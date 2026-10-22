(ns showroom.api
  "The browser's code, built by shadow-cljs from a build showroom writes as
   data; the app gives only its entry (the function the page runs). And the
   macro a namespace names its stylesheets with, which the build bundles.

     ;; development: compiles, then again on every save, and swaps the new
     ;; code into open tabs (the app's server starts it when it watches)
     (def watching (showroom/start-watching! {:entry 'app.browser.main/start! :files-folder \"target/public\"}))
     (showroom/stop-watching! watching)

     ;; the deploy, once, before the app starts:
     ;;   clojure -X:browser-release :entry app.browser.main/start! :files-folder '\"target/public\"'
     ;; writes target/public/js/main.<hash>.js, css/main.<hash>.css and a
     ;; manifest for each, which the page shell reads

     ;; in the app's browser code (showroom.api's ClojureScript half):
     (def order-card-styles (styles/use-stylesheet \"app/orders/order_card.css\"))

   shadow-cljs is needed only to build: production starts without it, so
   the app's server loads this namespace only when it watches."
  (:require
   [cljs.analyzer :as analyzer]
   [cljs.env :as env]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]
   [showroom.stylesheets :as stylesheets])
  (:import
   (java.util.zip GZIPOutputStream)))

(defn make-build
  "The shadow-cljs build: one module, main, starting at the app's entry;
   its files in <files-folder>/js, served at /js. After every compile the
   stylesheets the namespaces named become <files-folder>/css's bundle;
   while watching, shadow-cljs swaps a changed bundle into open pages."
  [{:keys [entry files-folder]}]
  {:build-id    :showroom
   :target      :browser
   :output-dir  (str files-folder "/js")
   :asset-path  "/js"
   :modules     {:main {:init-fn (symbol entry)}}
   :build-hooks [(list `stylesheets/bundle-stylesheets {:files-folder files-folder})]
   :devtools    {:watch-dir files-folder}})

(defn start-watching!
  "Starts shadow-cljs in this JVM and watches the build: compiled now,
   again on every save, the new code swapped into open tabs. Returns once
   the first compile is done."
  [build]
  (shadow-server/start!)
  (shadow/watch* (make-build build) {})
  (assoc build :stop-watching-stylesheets (stylesheets/start-watching-stylesheets!)))

(defn stop-watching!
  "Stops watching, and shadow-cljs."
  [{:keys [stop-watching-stylesheets]}]
  (stop-watching-stylesheets)
  (shadow/stop-worker :showroom)
  (shadow-server/stop!))

(defn- write-gzipped-copy!
  "file.gz beside the file, for the server to send to browsers that take gzip."
  [file]
  (with-open [in  (io/input-stream file)
              out (GZIPOutputStream. (io/output-stream (str file ".gz")))]
    (io/copy in out)))

(defn- gzip-built-files! [files-folder]
  (doseq [kind ["js" "css"]
          {:keys [output-name]} (edn/read-string (slurp (io/file files-folder kind "manifest.edn")))]
    (write-gzipped-copy! (io/file files-folder kind output-name))))

(defn build-for-release!
  "Compiles the browser's code once, optimized, each file's name with a
   hash of its content (cached for ever, never stale), the manifests
   listing them (js/manifest.edn, css/manifest.edn), and a gzipped copy of
   each (file.gz), which the server sends to browsers that take gzip. The
   CSS isn't minified: gzipped, its spaces and comments cost almost
   nothing. A stylesheet beside the namespaces that none names stops it.
   For the deploy: clojure -X:browser-release :entry … :files-folder …"
  [{:keys [files-folder] :as build}]
  (shadow/with-runtime
    (shadow/release* (assoc (make-build build) :module-hash-names true) {}))
  (gzip-built-files! files-folder)
  :built)

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
