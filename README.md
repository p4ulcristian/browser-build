# showroom

What the browser shows, built, for a Clojure app with ClojureScript in the
browser. **The build:** shadow-cljs, given a build showroom writes as data;
the app gives its entry, the function the page runs, and every option. In development it
is watched in the server's own JVM: compiled now, again on every save, the
new code swapped into open tabs. For the deploy it is built once: hashed
names, a manifest, a gzipped copy of each file. **The stylesheets:** a
namespace names each stylesheet it needs, as a query names its `.sql` file;
the build gathers them into one bundle, in the order the namespaces are
found, and checks that none beside the code is left unnamed.

```clojure
io.github.p4ulcristian/showroom {:git/tag "v0.1.1" :git/sha "82b9ae9"}
```

Needs only shadow-cljs and malli, and only to build: take showroom in the aliases you
build with, never in `:deps`, so production runs without either. One public
namespace, `showroom.api`, with a `.clj` half (the build, the macro) and a
`.cljs` half (for the browser's code to require); its part is
`showroom.stylesheets`.

## The api

| name | does |
|---|---|
| `start-watching!` `stop-watching!` | development: watch the build in this JVM; code and a saved stylesheet are swapped into open pages; the page loads `/js/main.js` and `/css/main.css` |
| `build-for-release!` | the deploy: `js/main.<hash>.js`, `css/main.<hash>.css`, `manifest.edn` for each, and `file.gz` beside each; stops on a stylesheet no namespace names |
| `make-build` | the shadow-cljs build as data, `:watch` or `:release`: one module, `main`, files in `<files-folder>/js`, served at `/js` |
| `use-stylesheet` | a macro, in the browser's code: names a stylesheet by its classpath path; a missing file stops the build at that line |

```clojure
;; deps.edn: the build aliases, never :deps
:browser-build   {:extra-deps {io.github.p4ulcristian/showroom {:git/tag "v0.1.1" :git/sha "82b9ae9"}}}
:browser-release {:extra-deps {io.github.p4ulcristian/showroom {:git/tag "v0.1.1" :git/sha "82b9ae9"}}
                  :exec-fn    showroom.api/build-for-release!
                  :exec-args  {:entry              app.browser.main/start!
                               :files-folder       "target/public"
                               :cache-folder       ".shadow-cljs"   ; shadow-cljs's cache
                               :compile-threads    4
                               :output-feature-set :es2020          ; what the browsers must understand
                               :source-maps?       false            ; a .map beside each file, readable by anyone
                               :gzip-level         9}}              ; the .gz copies, 1 (fast) to 9 (smallest)

;; the app's server, in development only: loaded when it watches
(def watching ((requiring-resolve 'showroom.api/start-watching!)
               {:entry                     'app.browser.main/start!
                :files-folder              "target/public"
                :cache-folder              ".shadow-cljs"
                :compile-threads           4
                :output-feature-set        :es2020
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
  (:require [showroom.api :as styles]))

(def order-card-styles (styles/use-stylesheet "app/browser/orders/order_card.css"))

(defn order-card [order]
  [:div {:class "order-card"} (:order/number order)])
```

Every option is required: a missing or unknown one is refused, named, with
every other problem at once, before anything is built. shadow-cljs reads no
`shadow-cljs.edn`: showroom hands it its whole configuration. What stays
fixed, and why, is in
[the decision](decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md).

The page shell lists what the manifests name (`js/manifest.edn`,
`css/manifest.edn`), so the page and the build can't disagree; hashed files
can be cached for ever.

## Tests

```sh
clojure -X:test     # release-builds small entries (the bundle, a missing and an unnamed stylesheet),
                    # the refusals, and one real watch: compiled, a saved stylesheet and namespace
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
