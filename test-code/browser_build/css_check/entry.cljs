(ns browser-build.css-check.entry
  "A release build whose stylesheet is written for today's browsers:
   nesting and an unprefixed backdrop-filter, which the CSS step rewrites
   for Safari 16.4."
  (:require [browser-build.api :as styles]))

(def ^:private card-styles (styles/use-stylesheet "browser_build/css_check/entry.css"))

(defn start! [] (js/console.log "css-check" card-styles))
