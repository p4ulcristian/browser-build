(ns showroom.missing-check.entry
  "Names a stylesheet that isn't there: the build must stop."
  (:require [showroom.api :as styles]))

(def ^:private nowhere (styles/use-stylesheet "showroom/missing_check/nowhere.css"))

(defn start! [] nowhere)
