(ns showroom.stylesheets
  "Each module's stylesheet bundle, made after every compile (a shadow-cljs
   build hook): the stylesheets the module's namespaces named with
   showroom.api's use-stylesheet, in the order the namespaces are found,
   each whole and as written, one after another; a stylesheet two modules
   name is in the first one's (one loads after the other).

     <files-folder>/css/main.css, admin.css            while watching
     <files-folder>/css/main.<hash>.css, admin.<hash>.css   a release build, with
     <files-folder>/css/manifest.edn                   [{:module-id :main :output-name \"main.<hash>.css\"} …]

   The check: every .css file beside the build's namespaces must be named
   by one of them. A release build stops on any that isn't; while watching
   they are written to the app's log, :warn :showroom/unnamed-stylesheets."
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(defn- list-stylesheets-named-in [build-state resource-ids]
  (->> resource-ids
       (keep #(get-in build-state [:sources % :ns]))
       (mapcat #(get-in build-state [:compiler-env :cljs.analyzer/namespaces % :showroom/stylesheets]))
       (distinct)
       (vec)))

(defn- list-named-stylesheets
  "Every named stylesheet's path, in the order the namespaces are found."
  [build-state]
  (list-stylesheets-named-in build-state (:build-sources build-state)))

(defn- list-named-stylesheets-by-module
  "Each module's named stylesheets, by module id, in the order its
   namespaces are found; one named in two modules is the first one's."
  [build-state]
  (first (reduce (fn [[by-module taken] {:keys [module-id sources]}]
                   (let [paths (vec (remove taken (list-stylesheets-named-in build-state sources)))]
                     [(assoc by-module module-id paths) (into taken paths)]))
                 [{} #{}]
                 (:build-modules build-state))))

(defn- list-unnamed-stylesheets
  "The .css files beside the build's own namespaces (in folders, not jars)
   that no namespace names, as classpath paths."
  [build-state named]
  (let [named (set named)]
    (->> (:build-sources build-state)
         (keep #(let [{:keys [file resource-name]} (get-in build-state [:sources %])]
                  (when file [(.getParentFile (io/file file)) (str (.getParent (io/file resource-name)))])))
         (distinct)
         (mapcat (fn [[folder resource-folder]]
                   (for [css (.listFiles ^java.io.File folder)
                         :when (str/ends-with? (.getName css) ".css")]
                     (str resource-folder "/" (.getName css)))))
         (remove named)
         (sort))))

(defn make-bundle-text
  "The bundle: each stylesheet, whole, after a comment naming it."
  [paths]
  (str/join "\n" (for [path paths]
                   (str "/* " path " */\n" (slurp (io/resource path))))))

(defn- hash-text [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String text "UTF-8"))]
    (str/upper-case (apply str (map #(format "%02x" %) (take 8 digest))))))

(defn- write-if-changed! [file text]
  (io/make-parents file)
  (when-not (and (.exists (io/file file)) (= text (slurp file)))
    (spit file text)))

(defn write-bundles!
  "Writes each module's bundle into the folder: <module>.css, or when
   released, <module>.<hash>.css and the manifest of them all."
  [paths-by-module files-folder release?]
  (if release?
    (spit (io/file files-folder "css" "manifest.edn")
          (pr-str (vec (for [[module-id paths] (sort-by key paths-by-module)
                             :let [text        (make-bundle-text paths)
                                   output-name (str (name module-id) "." (hash-text text) ".css")]]
                         (do (write-if-changed! (io/file files-folder "css" output-name) text)
                             {:module-id module-id :output-name output-name})))))
    (doseq [[module-id paths] paths-by-module]
      (write-if-changed! (io/file files-folder "css" (str (name module-id) ".css")) (make-bundle-text paths)))))

(defn- find-modified-times
  "Each named stylesheet's last save, by path, for those in folders (a
   stylesheet in a jar doesn't change while watching)."
  [paths]
  (into {} (for [path paths
                 :let [url (io/resource path)]
                 :when (and url (= "file" (.getProtocol url)))]
             [path (.lastModified (io/file url))])))

(def last-bundle
  "The last bundles written while watching, for re-bundling when a
   stylesheet is saved: {:paths-by-module {module-id […]} :files-folder …
   :times {path saved-at}}, the times read before the stylesheets were, so
   a save while bundling is seen at the next check."
  (atom nil))

(defn bundle-stylesheets
  "The build hook, after every compile: the check, then the bundle."
  {:shadow.build/stage :flush}
  [build-state {:keys [files-folder log]}]
  (let [release?        (= :release (:shadow.build/mode build-state))
        named           (list-named-stylesheets build-state)
        unnamed         (list-unnamed-stylesheets build-state named)
        paths-by-module (list-named-stylesheets-by-module build-state)]
    (when (seq unnamed)
      (if release?
        (throw (ex-info (str "Stylesheets no namespace names (name each with styles/use-stylesheet):\n  "
                             (str/join "\n  " unnamed))
                        {:type :showroom/unnamed-stylesheets :stylesheets (vec unnamed)}))
        (log :warn :showroom/unnamed-stylesheets {:showroom/stylesheets (vec unnamed)})))
    (if release?
      (write-bundles! paths-by-module files-folder true)
      (let [times (find-modified-times named)]
        (write-bundles! paths-by-module files-folder false)
        (reset! last-bundle {:paths-by-module paths-by-module :files-folder files-folder :times times})))
    build-state))

(defn start-watching-stylesheets!
  "Re-bundles when a named stylesheet was saved after the last bundle was
   written, checked every check-every-ms; shadow-cljs then swaps the new
   bundle into open pages. Each check compares with the times the last
   bundle was made from (by a compile or a check), not with the check
   before: a save before the thread first runs is still seen. Runs on a
   thread of its own, which a failed check doesn't end (it says why and
   checks again). Returns a function that stops it. A failed check is
   written to log, :error :showroom/bundle-failed."
  [check-every-ms log]
  (let [running? (atom true)
        check!   (fn []
                   (let [{:keys [paths-by-module files-folder times]} @last-bundle
                         now (find-modified-times (distinct (apply concat (vals paths-by-module))))]
                     ;; nothing until a compile has bundled
                     (when (and files-folder (not= times now))
                       (write-bundles! paths-by-module files-folder false)
                       (swap! last-bundle #(if (= paths-by-module (:paths-by-module %)) (assoc % :times now) %)))))
        thread   (Thread.
                  ^Runnable
                  (fn []
                    (while @running?
                      (try
                        (Thread/sleep (long check-every-ms))
                        (check!)
                        (catch InterruptedException _)
                        (catch Exception e
                          (log :error :showroom/bundle-failed {:error e})))))
                  "showroom-stylesheets")]
    (doto thread (.setDaemon true) (.start))
    (fn []
      (reset! running? false)
      (.interrupt thread))))
