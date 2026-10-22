(ns showroom.api-test
  "Release builds of small entries: the code compiles, optimized, into one
   file with a hashed name and its manifest; the named stylesheet becomes
   the bundle; a missing or an unnamed stylesheet stops the build."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [showroom.api :as showroom])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(deftest the-names-of-the-api
  (is (= '#{make-build start-watching! stop-watching! build-for-release! use-stylesheet}
         (set (keys (ns-publics 'showroom.api))))))

(defn- make-folder [] (str (Files/createTempDirectory "showroom-build" (make-array FileAttribute 0))))

(deftest a-release-build
  (let [folder (make-folder)]
    (with-out-str (showroom/build-for-release! {:entry 'showroom.build-check/start! :files-folder folder}))
    (let [[{:keys [module-id output-name]}] (edn/read-string (slurp (io/file folder "js" "manifest.edn")))]
      (is (= :main module-id))
      (is (re-matches #"main\.[0-9A-F]{8,}\.js" output-name) "the name carries a hash of the content")
      (is (.exists (io/file folder "js" output-name)))
      (is (.exists (io/file folder "js" (str output-name ".gz"))) "and its gzipped copy"))
    (let [[{:keys [output-name]}] (edn/read-string (slurp (io/file folder "css" "manifest.edn")))]
      (is (re-matches #"main\.[0-9A-F]{16}\.css" output-name))
      (is (= "/* showroom/build_check.css */\n.build-check { color: rebeccapurple; }\n"
             (slurp (io/file folder "css" output-name)))
          "the named stylesheet, whole, as written")
      (is (.exists (io/file folder "css" (str output-name ".gz")))))))

(deftest a-missing-stylesheet-stops-the-build
  (is (thrown? Exception
               (with-out-str (showroom/build-for-release! {:entry 'showroom.missing-check.entry/start! :files-folder (make-folder)})))))

(deftest a-stylesheet-no-namespace-names-stops-a-release
  (let [message (try (with-out-str (showroom/build-for-release! {:entry 'showroom.unnamed-check.entry/start! :files-folder (make-folder)}))
                     nil
                     (catch Exception e (pr-str e)))]
    (is (re-find #"forgotten\.css" (str message)))
    (is (not (re-find #"named\.css\n" (str message))))))
