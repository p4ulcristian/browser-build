# Stylesheets are rewritten for the app's browsers by a pinned lightningcss program

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, G15: IR supports Safari 16.4 and writes 120 nesting lines and 73 `backdrop-filter`s). Builds on [Built files are gzipped, not minified; no node in the build](2026-10-22-built-files-are-gzipped-not-minified.md), which named "lightningcss as a downloaded program" as the way to add it. Not built yet.

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
