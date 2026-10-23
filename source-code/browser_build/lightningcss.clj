(ns browser-build.lightningcss
  "The CSS step: each bundle rewritten by lightningcss for the browsers
   the app supports (nesting flattened, -webkit- prefixes added, nothing
   minified). lightningcss is a program, not a library: the build's
   :css :lightningcss pins its version and the sha256 of the program for
   each platform the app builds on. It is downloaded once per platform,
   from npm's registry, as the tarball npm itself would fetch
   (lightningcss-cli-<platform>), with no node; kept in the build's cache
   folder, <cache-folder>/lightningcss/<version>/<platform>/; and checked
   against its sha256 when downloaded and every time it runs. A platform
   the pins don't name, or a program whose checksum differs, stops the
   build, named."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io ByteArrayOutputStream InputStream)
   (java.net URI)
   (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
   (java.nio.file CopyOption Files StandardCopyOption)
   (java.nio.file.attribute FileAttribute)
   (java.security MessageDigest)
   (java.util.zip GZIPInputStream)))

(def platforms
  "The platforms a program can be pinned for, by browser-build's name, each
   with the npm package that carries its program and the program's name
   in it."
  {"linux-x64"    {:package "lightningcss-cli-linux-x64-gnu"   :program "lightningcss"}
   "linux-arm64"  {:package "lightningcss-cli-linux-arm64-gnu" :program "lightningcss"}
   "darwin-x64"   {:package "lightningcss-cli-darwin-x64"      :program "lightningcss"}
   "darwin-arm64" {:package "lightningcss-cli-darwin-arm64"    :program "lightningcss"}
   "windows-x64"  {:package "lightningcss-cli-win32-x64-msvc"  :program "lightningcss.exe"}})

(defn find-platform
  "This machine's platform, by browser-build's name (platforms), or nil for
   one lightningcss has no program for here."
  []
  (let [os   (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))
        os   (cond (str/starts-with? os "linux")   "linux"
                   (str/starts-with? os "mac")     "darwin"
                   (str/starts-with? os "windows") "windows")
        arch (case arch
               ("amd64" "x86_64") "x64"
               ("aarch64" "arm64") "arm64"
               nil)
        name (when (and os arch) (str os "-" arch))]
    (when (contains? platforms name) name)))

