# Stylesheets are rewritten for the app's browsers by a pinned lightningcss program

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, G15: IR supports Safari 16.4 and writes 120 nesting lines and 73 `backdrop-filter`s). Builds on [Built files are gzipped, not minified; no node in the build](2026-10-22-built-files-are-gzipped-not-minified.md), which named "lightningcss as a downloaded program" as the way to add it. Built on tidy-up, not released: browser-build; the composer's part comes later.

**Decision:** a build writes out its CSS step, and with it the browsers the app supports:

```clojure
:css {:targets      "chrome >= 119, safari >= 16.4, firefox >= 128"
      :lightningcss {:version "1.x.y" :sha256 {"linux-x64" "…" "darwin-arm64" "…"}}}   ; or :css nil: files joined as written
```

The bundle (and a watched rebundle) goes through lightningcss for those targets: nesting flattened, `-webkit-` prefixes added, nothing minified. The program is downloaded once per platform, checked against its sha256, and kept in the build's cache folder; a wrong checksum or a missing platform stops the build, named. No node.

**Why:** the app keeps writing modern CSS, and the browsers it promises get CSS they read; the targets are said once, in the build.

**The price:** one more program in the build, pinned and checksummed per platform.

**Instead of:**
- **raising Iron Rainbow's floor to Safari 18**: nothing to build, but older iPhones and iPads get a broken look
- **writing CSS for the floor by hand, with a CI check**: everyone remembers the prefixes forever
- **lightningcss from npm**: node in the build, which the gzip decision ruled out

## Settled while building

Choices made while building, not asked:

1. **The program comes from npm's registry, read on the JVM:** lightningcss publishes no program on its GitHub releases, so browser-build fetches the tarball npm itself would, `https://registry.npmjs.org/lightningcss-cli-<platform>/-/lightningcss-cli-<platform>-<version>.tgz`, and takes `package/lightningcss` out of it; no node, no npm. The address is browser-build's, not a setting.
2. **The sha256 is the program's own, not the tarball's,** and it is checked every time the program runs, not only when downloaded: a cached program changed on disk stops the build too.
3. **Five platforms, by browser-build's names:** `linux-x64` and `linux-arm64` (glibc builds), `darwin-x64`, `darwin-arm64`, `windows-x64`; any other key is refused with the options. A machine whose platform the pins don't name stops the build before anything is downloaded.
4. **The program is kept at `<cache-folder>/lightningcss/<version>/<platform>/`,** written to a temporary file there and moved into place once checked, so a build stopped halfway leaves no half program; a checked program is made runnable if it isn't (one copied in by hand).
5. **The whole bundle goes through one run,** `lightningcss --targets <targets>`, the text on stdin; a failure (a targets query it can't read, say) stops the build with lightningcss's own words, `:browser-build/lightningcss-failed`. While watching, a rebundle that fails is written to the app's log, `:browser-build/bundle-failed`, as before.
6. **Each file's comment is `/*! path */`,** with `:css nil` too: lightningcss drops plain comments and keeps these, so the bundle still names where each rule came from.
7. **The release's hashed name is the hash of the rewritten text,** what is written.
8. **No download timeout:** the decision names no setting for one; a download that hangs holds the build until it is stopped.
9. **The tests pin lightningcss 1.33.0** and download it, so they need the network.

## Changed 2026-10-23 (the user's decisions, after the build)

- **The native download stays.** Weighed and turned down: lightningcss as one WebAssembly file run in Java (keeps nesting, same output, but ~8 s per cold build and a .wasm rebuilt for every release), no transformer with only a check (developers lose nesting until the floor moves), raising Iron Rainbow's floor to Safari 18.
- **browser-build pins lightningcss**, as it pins shadow-cljs: the version and each platform's sha256 live in browser-build, and an app writes only `:css {:targets "…"}` (or `:css nil`). Upgrading lightningcss is a browser-build release.
- **The download has a time limit**, a required setting.
- **browser-build checks the targets itself** before calling lightningcss, which aborts (dumps core) on a browser name it doesn't know; an unknown name stops the build, named.
