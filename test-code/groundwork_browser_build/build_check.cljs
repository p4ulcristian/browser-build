(ns groundwork-browser-build.build-check
  "An entry that names a stylesheet through groundwork-browser-build.api, so the test
   compiles the browser half as an app's release build would."
  (:require [groundwork-browser-build.api :as styles]))

(def ^:private build-check-styles (styles/use-stylesheet "groundwork_browser_build/build_check.css"))

(defn start! []
  (js/console.log "build check" build-check-styles))
