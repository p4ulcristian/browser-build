(ns groundwork-browser-build.modules-check.main
  "The main module of a build with a module loaded on demand: its own
   stylesheet, and the admin module loaded with its stylesheet first."
  (:require [groundwork-browser-build.api :as styles]))

(def ^:private main-styles (styles/use-stylesheet "groundwork_browser_build/modules_check/main.css"))

(defn start! []
  (-> (styles/load-module! {"admin" "/css/admin.css"} "admin")
      (.then #(js/console.log "admin loaded" main-styles))))
