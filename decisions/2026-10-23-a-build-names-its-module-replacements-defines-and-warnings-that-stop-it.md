# A build names its module replacements, its defines and the warnings that stop it; no pass-through

> The user's decision, 2026-10-23, for Iron Rainbow's move to Groundwork v1 (its build uses all three: a `process` shim three.js needs, malli's registry define, `:infer-warning` as an error). Built the same day.

**Decision:** three new build settings, each required, checked by malli, written out empty where none is meant:

```clojure
{:resolve             {"process" {:target :file :file "project/code/process_shim.js"}
                       "@maptiler/sdk" false}                     ; {} for none
 :closure-defines     {malli.registry/type "custom"}              ; {} for none
 :warnings-as-errors  #{:infer-warning}}                          ; #{} for none
```

- `:resolve` is shadow-cljs's `:js-options :resolve`: an npm module replaced by a file, a global, or nothing.
- `:closure-defines` are `goog-define` values the release build is compiled with.
- `:warnings-as-errors` names the compiler warnings that stop a build.

**Why:** each is a thing a person can read and check: what a module is replaced by, what a define is set to, what stops the build. Iron Rainbow needs each (three's `process.emitWarning`, ~34 KB gzipped off the bundle, two renaming bugs that shipped before the guard). A new need is a new named setting, decided when it comes.

**The price:** an option showroom doesn't name can't be used until it is added.

**Instead of:**
- a `:compiler-options` map passed through to shadow-cljs: unchecked, anything shadow accepts, and the build settings no longer say what a build is
- the app dropping them: three's warning call fails without the shim, the bundle grows, the renaming guard is gone
