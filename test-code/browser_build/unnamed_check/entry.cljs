(ns browser-build.unnamed-check.entry
  "Names one of the two stylesheets beside it: a release build must stop
   on the other."
  (:require [browser-build.api :as styles]))

(def ^:private named (styles/use-stylesheet "browser_build/unnamed_check/named.css"))

(defn start! [] named)
