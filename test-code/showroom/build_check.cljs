(ns showroom.build-check
  "An entry that names a stylesheet through showroom.api, so the test
   compiles the browser half as an app's release build would."
  (:require [showroom.api :as styles]))

(def ^:private build-check-styles (styles/use-stylesheet "showroom/build_check.css"))

(defn start! []
  (js/console.log "build check" build-check-styles))
