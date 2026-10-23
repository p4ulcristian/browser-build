(ns browser-build.modules-check.admin
  "A module loaded on demand, with a stylesheet of its own."
  (:require [browser-build.api :as styles]))

(def ^:private admin-styles (styles/use-stylesheet "browser_build/modules_check/admin.css"))

(js/console.log "admin-module-here" admin-styles)
