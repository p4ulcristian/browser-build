# browser-build

What the browser shows, built, for a Clojure app with ClojureScript in the
browser. **The build:** shadow-cljs, given a build browser-build writes as data;
the app gives its entry, the function the page runs, and every option. In development it
is watched in the server's own JVM: compiled now, again on every save, the
new code swapped into open tabs. For the deploy it is built once: hashed
names, a manifest, a gzipped copy of each file. **The stylesheets:** a
namespace names each stylesheet it needs, as a query names its `.sql` file;
the build gathers them into one bundle, in the order the namespaces are
found, and checks that none beside the code is left unnamed. **The CSS
step:** with `:css`, each bundle is rewritten by the lightningcss program browser-build pins
program for the browsers the app supports (nesting flattened, `-webkit-`
prefixes added, nothing minified); with `:css nil`, it is written as the
files are.

```clojure
io.github.p4ulcristian/browser-build {:git/tag "v0.4.3" :git/sha "9efddf6"}
```

Needs only shadow-cljs, malli and jsonista (and, with `:css`, the
lightningcss program it downloads itself; no node), and only to build: take browser-build in the aliases you
build with, never in `:deps`, so production runs without either. One public
namespace, `browser-build.api`, with a `.clj` half (the build, the macro) and a
`.cljs` half (for the browser's code to require); its part is
`browser-build.stylesheets`.

## The api

| name | does |
|---|---|
| `start-watching!` `stop-watching!` | development: watch the build in this JVM; code and a saved stylesheet are swapped into open pages; the page loads `/js/main.js` and `/css/main.css`; browser-build's messages go to the app's `:log` |
| `build-for-release!` | the deploy: `js/main.<hash>.js`, `css/main.<hash>.css`, `manifest.edn` for each, and `file.gz` beside each; stops on a stylesheet no namespace names, and, before compiling, on an npm package it needs that isn't installed as one of package.json's `dependencies` (not `devDependencies`) |
| `make-build` | the shadow-cljs build as data, `:watch` or `:release`: the module `main` and the `:modules` loaded on demand, each with its `:start` run once its code has loaded ([why](decisions/2026-10-23-a-module-names-its-start.md)), files in `<files-folder>/js`, served at `/js`; each module's stylesheet bundle in `<files-folder>/css` |
| `load-module!` | in the browser: a module of `:modules`, its stylesheets linked first, then its code, as script tags; a promise |
| `use-stylesheet` | a macro, in the browser's code: names a stylesheet by its classpath path; a missing file stops the build at that line |

```clojure
;; deps.edn: the build aliases, never :deps
:browser-build   {:extra-deps {io.github.p4ulcristian/browser-build {:git/tag "v0.4.3" :git/sha "9efddf6"}}}
:browser-release {:extra-deps {io.github.p4ulcristian/browser-build {:git/tag "v0.4.3" :git/sha "9efddf6"}}
                  :exec-fn    browser-build.api/build-for-release!
                  :exec-args  {:entry              app.browser.main/start!
                               :modules            {}               ; or {:admin {:entries [app.admin.page] :depends-on #{:main} :start app.admin.page/start!}}, :start nil for none
                               :files-folder       "target/public"
                               :cache-folder       ".shadow-cljs"   ; shadow-cljs's cache
                               :compile-threads    4
                               :output-feature-set :es2020          ; what the browsers must understand
                               :resolve            {}               ; npm modules replaced: {"process" {:target :file :file "src/process_shim.js"}}
                               :closure-defines    {}               ; goog-defines: {malli.registry/type "custom"}
                               :warnings-as-errors #{}              ; warnings that stop the build: #{:infer-warning}
                               :silenced-warnings  #{}              ; warnings not shown: #{:unreachable-code}
                               :css                nil              ; or the CSS step, below
                               :lightningcss-download-timeout-ms 60000   ; the pinned program's download, once per platform
                               :source-maps?       false            ; a .map beside each file, readable by anyone
                               :gzip-level         9}}              ; the .gz copies, 1 (fast) to 9 (smallest)

;; the app's server, in development only: loaded when it watches
(def watching ((requiring-resolve 'browser-build.api/start-watching!)
               {:log                       log             ; the app's (logbook's): an unnamed stylesheet, a failed rebundle
                :entry                     'app.browser.main/start!
                :modules                   {}
                :files-folder              "target/public"
                :cache-folder              ".shadow-cljs"
                :compile-threads           4
                :output-feature-set        :es2020
                :resolve                   {}
                :closure-defines           {}
                :warnings-as-errors        #{}
                :silenced-warnings         #{}
                :css                       nil             ; or the CSS step, below
                :lightningcss-download-timeout-ms 60000
                :preloads                  []              ; development only: namespaces loaded before the entry
                :devtools-host             "localhost"     ; where open pages connect for new code; it can run code in this JVM
                :devtools-port             9630            ; a port in use is refused; 0 picks a free one
                :devtools-url              nil             ; nil: the page's own host and that port
                :nrepl                     nil             ; or {:host "localhost" :port 0}
                :socket-repl               nil
                :hot-reload?               true            ; saved code swapped into open pages
                :reload-strategy           :optimized      ; or :full, every namespace
                :hud                       #{:errors :warnings :progress}
                :stylesheet-check-every-ms 300}))

;; the deploy, before the app starts:
;;   clojure -X:browser-release

;; the browser's code, src/app/browser/orders/order_card.cljs beside order_card.css
(ns app.browser.orders.order-card
  (:require [browser-build.api :as styles]))

(def order-card-styles (styles/use-stylesheet "app/browser/orders/order_card.css"))

(defn order-card [order]
  [:div {:class "order-card"} (:order/number order)])
```

The CSS step, for both builds, the app's browsers only:

```clojure
:css {:targets "chrome >= 119, safari >= 16.4, firefox >= 128"}
```

`:targets` is `<browser> <op> <version>` queries (op `>=` `>` `<=` `<`)
joined by commas, each browser one lightningcss knows (browserslist's
names and aliases, `browser-build.lightningcss/browsers`, any case).
browser-build reads them itself before lightningcss runs, which aborts
on a query it can't read: anything else (a browser it doesn't know, an
exact version, a browserslist keyword such as `defaults`) is refused
with the options, named. The program is browser-build's own pin,
lightningcss 1.33.0 and its sha256 on each platform (`linux-x64`
`linux-arm64` `darwin-x64` `darwin-arm64` `windows-x64`), as it pins
shadow-cljs: upgrading lightningcss is a browser-build release. It comes
once per platform from npm's registry (the `lightningcss-cli-<platform>`
tarball, read on the JVM), within `:lightningcss-download-timeout-ms`, is
kept in the cache folder, `<cache-folder>/lightningcss/<version>/<platform>/`,
and is checked when downloaded and every time it runs; a platform the
pin doesn't name (`:browser-build/lightningcss-no-platform`), a download
past its time (`:browser-build/lightningcss-download-timed-out`), a
checksum that differs (`:browser-build/lightningcss-checksum`) or
lightningcss's own refusal (`:browser-build/lightningcss-failed`) stops
the build, named. Each file
in a bundle follows a `/*! path */` comment naming it, which lightningcss
keeps ([why](decisions/2026-10-23-stylesheets-are-rewritten-for-the-apps-browsers-by-a-pinned-lightningcss.md)).

browser-build prints nothing: while watching, a stylesheet no namespace names
(`:browser-build/unnamed-stylesheets`) and a failed rebundle
(`:browser-build/bundle-failed`) go to the app's log, `(log level event data)` as
[logbook](https://github.com/p4ulcristian/logbook) makes it, under `:log`. A
release build takes none: it stops on what a watch warns about
([why](decisions/2026-10-22-the-app-passes-its-log.md)).

Every option is required: a missing or unknown one is refused, named, with
every other problem at once, before anything is built. shadow-cljs reads no
`shadow-cljs.edn`: browser-build hands it its whole configuration. What stays
fixed, and why, is in
[the decision](decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).

The page shell lists what the manifests name (`js/manifest.edn`,
`css/manifest.edn`), so the page and the build can't disagree; hashed files
can be cached for ever.

## Tests

```sh
clojure -X:test     # release-builds small entries (the bundle, a missing and an unnamed stylesheet,
                    # the CSS step), the refusals, and one real watch: compiled, a saved stylesheet
                    # and namespace; downloads lightningcss 1.33.0 from npm's registry, so needs the network
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
