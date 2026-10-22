(ns showroom.modules-check.admin
  "A module loaded on demand, with a stylesheet of its own."
  (:require [showroom.api :as styles]))

(def ^:private admin-styles (styles/use-stylesheet "showroom/modules_check/admin.css"))

(js/console.log "admin-module-here" admin-styles)