(defn- hash-file [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [^InputStream in (io/input-stream file)]
      (loop []
        (let [n (.read in buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (apply str (map #(format "%02x" %) (.digest digest)))))

(defn- make-tarball-address [package version]
  (str "https://registry.npmjs.org/" package "/-/" package "-" version ".tgz"))

(defn- download! [address file]
  (let [client   (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NORMAL) (.build))
        request  (-> (HttpRequest/newBuilder (URI. address)) (.GET) (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofFile (.toPath (io/file file))))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "lightningcss wasn't downloaded: " address " answered " (.statusCode response))
                      {:type :browser-build/lightningcss-not-downloaded :address address :status (.statusCode response)})))))

(defn- read-tar-field [^bytes header from to]
  (let [end (or (first (filter #(zero? (aget header %)) (range from to))) to)]
    (String. header (int from) (int (- end from)) "UTF-8")))

(defn- copy-entry-of-tarball!
  "Copies the file at entry-name in a .tgz to out; false when it has none.
   A tar is 512-byte blocks: a header (the name, the size in octal), then
   the content, padded to a block."
  [tarball entry-name out]
  (with-open [^InputStream in (GZIPInputStream. (io/input-stream tarball))]
    (let [read-fully! (fn [^bytes buffer]
                        (loop [at 0]
                          (if (= at (alength buffer))
                            true
                            (let [n (.read in buffer at (- (alength buffer) at))]
                              (if (neg? n) false (recur (+ at n)))))))
          skip-fully! (fn [n] (read-fully! (byte-array n)))]
      (loop []
        (let [header (byte-array 512)]
          (if (or (not (read-fully! header)) (every? zero? header))
            false
            (let [name    (read-tar-field header 0 100)
                  prefix  (read-tar-field header 345 500)
                  path    (if (str/blank? prefix) name (str prefix "/" name))
                  size    (Long/parseLong (str/trim (read-tar-field header 124 136)) 8)
                  padding (mod (- 512 (mod size 512)) 512)]
              (if (= entry-name path)
                (let [content (byte-array size)]
                  (read-fully! content)
                  (io/copy content (io/file out))
                  true)
                (do (skip-fully! (+ size padding))
                    (recur))))))))))

(defn- check-program! [file sha256 platform version]
  (let [actual (hash-file file)]
    (when-not (= sha256 actual)
      (throw (ex-info (str "lightningcss " version " for " platform " has the sha256 " actual
                           ", not the pinned " sha256 " (" file ")")
                      {:type :browser-build/lightningcss-checksum :platform platform :version version
                       :expected sha256 :actual actual :file (str file)})))))

(defn find-program!
  "The path of the pinned lightningcss for this machine, checked against
   its sha256: from the cache folder, or downloaded into it first. Stops,
   named, on a platform the pins don't name, a download that fails, or a
   checksum that differs."
  [cache-folder {:keys [version sha256]}]
  (let [platform (find-platform)
        pinned   (get sha256 platform)]
    (when-not pinned
      (throw (ex-info (str "No lightningcss pinned for this platform, " (or platform (str (System/getProperty "os.name") " " (System/getProperty "os.arch")))
                           ": :css :lightningcss :sha256 names " (vec (sort (keys sha256))))
                      {:type :browser-build/lightningcss-no-platform :platform platform :pinned (vec (sort (keys sha256)))})))
    (let [{:keys [package program]} (get platforms platform)
          folder  (io/file cache-folder "lightningcss" version platform)
          file    (io/file folder program)]
      (when-not (.exists file)
        (.mkdirs folder)
        (let [work    (.toFile (Files/createTempDirectory (.toPath folder) "download" (make-array FileAttribute 0)))
              tarball (io/file work "package.tgz")
              fresh   (io/file work program)]
          (try
            (download! (make-tarball-address package version) tarball)
            (when-not (copy-entry-of-tarball! tarball (str "package/" program) fresh)
              (throw (ex-info (str "lightningcss " version "'s " package " has no package/" program)
                              {:type :browser-build/lightningcss-not-downloaded :package package :version version})))
            (check-program! fresh pinned platform version)
            (.setExecutable fresh true)
            (Files/move (.toPath fresh) (.toPath file) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
            (finally
              (doseq [f (reverse (file-seq work))] (io/delete-file f true))))))
      (check-program! file pinned platform version)
      ;; checked, so it is the pinned program: runnable, whoever copied it here
      (when-not (.canExecute file) (.setExecutable file true))
      (str file))))

(defn rewrite-css
  "The CSS rewritten by the program for the targets (a browserslist
   query): nesting flattened, prefixes added, not minified. A comment
   /*! … */ is kept, others dropped. Stops, with lightningcss's own words,
   when it fails."
  [program targets text]
  (let [process (.start (ProcessBuilder. ^java.util.List [program "--targets" targets]))
        writing (future (with-open [in (.getOutputStream process)]
                          (.write in (.getBytes ^String text "UTF-8"))))
        errors  (future (slurp (.getErrorStream process) :encoding "UTF-8"))
        out     (let [buffer (ByteArrayOutputStream.)]
                  (io/copy (.getInputStream process) buffer)
                  (.toString buffer "UTF-8"))
        exit    (.waitFor process)]
    ;; its own words first: a program that failed may not have read all it was given
    (when-not (zero? exit)
      (throw (ex-info (str "lightningcss failed (" exit "): " (str/trim @errors))
                      {:type :browser-build/lightningcss-failed :exit exit :errors @errors})))
    @writing
    out))
