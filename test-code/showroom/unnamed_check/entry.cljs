(ns showroom.unnamed-check.entry
  "Names one of the two stylesheets beside it: a release build must stop
   on the other."
  (:require [showroom.api :as styles]))

(def ^:private named (styles/use-stylesheet "showroom/unnamed_check/named.css"))

(defn start! [] named)
