(ns browser-build.watch-check.entry
  "The entry the watching test compiles, saves and compiles again. The
   test writes this file and its stylesheet back as they are after."
  (:require [browser-build.api :as styles]))

(def ^:private watch-check-styles (styles/use-stylesheet "browser_build/watch_check/entry.css"))

(defn start! [] (js/console.log "watch-check-one" watch-check-styles))
