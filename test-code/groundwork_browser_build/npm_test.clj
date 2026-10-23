(ns groundwork-browser-build.npm-test
  "The npm checks, over a build's resolved sources and npm's record as
   data: a package installed only for development is named, one installed
   as the app's own passes, a nested one is found where it is; and a
   package nobody installed is told again with what to do."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-browser-build.npm :as npm]))

(defn- make-build-state [resources]
  {:build-sources (vec (range (count resources)))
   :sources       (into {} (map-indexed (fn [i r] [i (assoc r :type :shadow-js)])) resources)})

(def ^:private installed
  {""                                            {"name" "app"}
   "node_modules/react"                          {"version" "19.3.1"}
   "node_modules/react-dom"                      {"version" "19.3.1"}
   "node_modules/react-dom/node_modules/scheduler" {"version" "0.27.0"}
   "node_modules/process"                        {"version" "0.11.10" "dev" true}
   "node_modules/left-pad"                       {"version" "1.3.0" "extraneous" true}})

(deftest a-package-installed-only-for-development-is-named
  (let [state (make-build-state [{:package-name "react" :resource-name "node_modules/react/index.js"}
                                 {:package-name "scheduler" :resource-name "node_modules/react-dom/node_modules/scheduler/index.js"}
                                 {:package-name "process" :resource-name "node_modules/process/browser.js"}
                                 {:package-name "left-pad" :resource-name "node_modules/left-pad/index.js"}])]
    (is (= [{:package "left-pad" :installed-as "node_modules/left-pad"}
            {:package "process" :installed-as "node_modules/process"}]
           (npm/find-development-only-packages state installed))
        "react and a package react-dom brings pass; dev and extraneous ones don't"))
  (testing "a package with a dot in its name, which shadow-cljs's resource names munge, is found by its file"
    (is (= [] (npm/find-development-only-packages
               (make-build-state [{:package-name "big.js" :resource-name "node_modules/big_DOT_js/big.js"
                                   :file (java.io.File. "node_modules/big.js/big.js")}])
               {"node_modules/big.js" {"version" "7.0.1"}}))))
  (testing "the app's own packages pass"
    (is (= [] (npm/find-development-only-packages
               (make-build-state [{:package-name "react" :resource-name "node_modules/react/cjs/react.production.js"}])
               installed)))))

(deftest a-package-nobody-installed-is-told-again
  (let [thrown (ex-info "outer" {} (ex-info "The required JS dependency \"process\" is not available"
                                            {:tag :shadow.build.resolve/missing-js :require "process"
                                             :require-from "node_modules/react/cjs/react.production.js"}))
        told   (npm/tell-missing-package-again thrown)]
    (is (= {:type :groundwork-browser-build/npm-package-missing :package "process" :required-from "node_modules/react/cjs/react.production.js"}
           (ex-data told)))
    (is (re-find #"package\.json's \"dependencies\" \(not \"devDependencies\"" (ex-message told))))
  (is (= "react" (:package (ex-data (npm/tell-missing-package-again
                                      (ex-info "x" {:tag :shadow.build.resolve/missing-js :require "react/jsx-runtime" :require-from "a.js"})))))
      "a file of a package names the package")
  (let [other (ex-info "something else" {})]
    (is (identical? other (npm/tell-missing-package-again other)))))

(deftest the-refusal-is-told-plainly
  (let [refusal (ex-info "The build needs npm packages that are installed only for development"
                         {:type :groundwork-browser-build/npm-package-only-for-development :packages ["process"]})]
    (is (identical? refusal (npm/tell-npm-problem-plainly (ex-info "Hook [1 groundwork-browser-build.npm/check-npm-packages] failed" {} refusal)))
        "not shadow-cljs's wrapping")))
