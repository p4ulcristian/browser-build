(ns browser-build.settings
  "browser-build's options, as malli shapes, checked before anything is built.
   Nothing has a default: every key is written out (nil where it is a
   choice), and a missing or unknown one refuses, named, with every other
   problem at once. Both maps are closed."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def output-feature-sets
  "What the compiled code may use, in shadow-cljs's words, oldest first.
   :es-unsupported leaves the code as it is written (shadow-cljs's own
   choice when none is given)."
  [:es5 :es2017 :es2018 :es2019 :es2020 :es2021
   :browser-2020 :browser-2021 :browser-2022 :browser-2023 :browser-2024 :browser-2025
   :es-next :es-unsupported])

(def modules-shape
  "The modules loaded on demand, besides main, by name, every key written
   out: each its :entries, the namespaces it holds; :depends-on, the
   modules it needs first (main among them, or another of these); and
   :start, the function run once when its code has loaded, before the
   page that loaded it is drawn (shadow-cljs's :init-fn), or nil for none.
   {} for none."
  [:map-of [:and :keyword [:fn {:error/message "main is the entry's own module"} #(not= :main %)]]
   [:map {:closed true}
    [:entries [:vector {:min 1} :symbol]]
    [:depends-on [:set {:min 1} :keyword]]
    [:start [:maybe :qualified-symbol]]]])

(def resolve-shape
  "npm modules the build replaces, by name, {} for none: with a file
   ({:target :file :file \"src/process_shim.js\"}), a global the page
   already has ({:target :global :global \"THREE\"}), another npm package
   ({:target :npm :require \"preact/compat\"}), or nothing (false)."
  [:map-of :string
   [:or
    [:= false]
    [:map {:closed true} [:target [:= :file]] [:file :string]]
    [:map {:closed true} [:target [:= :global]] [:global :string]]
    [:map {:closed true} [:target [:= :npm]] [:require :string]]]])

(def ^:private build-entries
  [[:entry :qualified-symbol]
   [:modules modules-shape]
   [:files-folder :string]
   [:cache-folder :string]
   [:compile-threads [:int {:min 1}]]
   [:output-feature-set (into [:enum] output-feature-sets)]
   [:resolve resolve-shape]
   [:closure-defines [:map-of :symbol [:or :string :boolean number?]]]
   [:warnings-as-errors [:set :keyword]]
   [:silenced-warnings [:set :keyword]]])

(def ^:private repl-server-shape
  [:maybe [:map {:closed true}
           [:host :string]
           [:port [:int {:min 0}]]]])

(def watch-options-shape
  "start-watching!'s options, every key required.

   The build:
   :entry: the function the page runs, app.browser.main/start!, in the
   module main, which the page loads.
   :modules: the modules the page loads on demand (browser-build.api's
   load-module!), {} for none: {:admin {:entries [app.admin.page]
   :depends-on #{:main} :start app.admin.page/start!}}, :start nil for a
   module with no start.
   :files-folder: where the files go, the folder the app's server serves at
   /: <files-folder>/js and <files-folder>/css.
   :cache-folder: shadow-cljs's cache (and its port files), \".shadow-cljs\".
   :compile-threads: how many namespaces compile at once.
   :output-feature-set: what the compiled code may use (output-feature-sets).
   :resolve: npm modules replaced (resolve-shape), {} for none.
   :closure-defines: goog-define values the build is compiled with,
   {malli.registry/type \"custom\"}, {} for none.
   :warnings-as-errors: the compiler warnings that stop the build,
   #{:infer-warning}, #{} for none.
   :silenced-warnings: the compiler warnings not shown at all,
   #{:unreachable-code}, #{} for none; none of them may also stop the
   build.
   :preloads: namespaces loaded before the entry, while watching only
   (development tools: a debugger's), [] for none; a release build has
   none.

   shadow-cljs's server, which open pages connect to for new code:
   :devtools-host: the address it listens on, \"localhost\" (\"0.0.0.0\"
   lets other machines connect, and run code in the JVM through it).
   :devtools-port: its port; a port in use is refused, not moved; 0 picks
   a free one.
   :devtools-url: nil, and a page connects to http://<its own host>:<the
   port>; or the URL it connects to (behind a proxy).
   :nrepl, :socket-repl: nil for none, or {:host :port} (port 0 picks a
   free one) for shadow-cljs's nREPL and socket REPL.

   Hot reload:
   :hot-reload?: a saved namespace's new code is swapped into open pages.
   :reload-strategy: :optimized reloads the changed namespaces and those
   that use them; :full reloads every namespace.
   :hud: what open pages show over themselves while compiling, a set of
   :errors :warnings :progress, #{} for nothing.
   :stylesheet-check-every-ms: how often the named stylesheets are checked
   for a save, which rebundles them and swaps the bundle into open pages.

   :log: the app's log function, (log level event data), made by logbook:
   a stylesheet no namespace names (:browser-build/unnamed-stylesheets) and a
   rebundle that failed (:browser-build/bundle-failed) are written to it."
  (into [:map {:closed true}]
        (concat build-entries
                [[:log [fn? {:error/message "should be the app's log function, made by logbook"}]]
                 [:preloads [:vector :symbol]]
                 [:devtools-host :string]
                 [:devtools-port [:int {:min 0}]]
                 [:devtools-url [:maybe :string]]
                 [:nrepl repl-server-shape]
                 [:socket-repl repl-server-shape]
                 [:hot-reload? :boolean]
                 [:reload-strategy [:enum :optimized :full]]
                 [:hud [:set [:enum :errors :warnings :progress]]]
                 [:stylesheet-check-every-ms [:int {:min 1}]]])))

(def release-options-shape
  "build-for-release!'s options, every key required: the build's
   (:entry :modules :files-folder :cache-folder :compile-threads
   :output-feature-set :resolve :closure-defines :warnings-as-errors
   :silenced-warnings, as start-watching! takes them; no :preloads, which
   are for development), and
   :source-maps?: a .map beside each file, so the browser shows the
   ClojureScript a failure came from (anyone can read it);
   :gzip-level: how hard the .gz copies are squeezed, 1 (fast) to 9
   (smallest).

   No :log: a release build writes no line of browser-build's (a stylesheet no
   namespace names stops it), and its options come from clojure -X's
   :exec-args, where a function can't be written."
  (into [:map {:closed true}]
        (concat build-entries
                [[:source-maps? :boolean]
                 [:gzip-level [:int {:min 1 :max 9}]]])))

(defn check-data-for-shape
  "Returns the data; throws {:type kind :errors …} with every problem at
   once when it doesn't fit the shape."
  [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

(defn- find-warnings-both-silenced-and-stopping
  "A warning can't be silenced and stop the build: {:silenced-warnings
   [\"…\"]}, or nil."
  [{:keys [warnings-as-errors silenced-warnings]}]
  (let [both (sort (filter (set warnings-as-errors) silenced-warnings))]
    (when (seq both)
      {:silenced-warnings [(str "silenced and among :warnings-as-errors: " (vec both))]})))

(defn- check-build-options [shape options]
  (check-data-for-shape "The options" :browser-build/bad-options shape options)
  (when-let [errors (find-warnings-both-silenced-and-stopping options)]
    (throw (ex-info (str "The options have problems: " (pr-str errors))
                    {:type :browser-build/bad-options :errors errors})))
  options)

(defn check-watch-options [options]
  (check-build-options watch-options-shape options))

(defn check-release-options [options]
  (check-build-options release-options-shape options))
