(ns browser-build.api-test
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
   [browser-build.api :as browser-build]
   [browser-build.lightningcss :as lightningcss])
  (:import
   (java.net ServerSocket)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util.zip GZIPInputStream)))

(deftest the-names-of-the-api
  (is (= '#{make-build start-watching! stop-watching! build-for-release! use-stylesheet}
         (set (keys (ns-publics 'browser-build.api))))))

(defn- make-folder [] (str (Files/createTempDirectory "browser-build-build" (make-array FileAttribute 0))))

(defn- make-release-options [entry]
  {:entry              entry
   :modules            {}
   :files-folder       (make-folder)
   :cache-folder       (make-folder)
   :compile-threads    2
   :output-feature-set :es2020
   :resolve            {}
   :closure-defines    {}
   :warnings-as-errors #{}
   :silenced-warnings  #{}
   :css                nil
   :source-maps?       false
   :gzip-level         9})

(def ^:private lines
  "What the tests' log has been handed, [level event data] each."
  (atom []))

(defn- log [level event data] (swap! lines conj [level event data]) nil)

(defn- make-watch-options []
  {:log                       log
   :entry                     'browser-build.watch-check.entry/start!
   :modules                   {}
   :files-folder              (make-folder)
   :cache-folder              (make-folder)
   :compile-threads           2
   :output-feature-set        :es2020
   :resolve                   {}
   :closure-defines           {}
   :warnings-as-errors        #{}
   :silenced-warnings         #{}
   :css                       nil
   :preloads                  []
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
  (let [{:keys [files-folder] :as options} (make-release-options 'browser-build.build-check/start!)]
    (with-out-str (browser-build/build-for-release! options))
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
      (is (= "/*! browser_build/build_check.css */\n.build-check { color: rebeccapurple; }\n" (slurp css))
          "the named stylesheet, whole, as written: :css nil")
      (is (= (slurp css) (gunzip (str css ".gz")))))))

(deftest a-release-build-with-a-module-loaded-on-demand
  (let [{:keys [files-folder] :as options} (assoc (make-release-options 'browser-build.modules-check.main/start!)
                                                  :modules {:admin {:entries    '[browser-build.modules-check.admin]
                                                                    :depends-on #{:main}
                                                                    :start      'browser-build.modules-check.admin/start!}})]
    (with-out-str (browser-build/build-for-release! options))
    (let [js-files  (edn/read-string (slurp (io/file files-folder "js" "manifest.edn")))
          css-files (edn/read-string (slurp (io/file files-folder "css" "manifest.edn")))
          js-of     #(slurp (io/file files-folder "js" (:output-name (first (filter (fn [f] (= % (:module-id f))) js-files)))))
          css-of    #(slurp (io/file files-folder "css" (:output-name (first (filter (fn [f] (= % (:module-id f))) css-files)))))]
      (is (= [:admin :main] (sort (map :module-id js-files))) "two modules, each its own file")
      (is (= [:admin :main] (map :module-id css-files)) "and each its own stylesheet bundle")
      (is (every? #(re-matches #"(main|admin)\.[0-9A-F]+\.css" (:output-name %)) css-files))
      (is (= "/*! browser_build/modules_check/main.css */
.main-check { color: rebeccapurple; }
" (css-of :main)))
      (is (= "/*! browser_build/modules_check/admin.css */
.admin-check { color: tomato; }
" (css-of :admin)))
      (is (str/includes? (js-of :admin) "admin-module-here") "the module's code in its own file")
      (is (str/includes? (js-of :admin) "admin-start-here") "its :start kept and run, not dropped as unused")
      (is (not (str/includes? (js-of :main) "admin-module-here")) "not in main")
      (is (str/includes? (js-of :main) (:output-name (first (filter #(= :admin (:module-id %)) js-files))))
          "main knows where the module's hashed file is, for the loader")
      (is (every? #(.exists (io/file files-folder "css" (str (:output-name %) ".gz"))) css-files) "gzipped too"))))

(def ^:private pins
  "lightningcss 1.33.0, the sha256 of its program on each platform."
  {:version "1.33.0"
   :sha256  {"linux-x64"    "e7175508098a87b4e9788a695e9a4d780db727f317f69c1c452d6d0db1fc9817"
             "linux-arm64"  "aad374206d8e64991cccb94e12e01951df7370814bcbe4bd6dee74fa52282500"
             "darwin-x64"   "348fd8afa8dc5c29a060f0dd85c78af1dc4ba4d9e5eeff3d1a8af9a992de6dc6"
             "darwin-arm64" "c6a00cbd56610472494060d6b53b74ffd19442c6979b9346667e9abadd2c6c45"
             "windows-x64"  "be3ed8a564eea2cf8e8d0a92d807834ef00380b6889566b35ef2c68ff6426b34"}})

(def ^:private targets "chrome >= 119, safari >= 16.4, firefox >= 128")

(def ^:private downloaded
  "The pinned program, downloaded once for the tests that only need it there."
  (delay (lightningcss/find-program! (make-folder) pins)))

(defn- put-program-in! [cache-folder]
  (let [file (io/file cache-folder "lightningcss" (:version pins) (lightningcss/find-platform)
                      (.getName (io/file @downloaded)))]
    (io/make-parents file)
    (io/copy (io/file @downloaded) file)
    cache-folder))

(defn- find-type-of-cause
  "The :type of the first ex-info along the causes that has one."
  [^Throwable e]
  (some #(:type (ex-data %)) (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn- find-release-refusal [options]
  (try (with-out-str (browser-build/build-for-release! options)) :built
       (catch Exception e (find-type-of-cause e))))

(deftest a-release-build-rewrites-its-stylesheets-for-the-apps-browsers
  (let [{:keys [files-folder cache-folder] :as options} (assoc (make-release-options 'browser-build.css-check.entry/start!)
                                                              :css {:targets targets :lightningcss pins})]
    (with-out-str (browser-build/build-for-release! options))
    (let [[{:keys [output-name]}] (edn/read-string (slurp (io/file files-folder "css" "manifest.edn")))
          css (slurp (io/file files-folder "css" output-name))]
      (is (str/includes? css "/*! browser_build/css_check/entry.css */") "the comment naming the file, kept")
      (is (str/includes? css ".css-check .title {") "nesting flattened, for Safari 16.4")
      (is (str/includes? css "-webkit-backdrop-filter: blur(4px);") "the prefix Safari needs")
      (is (str/includes? css "color: tomato;") "not minified")
      (is (= css (gunzip (io/file files-folder "css" (str output-name ".gz"))))))
    (is (.exists (io/file cache-folder "lightningcss" "1.33.0" (lightningcss/find-platform)))
        "downloaded into the build's cache folder"))
  (testing "a checksum that differs stops the build, named"
    (let [cache-folder (put-program-in! (make-folder))]
      (is (= :browser-build/lightningcss-checksum
             (find-release-refusal (assoc (make-release-options 'browser-build.css-check.entry/start!)
                                          :cache-folder cache-folder
                                          :css {:targets targets
                                                :lightningcss (assoc-in pins [:sha256 (lightningcss/find-platform)] (apply str (repeat 64 "0")))}))))))
  (testing "a platform the pins don't name stops the build, named, before anything is downloaded"
    (let [cache-folder (make-folder)]
      (is (= :browser-build/lightningcss-no-platform
             (find-release-refusal (assoc (make-release-options 'browser-build.css-check.entry/start!)
                                          :cache-folder cache-folder
                                          :css {:targets targets
                                                :lightningcss (update pins :sha256 dissoc (lightningcss/find-platform))}))))
      (is (not (.exists (io/file cache-folder "lightningcss"))))))
  (testing "lightningcss's own refusal stops the build, with its words"
    (is (= :browser-build/lightningcss-failed
           (find-release-refusal (assoc (make-release-options 'browser-build.css-check.entry/start!)
                                        :cache-folder (put-program-in! (make-folder))
                                        :css {:targets "no such browser >= 1" :lightningcss pins}))))))

(deftest the-css-step-is-written-out
  (is (= {:css {:lightningcss {:sha256 {"linux-x64" ["should be a sha256, 64 lowercase hex digits"]}}}}
         (:errors (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                                      :css {:targets targets
                                                                            :lightningcss {:version "1.33.0" :sha256 {"linux-x64" "abc"}}}))))))
  (is (= {:css {:lightningcss {:sha256 {"beos-x64" ["should be either \"darwin-arm64\", \"darwin-x64\", \"linux-arm64\", \"linux-x64\" or \"windows-x64\""]}}}}
         (:errors (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                                      :css {:targets targets
                                                                            :lightningcss {:version "1.33.0" :sha256 {"beos-x64" (apply str (repeat 64 "a"))}}})))))
      "a platform browser-build knows")
  (is (= {:css {:targets ["missing required key"]}}
         (:errors (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                                      :css {:lightningcss pins})))))))

(deftest a-missing-stylesheet-stops-the-build
  (is (thrown? Exception
               (with-out-str (browser-build/build-for-release! (make-release-options 'browser-build.missing-check.entry/start!))))))

(deftest a-stylesheet-no-namespace-names-stops-a-release
  (let [message (try (with-out-str (browser-build/build-for-release! (make-release-options 'browser-build.unnamed-check.entry/start!)))
                     nil
                     (catch Exception e (pr-str e)))]
    (is (re-find #"forgotten\.css" (str message)))
    (is (not (re-find #"named\.css\n" (str message))))))

(deftest every-option-is-required
  (testing "build-for-release! names each missing option, before anything is built"
    (let [{:keys [type errors]} (find-refusal #(browser-build/build-for-release! {:entry 'app.browser.main/start! :files-folder "target/public"}))]
      (is (= :browser-build/bad-options type))
      (is (= #{:modules :cache-folder :compile-threads :output-feature-set :resolve :closure-defines :warnings-as-errors
               :silenced-warnings :css :source-maps? :gzip-level}
             (set (keys errors))))))
  (testing "start-watching! names each missing option, before shadow-cljs starts"
    (let [{:keys [type errors]} (find-refusal #(browser-build/start-watching! (dissoc (make-watch-options) :devtools-host :hud :nrepl :log)))]
      (is (= :browser-build/bad-options type))
      (is (= #{:devtools-host :hud :nrepl :log} (set (keys errors))) "the app's log among them")))
  (testing "an unknown option too"
    (is (= {:type :browser-build/bad-options :errors {:gzip-levle ["disallowed key"]}}
           (find-refusal #(browser-build/build-for-release! (assoc (make-release-options 'browser-build.build-check/start!) :gzip-levle 9))))))
  (testing "the build says what the options say"
    (let [build (browser-build/make-build :watch (assoc (make-watch-options) :hot-reload? false :hud #{} :reload-strategy :full))]
      (is (= {:autoload false :hud false :reload-strategy :full}
             (select-keys (:devtools build) [:autoload :hud :reload-strategy]))))
    (is (= :advanced (get-in (browser-build/make-build :release (make-release-options 'browser-build.build-check/start!))
                             [:compiler-options :optimizations])))
    (is (= {:main  {:init-fn 'app.main/start!}
            :admin {:entries '[app.admin] :depends-on #{:main} :init-fn 'app.admin/start!}
            :quiet {:entries '[app.quiet] :depends-on #{:main}}}
           (:modules (browser-build/make-build :release (assoc (make-release-options 'app.main/start!)
                                                          :modules {:admin {:entries '[app.admin] :depends-on #{:main} :start 'app.admin/start!}
                                                                    :quiet {:entries '[app.quiet] :depends-on #{:main} :start nil}}))))
        "a module's :start is its :init-fn; nil, none")
    (is (= {:modules {:admin {:start ["missing required key"]}}}
           (:errors (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'app.main/start!)
                                                                        :modules {:admin {:entries '[app.admin] :depends-on #{:main}}})))))
        "a module names its start, nil for none")
    (let [build (browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                     :resolve {"process" {:target :file :file "shim.js"} "@maptiler/sdk" false}
                                                     :closure-defines '{malli.registry/type "custom"}
                                                     :warnings-as-errors #{:infer-warning}))]
      (is (= {"process" {:target :file :file "shim.js"} "@maptiler/sdk" false} (get-in build [:js-options :resolve])))
      (is (= '{malli.registry/type "custom"} (get-in build [:compiler-options :closure-defines])))
      (is (= #{:infer-warning} (get-in build [:compiler-options :warnings-as-errors]))))
    (testing "silenced warnings are shadow-cljs's :warnings, each false; preloads only while watching"
      (is (= {:unreachable-code false}
             (get-in (browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                          :silenced-warnings #{:unreachable-code}))
                     [:compiler-options :warnings])))
      (is (= {} (get-in (browser-build/make-build :release (make-release-options 'browser-build.build-check/start!))
                        [:compiler-options :warnings])))
      (is (= '[app.dev.tools] (get-in (browser-build/make-build :watch (assoc (make-watch-options) :preloads '[app.dev.tools]))
                                      [:devtools :preloads])))
      (is (= {:preloads ["disallowed key"]}
             (:errors (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                                          :preloads '[app.dev.tools])))))
          "a release has no preloads")
      (is (= {:silenced-warnings ["silenced and among :warnings-as-errors: [:infer-warning]"]}
             (:errors (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                                          :warnings-as-errors #{:infer-warning}
                                                                          :silenced-warnings #{:infer-warning})))))
          "a warning is silenced or stops the build, not both"))
    (is (= :browser-build/bad-options
           (:type (find-refusal #(browser-build/make-build :release (assoc (make-release-options 'browser-build.build-check/start!)
                                                                      :resolve {"x" {:target :file}})))))
        "a replacement says what it is replaced with")))

(def ^:private entry-file (io/file "test-code/browser_build/watch_check/entry.cljs"))
(def ^:private css-file (io/file "test-code/browser_build/watch_check/entry.css"))
(def ^:private stray-file (io/file "test-code/browser_build/watch_check/stray.css"))

(defn- wait-until [f]
  (loop [n 0]
    (cond (try (f) (catch Exception _ false)) true
          (> n 300) false
          :else (do (Thread/sleep 100) (recur (inc n))))))

(deftest watching
  (testing "a devtools port in use is refused, not moved"
    (with-open [taken (ServerSocket. 0)]
      (is (thrown? Exception (with-out-str (browser-build/start-watching! (assoc (make-watch-options) :devtools-port (.getLocalPort taken))))))))
  (let [entry-text (slurp entry-file)
        css-text   (slurp css-file)
        {:keys [files-folder cache-folder] :as options} (let [options (make-watch-options)]
                                                          (put-program-in! (:cache-folder options))
                                                          (assoc options :css {:targets targets :lightningcss pins}))
        output     (java.io.StringWriter.)]
    (try
      (let [watching (binding [*out* output] (browser-build/start-watching! options))
            bundle   (io/file files-folder "css" "main.css")
            code     (io/file files-folder "js" "cljs-runtime" "browser_build.watch_check.entry.js")]
        (try
          (testing "the first compile: the page's code and its bundle, through the CSS step"
            (is (.exists (io/file files-folder "js" "main.js")))
            (is (str/includes? (slurp code) "watch-check-one"))
            (is (str/includes? (slurp bundle) "color: #639;") "rebeccapurple, as lightningcss writes it"))
          (testing "shadow-cljs wrote its port into the cache folder, not .shadow-cljs"
            (is (.exists (io/file cache-folder "http.port"))))
          (testing "a stylesheet saved right after the start is rebundled, through the CSS step"
            (spit css-file ".watch-check { & .inner { color: tomato; } }\n")
            (is (wait-until #(str/includes? (slurp bundle) ".watch-check .inner {"))))
          (testing "a saved namespace is compiled again; a stylesheet no namespace names is written to the log"
            (reset! lines [])
            (spit stray-file ".stray { color: red; }\n")
            (spit entry-file (str/replace entry-text "watch-check-one" "watch-check-two"))
            (is (wait-until #(str/includes? (slurp code) "watch-check-two")))
            (is (wait-until #(some #{[:warn :browser-build/unnamed-stylesheets {:browser-build/stylesheets ["browser_build/watch_check/stray.css"]}]}
                                   @lines))))
          (finally
            (binding [*out* output] (is (= :stopped (browser-build/stop-watching! watching)))))))
      (finally
        (io/delete-file stray-file true)
        (spit entry-file entry-text)
        (spit css-file css-text)))))
