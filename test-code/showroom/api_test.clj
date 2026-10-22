(ns showroom.api-test
  "Release builds of small entries: the code compiles, optimized, into one
   file with a hashed name and its manifest; the named stylesheet becomes
   the bundle; a missing or an unnamed stylesheet stops the build. Every
   option is required. And one watch: compiled, a saved stylesheet
   rebundled, a saved namespace compiled again, stopped."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [showroom.api :as showroom])
  (:import
   (java.net ServerSocket)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.zip GZIPInputStream)))

(deftest the-names-of-the-api
  (is (= '#{make-build start-watching! stop-watching! build-for-release! use-stylesheet}
         (set (keys (ns-publics 'showroom.api))))))

(defn- make-folder [] (str (Files/createTempDirectory "showroom-build" (make-array FileAttribute 0))))

(defn- make-release-options [entry]
  {:entry              entry
   :files-folder       (make-folder)
   :cache-folder       (make-folder)
   :compile-threads    2
   :output-feature-set :es2020
   :source-maps?       false
   :gzip-level         9})

(def ^:private lines
  "What the tests' log has been handed, [level event data] each."
  (atom []))

(defn- log [level event data] (swap! lines conj [level event data]) nil)

(defn- make-watch-options []
  {:log                       log
   :entry                     'showroom.watch-check.entry/start!
   :files-folder              (make-folder)
   :cache-folder              (make-folder)
   :compile-threads           2
   :output-feature-set        :es2020
   :devtools-host             "localhost"
   :devtools-port             0
   :devtools-url              nil
   :nrepl                     nil
   :socket-repl               nil
   :hot-reload?               true
   :reload-strategy           :optimized
   :hud                       #{:errors :warnings :progress}
   :stylesheet-check-every-ms 100})

(defn- find-refusal [f]
  (try (f) :nothing
       (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :errors]))))

(defn- gunzip [file]
  (with-open [in (GZIPInputStream. (io/input-stream file))]
    (slurp in)))

(deftest a-release-build
  (let [{:keys [files-folder] :as options} (make-release-options 'showroom.build-check/start!)]
    (with-out-str (showroom/build-for-release! options))
    (let [[{:keys [module-id output-name]}] (edn/read-string (slurp (io/file files-folder "js" "manifest.edn")))
          js (io/file files-folder "js" output-name)]
      (is (= :main module-id))
      (is (re-matches #"main\.[0-9A-F]{32}\.js" output-name) "the name carries the whole hash of the content")
      (is (.exists js))
      (is (= (slurp js) (gunzip (str js ".gz"))) "and its gzipped copy, the same bytes")
      (is (not (.exists (io/file (str js ".map")))) "no source map: :source-maps? false"))
    (let [[{:keys [output-name]}] (edn/read-string (slurp (io/file files-folder "css" "manifest.edn")))
          css (io/file files-folder "css" output-name)]
      (is (re-matches #"main\.[0-9A-F]{16}\.css" output-name))
      (is (= "/* showroom/build_check.css */\n.build-check { color: rebeccapurple; }\n" (slurp css))
          "the named stylesheet, whole, as written")
      (is (= (slurp css) (gunzip (str css ".gz")))))))

(deftest a-missing-stylesheet-stops-the-build
  (is (thrown? Exception
               (with-out-str (showroom/build-for-release! (make-release-options 'showroom.missing-check.entry/start!))))))

(deftest a-stylesheet-no-namespace-names-stops-a-release
  (let [message (try (with-out-str (showroom/build-for-release! (make-release-options 'showroom.unnamed-check.entry/start!)))
                     nil
                     (catch Exception e (pr-str e)))]
    (is (re-find #"forgotten\.css" (str message)))
    (is (not (re-find #"named\.css\n" (str message))))))

(deftest every-option-is-required
  (testing "build-for-release! names each missing option, before anything is built"
    (let [{:keys [type errors]} (find-refusal #(showroom/build-for-release! {:entry 'app.browser.main/start! :files-folder "target/public"}))]
      (is (= :showroom/bad-options type))
      (is (= #{:cache-folder :compile-threads :output-feature-set :source-maps? :gzip-level} (set (keys errors))))))
  (testing "start-watching! names each missing option, before shadow-cljs starts"
    (let [{:keys [type errors]} (find-refusal #(showroom/start-watching! (dissoc (make-watch-options) :devtools-host :hud :nrepl :log)))]
      (is (= :showroom/bad-options type))
      (is (= #{:devtools-host :hud :nrepl :log} (set (keys errors))) "the app's log among them")))
  (testing "an unknown option too"
    (is (= {:type :showroom/bad-options :errors {:gzip-levle ["disallowed key"]}}
           (find-refusal #(showroom/build-for-release! (assoc (make-release-options 'showroom.build-check/start!) :gzip-levle 9))))))
  (testing "the build says what the options say"
    (let [build (showroom/make-build :watch (assoc (make-watch-options) :hot-reload? false :hud #{} :reload-strategy :full))]
      (is (= {:autoload false :hud false :reload-strategy :full}
             (select-keys (:devtools build) [:autoload :hud :reload-strategy]))))
    (is (= :advanced (get-in (showroom/make-build :release (make-release-options 'showroom.build-check/start!))
                             [:compiler-options :optimizations])))))

(def ^:private entry-file (io/file "test-code/showroom/watch_check/entry.cljs"))
(def ^:private css-file (io/file "test-code/showroom/watch_check/entry.css"))
(def ^:private stray-file (io/file "test-code/showroom/watch_check/stray.css"))

(defn- wait-until [f]
  (loop [n 0]
    (cond (try (f) (catch Exception _ false)) true
          (> n 300) false
          :else (do (Thread/sleep 100) (recur (inc n))))))

(deftest watching
  (testing "a devtools port in use is refused, not moved"
    (with-open [taken (ServerSocket. 0)]
      (is (thrown? Exception (with-out-str (showroom/start-watching! (assoc (make-watch-options) :devtools-port (.getLocalPort taken))))))))
  (let [entry-text (slurp entry-file)
        css-text   (slurp css-file)
        {:keys [files-folder cache-folder] :as options} (make-watch-options)
        output     (java.io.StringWriter.)]
    (try
      (let [watching (binding [*out* output] (showroom/start-watching! options))
            bundle   (io/file files-folder "css" "main.css")
            code     (io/file files-folder "js" "cljs-runtime" "showroom.watch_check.entry.js")]
        (try
          (testing "the first compile: the page's code and its bundle"
            (is (.exists (io/file files-folder "js" "main.js")))
            (is (str/includes? (slurp code) "watch-check-one"))
            (is (str/includes? (slurp bundle) "rebeccapurple")))
          (testing "shadow-cljs wrote its port into the cache folder, not .shadow-cljs"
            (is (.exists (io/file cache-folder "http.port"))))
          (testing "a stylesheet saved right after the start is rebundled"
            (spit css-file ".watch-check { color: tomato; }\n")
            (is (wait-until #(str/includes? (slurp bundle) "tomato"))))
          (testing "a saved namespace is compiled again; a stylesheet no namespace names is written to the log"
            (reset! lines [])
            (spit stray-file ".stray { color: red; }\n")
            (spit entry-file (str/replace entry-text "watch-check-one" "watch-check-two"))
            (is (wait-until #(str/includes? (slurp code) "watch-check-two")))
            (is (wait-until #(some #{[:warn :showroom/unnamed-stylesheets {:showroom/stylesheets ["showroom/watch_check/stray.css"]}]}
                                   @lines))))
          (finally
            (binding [*out* output] (is (= :stopped (showroom/stop-watching! watching)))))))
      (finally
        (io/delete-file stray-file true)
        (spit entry-file entry-text)
        (spit css-file css-text)))))
