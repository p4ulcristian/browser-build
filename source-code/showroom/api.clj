(ns showroom.api
  "The browser's code, built by shadow-cljs from a build showroom writes as
   data; the app gives its entry (the function the page runs) and every
   option (showroom.settings: every key required, none filled in). And the
   macro a namespace names its stylesheets with, which the build bundles.

     ;; development: compiles, then again on every save, and swaps the new
     ;; code into open tabs (the app's server starts it when it watches)
     (def watching (showroom/start-watching!
                    {:log log                                  ; the app's (logbook's)
                     :entry 'app.browser.main/start! :files-folder \"target/public\"
                     :cache-folder \".shadow-cljs\" :compile-threads 4 :output-feature-set :es2020
                     :devtools-host \"localhost\" :devtools-port 9630 :devtools-url nil
                     :nrepl nil :socket-repl nil
                     :hot-reload? true :reload-strategy :optimized :hud #{:errors :warnings :progress}
                     :stylesheet-check-every-ms 300}))
     (showroom/stop-watching! watching)

     ;; the deploy, once, before the app starts:
     ;;   clojure -X:browser-release :entry app.browser.main/start! :files-folder '\"target/public\"' …
     ;; writes target/public/js/main.<hash>.js, css/main.<hash>.css and a
     ;; manifest for each, which the page shell reads

     ;; in the app's browser code (showroom.api's ClojureScript half):
     (def order-card-styles (styles/use-stylesheet \"app/orders/order_card.css\"))

   shadow-cljs is needed only to build: production starts without it, so
   the app's server loads this namespace only when it watches. shadow-cljs
   reads no shadow-cljs.edn: its whole configuration is written here, from
   the options."
  (:require
   [cljs.analyzer :as analyzer]
   [cljs.env :as env]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]
   [shadow.cljs.devtools.server.common :as shadow-common]
   [shadow.cljs.devtools.server.runtime :as shadow-runtime]
   [shadow.runtime.services :as shadow-services]
   [showroom.settings :as settings]
   [showroom.stylesheets :as stylesheets])
  (:import
   (java.io OutputStream)
   (java.util.zip CRC32 Deflater DeflaterOutputStream)))

