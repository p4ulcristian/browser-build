# A build names the warnings it silences, and a watch its preloads

> The lean of Iron Rainbow's port (GROUNDWORK-GAPS.md, G16), mechanical: built without a separate ask, 2026-10-23. Adds to [A build names its module replacements, its defines and the warnings that stop it](2026-10-23-a-build-names-its-module-replacements-defines-and-warnings-that-stop-it.md), which said a new need is a new named setting. Built on tidy-up, not released: browser-build; the composer's part comes later.

**What was wrong:** Iron Rainbow's build turns one compiler warning off, `:warnings {:unreachable-code false}`, and loads a debugger's namespace before its entry in development only, `:preloads`. browser-build had neither.

**Decision:** two named settings, each required, written out empty where none is meant:

```clojure
{:silenced-warnings #{:unreachable-code}     ; both builds; #{} for none
 :preloads          [app.dev.blackbox]}      ; start-watching! only; [] for none
```

- `:silenced-warnings` is shadow-cljs's `:compiler-options :warnings`, each named warning `false`.
- `:preloads` is shadow-cljs's `:devtools :preloads`: loaded before the entry, while watching. A release build takes none; the key is refused there.

**Why:** each says one thing a person can read and check, and the build's options still say what a build is.

**The price:** two more keys in every build's options.

**Instead of:**
- **Iron Rainbow fixing the code that gives the warning**: works only if the warning comes from its own code, which wasn't checked
- **a `:compiler-options` pass-through**: unchecked, ruled out by the decision this adds to

## Settled while building

Choices made while building, not asked:

1. **A warning silenced and among `:warnings-as-errors` is refused,** `:browser-build/bad-options`, naming it: one setting would undo the other.
2. **The warning names are shadow-cljs's own keywords,** checked as keywords, not against a list: the compiler's list changes with its version.
3. **`:preloads` are namespace names, as symbols,** in the order they load.
