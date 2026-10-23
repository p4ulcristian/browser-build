(ns groundwork-browser-build.modules-check.admin
  "A module loaded on demand, with a stylesheet of its own."
  (:require [groundwork-browser-build.api :as styles]))

(def ^:private admin-styles (styles/use-stylesheet "groundwork_browser_build/modules_check/admin.css"))

(js/console.log "admin-module-here" admin-styles)

(defn start!
  "The module's :start, run once its code has loaded."
  []
  (js/console.log "admin-start-here"))
