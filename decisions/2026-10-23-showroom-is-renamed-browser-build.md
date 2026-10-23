# showroom is renamed browser-build

**Decision:** the library is named browser-build: the repository is github.com/p4ulcristian/browser-build (the old address forwards), the namespaces are `browser-build.*` (`browser-build.api` the public one, its files under `source-code/browser_build/`), and every name it gives is renamed with it:

- the errors: `:browser-build/bad-options`, `:browser-build/bad-path`, `:browser-build/missing-file`, `:browser-build/unnamed-stylesheets`, `:browser-build/npm-not-installed`, `:browser-build/npm-package-missing`, `:browser-build/npm-package-only-for-development`;
- the log events: `:browser-build/unnamed-stylesheets`, `:browser-build/bundle-failed`, and their data's `:browser-build/stylesheets`;
- the shadow-cljs build id, `:browser-build`.

The decisions written before keep their text and their file names, showroom in them; this one says the name changed.

**Why:** the name says what the library does. It builds what the browser loads: the app's ClojureScript, compiled by shadow-cljs (watched in development, built once for a release), and the stylesheets its namespaces name, bundled. showroom read as a library that shows things, a UI kit; it shows nothing. The decisions already called it "the browser build", and an app's alias for it was already `:browser-build`.

**The price:** every app and library that uses it changes its requires, its keywords and its deps.edn at once. It is the one name in the family that is not a place in a house. The old tags stay on the renamed repository; a deps.edn naming `io.github.p4ulcristian/showroom` still resolves through GitHub's forward until it moves.

**Instead of:**
- **workshop, kiln, darkroom, press, loom**: house words that fit the family, each a picture to translate instead of a name that says it
- **build**: a build of what; jars and images are builds too
- **cljs-build**: leaves out the stylesheets
- **bundler**: names one step and suggests webpack or esbuild; it also watches and swaps code into open pages
- **frontend-build**: frontend is vaguer than browser
- **keeping showroom**: it keeps saying UI kit
