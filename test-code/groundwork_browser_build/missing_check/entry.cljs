(ns groundwork-browser-build.missing-check.entry
  "Names a stylesheet that isn't there: the build must stop."
  (:require [groundwork-browser-build.api :as styles]))

(def ^:private nowhere (styles/use-stylesheet "groundwork_browser_build/missing_check/nowhere.css"))

(defn start! [] nowhere)
