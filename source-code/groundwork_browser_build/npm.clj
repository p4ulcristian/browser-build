(ns groundwork-browser-build.npm
  "The npm packages a release build takes its JavaScript from, checked
   before any code is compiled: each one the build resolves (react, and
   what react needs, process among them) must be installed as one of the
   app's own dependencies, so the deploy, which installs without
   development packages (npm ci --omit=dev), has it too.

   Two checks, both before the ClojureScript is compiled:
   - a package the build needs that isn't installed at all stops the
     build where shadow-cljs resolves it (its missing-js error, told
     again with what to do);
   - a package that is installed only for development (in
     devDependencies, or only because a development package needs it, as
     process comes with the shadow-cljs npm package) stops the build at
     the build hook check-npm-packages (stage :compile-prepare, after
     everything is resolved).

   What is installed, and why, is read from npm's own record,
   node_modules/.package-lock.json, beside the package.json in the folder
   the build runs in, as shadow-cljs resolves from node_modules there: an
   entry marked dev, devOptional or extraneous, or none, is not the app's."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [jsonista.core :as json]))

(def ^:private installed-record "node_modules/.package-lock.json")

(defn- say-what-to-do [package-name]
  (str "add \"" package-name "\" to package.json's \"dependencies\" (not \"devDependencies\": "
       "the deploy installs without them, npm ci --omit=dev) and run npm install"))

(defn- find-path-in-folder
  "The resource's file, as a path from the folder the build runs in
   (shadow-cljs's resource names munge dots: big.js is big_DOT_js)."
  [{:keys [file resource-name]}]
  (let [folder (str (.getCanonicalPath (io/file ".")) "/")
        path   (some-> file io/file .getCanonicalPath)]
    (if (and path (str/starts-with? path folder))
      (subs path (count folder))
      resource-name)))

(defn- find-installed-key
  "The record's key for the package a resource is in:
   node_modules/react-dom/node_modules/scheduler/… -> node_modules/react-dom/node_modules/scheduler."
  [{:keys [package-name] :as resource}]
  (let [path   (find-path-in-folder resource)
        marker (str "node_modules/" package-name)
        at     (str/last-index-of path (str marker "/"))]
    (when at (subs path 0 (+ at (count marker))))))

(defn- is-the-apps? [installed key]
  (let [entry (get installed key)]
    (and (map? entry) (not (or (get entry "dev") (get entry "devOptional") (get entry "extraneous"))))))

(defn- read-installed []
  (let [file (io/file installed-record)]
    (when-not (.exists file)
      (throw (ex-info (str "No " installed-record " here: install the npm packages first (npm install)")
                      {:type :groundwork-browser-build/npm-not-installed})))
    (get (json/read-value file) "packages")))

(defn- list-npm-resources [build-state]
  (->> (:build-sources build-state)
       (map #(get-in build-state [:sources %]))
       (filter #(and (= :shadow-js (:type %)) (:package-name %)
                     (str/includes? (find-path-in-folder %) "node_modules/")))))

(defn find-development-only-packages
  "The packages the build took JavaScript from that aren't installed as
   the app's own: [{:package \"process\" :installed-as \"node_modules/process\"} …]."
  [build-state installed]
  (->> (list-npm-resources build-state)
       (keep (fn [resource]
               (let [key (find-installed-key resource)]
                 (when-not (is-the-apps? installed key)
                   {:package (:package-name resource) :installed-as key}))))
       (distinct)
       (sort-by :package)
       (vec)))

(defn check-npm-packages
  "The build hook, release builds only, after shadow-cljs has resolved
   everything and before it compiles: every npm package the build takes
   JavaScript from is installed as the app's own dependency, or the build
   stops, naming each and what to do. A build that takes no npm package
   reads nothing."
  {:shadow.build/stage :compile-prepare}
  [build-state]
  (let [problems (when (seq (list-npm-resources build-state))
                   (find-development-only-packages build-state (read-installed)))]
    (when (seq problems)
      (throw (ex-info (str "The build needs npm packages that are installed only for development:\n  "
                           (str/join "\n  " (map #(str "\"" (:package %) "\": " (say-what-to-do (:package %))) problems)))
                      {:type :groundwork-browser-build/npm-package-only-for-development :packages (mapv :package problems)})))
    build-state))

(defn- find-missing-js
  "The missing-js error shadow-cljs throws when the build needs a package
   that isn't installed, in what was thrown or its causes."
  [throwable]
  (some #(when (= :shadow.build.resolve/missing-js (:tag (ex-data %))) (ex-data %))
        (take-while some? (iterate ex-cause throwable))))

(defn- find-package-of-require
  "react/jsx-runtime -> react; @scope/name/x -> @scope/name."
  [required]
  (let [parts (str/split required #"/")]
    (str/join "/" (take (if (str/starts-with? required "@") 2 1) parts))))

(declare tell-missing-package-again)

(defn- find-own-refusal
  "check-npm-packages's refusal, which shadow-cljs wraps in its own
   \"Hook … failed\"."
  [throwable]
  (some #(when (= :groundwork-browser-build/npm-package-only-for-development (:type (ex-data %))) %)
        (take-while some? (iterate ex-cause throwable))))

(defn tell-npm-problem-plainly
  "What a release build threw; or, when it was about an npm package, what
   to do: check-npm-packages's refusal itself, not shadow-cljs's wrapping
   of it, and a package the build needs and nobody installed told again."
  [throwable]
  (if-let [refusal (find-own-refusal throwable)]
    refusal
    (tell-missing-package-again throwable)))

(defn tell-missing-package-again
  "What a release build threw, or, when it was a package the build needs
   and nobody installed, the same told with what to do."
  [throwable]
  (if-let [{required :require required-from :require-from} (find-missing-js throwable)]
    (let [package-name (find-package-of-require required)]
      (ex-info (str "The build needs the npm package \"" package-name "\" (" required-from " requires \"" required
                    "\"), which isn't installed: " (say-what-to-do package-name))
               {:type :groundwork-browser-build/npm-package-missing :package package-name :required-from required-from}
               throwable))
    throwable))