(defn make-build
  "The shadow-cljs build, :watch or :release, from start-watching!'s or
   build-for-release!'s options: one module, main, starting at the app's
   entry; its files in <files-folder>/js, served at /js (the app's server
   serves the files folder at /). After every compile the stylesheets the
   namespaces named become <files-folder>/css's bundle; while watching,
   shadow-cljs swaps a changed bundle into open pages."
  [mode {:keys [entry files-folder output-feature-set log] :as options}]
  (case mode
    :watch   (settings/check-watch-options options)
    :release (settings/check-release-options options))
  (let [{:keys [devtools-url hot-reload? reload-strategy hud]} options]
    (cond-> {:build-id         :showroom
             :target           :browser
             :output-dir       (str files-folder "/js")
             :asset-path       "/js"
             :modules          {:main {:init-fn entry}}
             ;; a release build has no log: it stops where a watch writes a warning
             :build-hooks      [(list `stylesheets/bundle-stylesheets {:files-folder files-folder :log log})]
             :compiler-options {:output-feature-set output-feature-set}}
      (= :release mode)
      (-> (assoc-in [:compiler-options :optimizations] :advanced)
          ;; the name carries the whole hash of the content (32 hex digits)
          (assoc :module-hash-names true))

      (= :watch mode)
      (assoc :devtools (cond-> {:watch-dir         files-folder
                                :autoload          hot-reload?
                                :reload-strategy   reload-strategy
                                :hud               (if (seq hud) hud false)
                                :use-document-host true
                                :log               true}
                         devtools-url (assoc :devtools-url devtools-url))))))

(defn- make-shadow-config
  "shadow-cljs's whole configuration, written out, in place of a
   shadow-cljs.edn (which it would read from the working folder, with the
   user's own config file)."
  [{:keys [cache-folder compile-threads devtools-host devtools-port nrepl socket-repl]} mode]
  (cond-> {:cache-root      cache-folder
           :cache-dir       cache-folder   ; where shadow-cljs looks for another server of this project
           :builds          {}
           :compile-threads compile-threads
           :log             {:level :info}}
    (= :watch mode)
    (assoc :http        {:host devtools-host :port devtools-port :strict true}
           :dev-http    {}
           :nrepl       (if nrepl (assoc nrepl :write-port-file false) false)
           :socket-repl (or socket-repl false))))

(defn start-watching!
  "Starts shadow-cljs in this JVM and watches the build: compiled now,
   again on every save, the new code swapped into open tabs. The options
   are showroom.settings/watch-options-shape's, every key required, checked
   first; showroom's own messages (a stylesheet no namespace names, a
   rebundle that failed) go to their :log, the app's. Returns once the
   first compile is done."
  [options]
  (let [build (make-build :watch options)]
    (reset! stylesheets/last-bundle nil)
    (shadow-server/start! (make-shadow-config options :watch))
    (try
      (shadow/watch* build {:autobuild true :sync true :verbose false})
      (assoc options :stop-watching-stylesheets
             (stylesheets/start-watching-stylesheets! (:stylesheet-check-every-ms options) (:log options)))
      (catch Throwable e
        (shadow-server/stop!)
        (throw e)))))

(defn stop-watching!
  "Stops watching, and shadow-cljs."
  [{:keys [stop-watching-stylesheets]}]
  (stop-watching-stylesheets)
  (shadow/stop-worker :showroom)
  (shadow-server/stop!)
  :stopped)

(defn- write-gzipped-copy!
  "file.gz beside the file, squeezed at level (1 to 9), for the server to
   send to browsers that take gzip. Written by hand, as GZIPOutputStream
   does, since it takes no level: the header (no name, no time, so the same
   file gives the same bytes), the deflated bytes, the CRC and the length."
  [file level]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file file)))
        crc   (doto (CRC32.) (.update ^bytes bytes))
        int-le (fn [^long n] (byte-array (map #(unchecked-byte (bit-and 0xff (bit-shift-right n (* 8 %)))) (range 4))))]
    (with-open [^OutputStream out (io/output-stream (str file ".gz"))]
      (.write out (byte-array (map unchecked-byte [0x1f 0x8b 8 0 0 0 0 0 0 0xff])))
      (let [deflater (Deflater. (int level) true)
            deflated (DeflaterOutputStream. out deflater)]
        (.write deflated ^bytes bytes)
        (.finish deflated)
        (.end deflater))
      (.write out ^bytes (int-le (.getValue crc)))
      (.write out ^bytes (int-le (alength ^bytes bytes))))))

(defn- gzip-built-files! [files-folder level]
  (doseq [kind ["js" "css"]
          {:keys [output-name]} (edn/read-string (slurp (io/file files-folder kind "manifest.edn")))]
    (write-gzipped-copy! (io/file files-folder kind output-name) level)))

(defn- run-in-own-shadow-runtime
  "Runs (f) with a shadow-cljs runtime of this configuration, made for it
   and stopped after; or in the one running (while watching)."
  [config f]
  (if (shadow-runtime/get-instance)
    (f)
    (let [runtime (-> {:config config}
                      (shadow-services/init (shadow-common/get-system-config config))
                      (shadow-services/start-all))]
      (try
        (shadow-runtime/set-instance! runtime)
        (f)
        (finally
          (shadow-runtime/reset-instance!)
          (shadow-services/stop-all runtime))))))

(defn build-for-release!
  "Compiles the browser's code once, optimized, each file's name with a
   hash of its content (cached for ever, never stale), the manifests
   listing them (js/manifest.edn, css/manifest.edn), and a gzipped copy of
   each (file.gz), which the server sends to browsers that take gzip. The
   CSS isn't minified: gzipped, its spaces and comments cost almost
   nothing. A stylesheet beside the namespaces that none names stops it.
   The options are showroom.settings/release-options-shape's, every key
   required, checked first. For the deploy: clojure -X:browser-release
   :entry … :files-folder … (and every other option)."
  [{:keys [files-folder source-maps? gzip-level] :as options}]
  (let [build (make-build :release options)]
    (run-in-own-shadow-runtime (make-shadow-config options :release)
      #(shadow/release* build {:debug false :source-maps source-maps? :pseudo-names false :verbose false}))
    (gzip-built-files! files-folder gzip-level)
    :built))

(defmacro use-stylesheet
  "Names a stylesheet this namespace needs, by its path on the classpath,
   as a query names its .sql file:

     (def order-card-styles (styles/use-stylesheet \"app/orders/order_card.css\"))

   Runs on the JVM while shadow-cljs compiles the namespace, where files
   can be read: a missing file stops the build here. The build gathers
   every named stylesheet into the page's one bundle, in the order the
   namespaces are found. Returns the path."
  [path]
  (when-not (string? path)
    (throw (ex-info "use-stylesheet takes the path as text, written out" {:type :showroom/bad-path :path path})))
  (when-not (io/resource path)
    (throw (ex-info (str "No stylesheet " path " on the classpath (named in " analyzer/*cljs-ns* ")")
                    {:type :showroom/missing-file :path path :ns analyzer/*cljs-ns*})))
  (swap! env/*compiler* update-in [:cljs.analyzer/namespaces analyzer/*cljs-ns* :showroom/stylesheets]
         (fn [paths] (if (some #{path} paths) paths (conj (or paths []) path))))
  path)
