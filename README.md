# showroom

What the browser shows, built, for a Clojure app with ClojureScript in the
browser. **The build:** shadow-cljs, given a build showroom writes as data;
the app gives only its entry, the function the page runs. In development it
is watched in the server's own JVM: compiled now, again on every save, the
new code swapped into open tabs. For the deploy it is built once: hashed
names, a manifest, a gzipped copy of each file. **The stylesheets:** a
namespace names each stylesheet it needs, as a query names its `.sql` file;
the build gathers them into one bundle, in the order the namespaces are
found, and checks that none beside the code is left unnamed.

```clojure
io.github.p4ulcristian/showroom {:git/tag "v0.1.0" :git/sha "36a51a9"}
```

Needs only shadow-cljs, and only to build: take showroom in the aliases you
build with, never in `:deps`, so production runs without either. One public
namespace, `showroom.api`, with a `.clj` half (the build, the macro) and a
`.cljs` half (for the browser's code to require); its part is
`showroom.stylesheets`.

## The api

| name | does |
|---|---|
| `start-watching!` `stop-watching!` | development: watch the build in this JVM; code and a saved stylesheet are swapped into open pages; the page loads `/js/main.js` and `/css/main.css` |
| `build-for-release!` | the deploy: `js/main.<hash>.js`, `css/main.<hash>.css`, `manifest.edn` for each, and `file.gz` beside each; stops on a stylesheet no namespace names |
| `make-build` | the shadow-cljs build as data: one module, `main`, files in `<files-folder>/js`, served at `/js` |
| `use-stylesheet` | a macro, in the browser's code: names a stylesheet by its classpath path; a missing file stops the build at that line |

```clojure
;; deps.edn: the build aliases, never :deps
:browser-build   {:extra-deps {io.github.p4ulcristian/showroom {:git/tag "v0.1.0" :git/sha "36a51a9"}}}
:browser-release {:extra-deps {io.github.p4ulcristian/showroom {:git/tag "v0.1.0" :git/sha "36a51a9"}}
                  :exec-fn    showroom.api/build-for-release!}

;; the app's server, in development only: loaded when it watches
(def watching ((requiring-resolve 'showroom.api/start-watching!)
               {:entry 'app.browser.main/start! :files-folder "target/public"}))

;; the deploy, before the app starts:
;;   clojure -X:browser-release :entry app.browser.main/start! :files-folder '"target/public"'

;; the browser's code, src/app/browser/orders/order_card.cljs beside order_card.css
(ns app.browser.orders.order-card
  (:require [showroom.api :as styles]))

(def order-card-styles (styles/use-stylesheet "app/browser/orders/order_card.css"))

(defn order-card [order]
  [:div {:class "order-card"} (:order/number order)])
```

The page shell lists what the manifests name (`js/manifest.edn`,
`css/manifest.edn`), so the page and the build can't disagree; hashed files
can be cached for ever.

## Tests

```sh
clojure -X:test     # release-builds small entries: the bundle, a missing and an unnamed stylesheet
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
