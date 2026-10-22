(ns showroom.watch-check.entry
  "The entry the watching test compiles, saves and compiles again. The
   test writes this file and its stylesheet back as they are after."
  (:require [showroom.api :as styles]))

(def ^:private watch-check-styles (styles/use-stylesheet "showroom/watch_check/entry.css"))

(defn start! [] (js/console.log "watch-check-one" watch-check-styles))
