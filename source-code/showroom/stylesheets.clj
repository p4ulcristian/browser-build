(ns showroom.stylesheets
  "The page's one stylesheet bundle, made after every compile (a shadow-cljs
   build hook): the stylesheets each namespace named with showroom.api's
   use-stylesheet, in the order the namespaces are found, each whole and
   as written, one after another.

     <files-folder>/css/main.css               while watching
     <files-folder>/css/main.<hash>.css        a release build, with
     <files-folder>/css/manifest.edn           [{:output-name \"main.<hash>.css\"}]

   The check: every .css file beside the build's namespaces must be named
   by one of them. A release build stops on any that isn't; while watching
   they are printed."
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(defn- list-named-stylesheets
  "Every named stylesheet's path, in the order the namespaces are found."
  [build-state]
  (->> (:build-sources build-state)
       (keep #(get-in build-state [:sources % :ns]))
       (mapcat #(get-in build-state [:compiler-env :cljs.analyzer/namespaces % :showroom/stylesheets]))
       (distinct)
       (vec)))

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

(defn write-bundle!
  "Writes the bundle of these stylesheets into the folder: main.css, or
   when released, main.<hash>.css and its manifest."
  [paths files-folder release?]
  (let [text (make-bundle-text paths)]
    (if release?
      (let [output-name (str "main." (hash-text text) ".css")]
        (write-if-changed! (io/file files-folder "css" output-name) text)
        (spit (io/file files-folder "css" "manifest.edn") (pr-str [{:output-name output-name}])))
      (write-if-changed! (io/file files-folder "css" "main.css") text))))

(defn- find-modified-times
  "Each named stylesheet's last save, by path, for those in folders (a
   stylesheet in a jar doesn't change while watching)."
  [paths]
  (into {} (for [path paths
                 :let [url (io/resource path)]
                 :when (and url (= "file" (.getProtocol url)))]
             [path (.lastModified (io/file url))])))

(def last-bundle
  "The last bundle written while watching, for re-bundling when a
   stylesheet is saved: {:paths […] :files-folder … :times {path saved-at}},
   the times read before the stylesheets were, so a save while bundling is
   seen at the next check."
  (atom nil))

(defn bundle-stylesheets
  "The build hook, after every compile: the check, then the bundle."
  {:shadow.build/stage :flush}
  [build-state {:keys [files-folder]}]
  (let [release? (= :release (:shadow.build/mode build-state))
        named    (list-named-stylesheets build-state)
        unnamed  (list-unnamed-stylesheets build-state named)]
    (when (seq unnamed)
      (let [message (str "Stylesheets no namespace names (name each with styles/use-stylesheet):\n  "
                         (str/join "\n  " unnamed))]
        (if release?
          (throw (ex-info message {:type :showroom/unnamed-stylesheets :stylesheets (vec unnamed)}))
          (println message))))
    (if release?
      (write-bundle! named files-folder true)
      (let [times (find-modified-times named)]
        (write-bundle! named files-folder false)
        (reset! last-bundle {:paths named :files-folder files-folder :times times})))
    build-state))

(defn start-watching-stylesheets!
  "Re-bundles when a named stylesheet was saved after the last bundle was
   written, checked every check-every-ms; shadow-cljs then swaps the new
   bundle into open pages. Each check compares with the times the last
   bundle was made from (by a compile or a check), not with the check
   before: a save before the thread first runs is still seen. Runs on a
   thread of its own, which a failed check doesn't end (it prints why and
   checks again). Returns a function that stops it."
  [check-every-ms]
  (let [running? (atom true)
        check!   (fn []
                   (let [{:keys [paths files-folder times]} @last-bundle
                         now (find-modified-times paths)]
                     ;; nothing until a compile has bundled
                     (when (and files-folder (not= times now))
                       (write-bundle! paths files-folder false)
                       (swap! last-bundle #(if (= paths (:paths %)) (assoc % :times now) %)))))
        thread   (Thread.
                  ^Runnable
                  (fn []
                    (while @running?
                      (try
                        (Thread/sleep (long check-every-ms))
                        (check!)
                        (catch InterruptedException _)
                        (catch Exception e
                          (println "The stylesheet bundle failed:" (.getMessage e))))))
                  "showroom-stylesheets")]
    (doto thread (.setDaemon true) (.start))
    (fn []
      (reset! running? false)
      (.interrupt thread))))
