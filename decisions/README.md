# showroom decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

All but the last two were made while showroom was two libraries inside Groundwork, a Clojure framework (groundwork.server.browser-build and groundwork.browser.styles, and Groundwork's styles before them); they name its parts (its settings, its page shell, `start-app!`) where they came from. The ones they name that stayed with Groundwork: "Groundwork starts the browser and owns the build", which the build builds on, and "Groundwork is frozen", whose freeze the styles lifted.

## In force

- [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md), changed in part by [A def names its stylesheet](2026-10-22-a-def-names-its-stylesheet-as-a-query-names-its-sql.md) and [Built files are gzipped, not minified](2026-10-22-built-files-are-gzipped-not-minified.md)
- [The browser build is Groundwork's: watched in development, built once by the deploy](2026-10-22-the-browser-build-is-groundworks.md), changed in part by [showroom is its own library](2026-10-22-showroom-is-its-own-library.md) and [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [Built files are gzipped, not minified; no node in the build](2026-10-22-built-files-are-gzipped-not-minified.md), changed in part by [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [A def names its stylesheet, as a query names its .sql file](2026-10-22-a-def-names-its-stylesheet-as-a-query-names-its-sql.md), changed in part by [showroom is its own library](2026-10-22-showroom-is-its-own-library.md)
- [showroom is its own library](2026-10-22-showroom-is-its-own-library.md), changed in part by [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)

## Replaced

- [The styles tree's order is cascade layers](2026-10-13-the-styles-trees-order-is-cascade-layers.md), replaced by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md)
- [The styles tree](2026-10-15-the-styles-tree.md), replaced by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md)
- [A page loads the stylesheets its code uses](2026-10-15-a-page-loads-the-stylesheets-its-code-uses.md), replaced by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md)
- [A stylesheet paints only its own room](2026-10-15-a-stylesheet-paints-only-its-own-room.md), replaced by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md)
- [A class is a namespaced keyword](2026-10-15-a-class-is-a-namespaced-keyword.md), replaced by [A namespace names its stylesheets](2026-10-17-a-namespace-names-its-stylesheets.md)
