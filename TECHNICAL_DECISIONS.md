# Technical decisions and feasibility assessment

This file records the technical assessment made before implementation (master prompt §20, items
3, 4 and 11) and every significant decision since. Each decision states the reason and what would
make us revisit it.

Last updated: 2026-11-18

---

## Part A — Feasibility assessment

### A1. Repository analysis (§20 item 1)

`wartamimi/build-it` was empty when work started (no commits). There is no prior implementation,
no existing code to preserve, and no constraints from earlier code. Everything in this repository
was written from scratch for Build It. Build It is an original product: its name, visual identity,
architecture, project format and block language are its own and are not derived from Sketchware
Pro's code, assets or file formats.

### A2. Toolchain facts used for version selection (checked 2026-11-18)

| Component | Version chosen | Source / reason |
|---|---|---|
| Android Gradle Plugin | 9.5.1 | Latest stable. Requires Gradle ≥ 9.6.0, JDK 17, Build Tools 36.0.0; supports API ≤ 37. |
| Gradle | 9.6.0 | AGP 9.5 minimum and default. |
| JDK (build host) | 17+ (CI uses 17, dev host 21) | AGP requirement. Bytecode target 17. |
| Kotlin | 2.4.21 | Latest stable. AGP 9 built-in Kotlin support is used (no separate kotlin-android plugin). |
| KSP | 2.3.14 | KSP2 (decoupled from Kotlin version), used by Room. |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | AndroidX 2026 releases require compileSdk 37. minSdk 26 gives adaptive icons, java.nio.file and keeps ~97% of active devices. |
| Compose BOM | 2026.09.00 | Latest stable BOM. |
| Room | 2.8.5 | Latest stable. |

Versions are pinned in `gradle/libs.versions.toml`. They are never mixed ad hoc; upgrades go
through the catalog and this table.

### A3. What can and cannot run inside an Android app (§20 items 3 and 4)

The goal "build APK and AAB locally on the device, not in the cloud" is achievable, but **not by
running the desktop Gradle + AGP stack as-is inside a normal app**. Component by component:

| Component | Runs inside an Android app? | Notes |
|---|---|---|
| **javac** | Not as shipped. | `jdk.compiler` is not part of ART's class library. Patched ports exist but are large and fragile. |
| **ECJ** (Eclipse Compiler for Java) | **Yes**, in-process on ART. | Pure Java, battle-tested by several on-device IDEs. Supports Java 17 source. Used for Java compilation. |
| **Kotlin compiler (K2)** | **Uncertain — needs a spike.** | `kotlin-compiler-embeddable` relies on IntelliJ core and some JDK APIs that ART lacks or implements differently. Older (K1) compilers have been run on ART with patches. Phase 6 starts with a time-boxed spike; fallback below. |
| **D8** (dexer) | **Yes**, in-process. | Pure Java library (from the R8 artifact). |
| **R8** (shrinker/optimizer) | **Yes, with memory limits.** | Pure Java but needs 1–3 GB heap for an app with AndroidX. Runs in a separate `:build` process with `largeHeap`; on low-RAM devices Build It reports this and offers a non-minified release build instead of crashing. |
| **AAPT2** | **Only as a native binary built for Android.** | The Maven `aapt2` artifact contains Linux/macOS/Windows x86 binaries only. *Superseded by D13: Build It compiles resources itself in Kotlin and does not ship aapt2.* |
| **apksig** (APK signing v1–v4) | **Yes**, in-process. | Pure Java (Apache 2.0). |
| **bundletool** (AAB) | **Yes**, in-process. | Pure Java. Needs resources in protobuf format (aapt2 `--proto-format`); with D13 that means a protobuf writer in the resource compiler (not done yet). |
| **Android SDK platform (`android.jar`)** | **Yes**, as a downloaded file. | Needed as the compile classpath. Downloaded from Google after the user accepts the Android SDK License in-app; never bundled. |
| **Maven dependency resolution** | **Yes**, own implementation. | POM parsing, parent/BOM import, scopes, AAR extraction, "highest version wins" conflict policy, offline cache. Gradle Module Metadata variants (e.g. Kotlin Multiplatform-only libraries) are reported as unsupported in the first version. |
| **Gradle + AGP** | **Possible, but heavy and fragile; optional mode only.** | Needs an Android build of OpenJDK 17+ (no official one; community builds exist), executed from `nativeLibraryDir` because Android 10+ forbids executing files from app-writable storage (W^X). ~1 GB+ of toolchain and caches, 2 GB+ RAM for the daemon, first build needs network. Distribution through Google Play is a policy risk ("downloading executable code"). |
| **NDK / C++ builds** | **No (out of scope).** | clang/lld for on-device use are not something Build It can ship responsibly. Projects with native code are reported as unsupported. |
| **Step debugger (breakpoints) for the built app** | **No, not without ADB.** | JDWP needs ADB. See alternative below. |

#### Chosen approach: two build modes

1. **Direct toolchain (default, Phase 6).** Build It runs its own pipeline in a dedicated build
   process: validation → dependency resolution → source generation → ECJ (Java) / Kotlin compiler
   → AAPT2 compile+link → D8 (or R8 for release) → APK zip-align and packaging (or bundletool for
   AAB) → apksig signing → verification → report. This covers projects created in Build It and
   ordinary single-module Android apps with Maven dependencies. It is what makes "build on the
   phone" fast and reliable.
2. **Gradle compatibility mode (optional, Phase 6b, experimental).** For projects that need real
   Gradle plugins, Build It can install an Android build of OpenJDK + Gradle via the Toolchain
   Manager and run Gradle in a separate process with an AAPT2 override. Clearly labelled
   experimental, gated on available RAM/storage, and **not** offered in a Play Store build.

When a project needs something neither mode supports (NDK, unsupported Gradle plugin, KMP-only
dependency), the build stops at validation with a plain explanation of what is unsupported and
why, and the option to export the project to build in Android Studio. There is never a simulated
or "pretend" build: success is reported only when a verified artifact exists.

#### Kotlin fallback

If the Kotlin compiler cannot run reliably on ART: Java projects remain fully supported in direct
mode, Kotlin projects build in Gradle compatibility mode (the compiler then runs on the on-device
JDK, where it is supported), and block code generation defaults to Java. This will be decided by
the Phase 6 spike and recorded here.

### A4. Other features that need a different approach on Android

| Requested feature | Problem | Alternative adopted |
|---|---|---|
| Debugger | No JDWP/ADB from an app. | Build It injects an optional runtime log/crash bridge into **debug** builds that reports logs, uncaught exceptions and lifecycle events back to Build It's Debug Console (via a content provider). Advanced: wireless-debugging ADB pairing (Android 11+) in a later phase. |
| Install & run built app | Silent install is not allowed. | `PackageInstaller` session with the system confirmation prompt (`REQUEST_INSTALL_PACKAGES`). |
| Live preview from XML | `LayoutInflater` only inflates *compiled* XML. | Preview builds real Android `View`s directly from the parsed layout model (native rendering). Device size/density/orientation/night mode are simulated with `createConfigurationContext`. Third-party custom views that are not compiled yet show a labelled placeholder, never a fake rendering. |
| Bidirectional blocks ↔ code | Arbitrary code → blocks is not decidable. | Generated code lives in marked regions with recorded hashes (`GeneratedFiles`). If the user edits a generated region, Build It detects it, stops regenerating that region, and offers: keep code (blocks for that event become read-only), convert the region into a *custom code block*, or overwrite after an explicit confirmation. User code is never overwritten silently. |
| AI assistant | Secrets on device. | User supplies their own provider/endpoint/key. Keys are encrypted with the Android Keystore, never logged, never written into projects. AI output is a structured proposal shown as a reviewable diff; nothing is applied without confirmation. |
| Firebase blocks | Needs the `google-services` Gradle plugin. | Direct mode reproduces the plugin's documented transformation (`google-services.json` → `values.xml`) itself; SDKs come from Google Maven via the resolver. |
| Code editor on Compose | Large-file editing with highlighting is costly to build from scratch. | Editor behind a `CodeEditor` abstraction. Phase 5 evaluates a mature View-based editor (e.g. Sora Editor, LGPL-2.1) wrapped in `AndroidView` against a Compose implementation; the choice and its license are recorded here. |

---

## Part B — Architecture decisions

### D1. Engines are pure Kotlin/JVM modules
*Decision:* project format, layout model, block compiler and build pipeline live in `engine:*`
modules with no Android dependency.
*Why:* they run unchanged on ART, are unit-tested on the JVM in milliseconds, and keep the
compiler pipeline independent of any UI (§7 "keep the renderer separate from the compiler").

### D2. Project files are the source of truth; the database is an index
*Decision:* each project is a folder with `buildit.json` and a standard Android source layout.
Room stores only an index (name, package, timestamps) for fast listing.
*Why:* no data is lost if the database is cleared; projects open in Android Studio; recovery after
a crash is a directory scan (`ProjectRepository.reindex`, run at every app start).

### D3. Atomic writes and staged operations
*Decision:* every metadata write is temp-file + fsync + rename. Create, duplicate, import and
delete work in hidden staging directories that are moved into place only when complete; leftovers
are cleaned at startup.
*Why:* §6 "prevent accidental data loss", §15 "recovery after unexpected termination".

### D4. Versioned project format with JSON-level migrations
*Decision:* `formatVersion` in `buildit.json`; migrations transform raw JSON one version at a time;
the pre-migration manifest is backed up; newer-than-supported formats are refused with an
explanation. See `docs/PROJECT_FORMAT.md`.

### D5. Generated files are hash-tracked
*Decision:* `GeneratedFiles` records the hash of everything Build It generates. A regeneration that
would overwrite a hand-edited file is refused and surfaced to the user.
*Why:* §9 "never silently overwrite user-written code". The same mechanism will guard layout XML
and code generated from blocks.

### D6. Layout XML is the designer's source of truth
*Decision (for Phase 3):* the designer parses `res/layout/*.xml` into a typed `LayoutDocument`,
edits that model, and serializes back with stable formatting; unknown elements and attributes are
preserved verbatim. The preview is always rendered from the same parsed document.
*Why:* one source of truth means preview and XML cannot drift (§8), and projects stay compatible
with standard Android tooling.

### D7. No DI framework, no Hilt
*Decision:* a small manual `AppContainer`. ViewModels get dependencies through
`viewModelFactory`.
*Why:* the object graph is small; avoids annotation processing cost and startup overhead (§3 "avoid
heavyweight frameworks").

### D8. Navigation shows only implemented destinations
*Decision:* top-level navigation currently has Home, Projects, Settings; the project workspace
has Project and Files tabs. Design, Blocks, Code, Resources, Manifest, Build, AI and Debug appear
when their phase is implemented.
*Why:* §2/§18 forbid placeholder screens and buttons that do nothing.

### D9. Material 3 with an original Build It identity
*Decision:* Material 3 components, Build It palette ("Blueprint" blue primary, "Kiln" orange for
build/run, graphite neutrals), system fonts, optional dynamic color on Android 12+.

### D10. Per-app language via AppCompat
*Decision:* English and Indonesian string resources in every module; language switching uses
`AppCompatDelegate.setApplicationLocales` with `generateLocaleConfig`, so it also appears in the
system per-app language settings on Android 13+.

### D11. Build tooling for Build It itself
*Decision:* convention plugins in `build-logic/` (`buildit.android.application`, `.library`,
`.compose`, `.feature`, `buildit.jvm.library`), version catalog, configuration cache on.
CI (GitHub Actions) runs unit tests and compiles every module. Per the owner's instruction the
APK is **not** built or published until the final phase.

### D12. Code editor on the platform text stack, not a third-party editor
*Context:* the code editor needs highlighting, line numbers, undo/redo, find/replace and good IME
behaviour (Indonesian and other keyboards, autocorrect off, hardware keyboards). Embedding a
third-party editor component (e.g. Sora Editor, LGPL) adds licence obligations for a closed APK
distribution and a large surface we cannot fix ourselves.
*Decision:* `feature:code` uses a subclass of the platform `EditText` (`CodeEditText`), so the
platform owns IME, selection handles, accessibility and copy/paste. Everything else lives in the
pure-JVM `engine:editor` module and is unit tested: lexers (Java, Kotlin, Groovy Gradle, XML,
JSON), search (case/whole word/regex) and replacement, syntax checks (brackets, unterminated
strings/comments, XML well-formedness, JSON validity), and the edit history (coalesced typing,
auto-indent recorded as part of the newline). Highlighting is applied only to the visible lines
plus a margin, debounced, so typing in long files stays responsive.
*Consequences:* files larger than 2 MB are not opened (the read-only viewer is used); there is no
code completion yet. The editor sits behind its own module, so it can be replaced by a custom
text view (piece table, incremental layout) later without touching the view model or the engine.
*Safety:* saving compares the file on disk with the text that was loaded; if it changed (for
example, the designer or blocks regenerated it), the editor reports a conflict and lets the user
overwrite or reload instead of silently overwriting either side.


### D13. Resources compiled in Kotlin instead of a native aapt2
*Context:* the plan (Part A) was to ship aapt2 built from AOSP for Android ABIs. Building aapt2 for
Android needs the AOSP tree and a native toolchain in CI, and shipping third-party prebuilt
binaries means trusting their provenance. Native executables are also the part most exposed to
W^X and Play policy changes.
*Decision:* `engine:build` contains its own resource compiler written in Kotlin
(`engine/build/.../res`): it reads the platform's attributes from android.jar's resources.arsc,
compiles `res/` and the manifest into Android binary XML and resources.arsc (UTF-8 string pools,
dense type chunks, 64-byte configurations), assigns ids and writes R.java. It covers what Build It
projects use (values of all common kinds, styles, arrays, plurals, XML files, bitmaps copied as
they are, common qualifiers) and reports everything else as an error with file and line.
*Verification:* tests run the SDK's own `aapt2 dump badging|xmltree|resources` on the output and
check attribute ids, typed values, launchable activity and labels; CI installs the SDK so these
tests cannot be skipped there.
*Consequences:* no native code to build or ship; identical behaviour on the JVM (tests) and on
ART. Not covered yet: `declare-styleable`/`attr`/`public` (needed for libraries), styled strings,
nine-patch processing, PNG crunching, resource shrinking, and the protobuf format needed for AAB.

### D14. In-process ECJ, D8 and apksig; platforms downloaded from Google
*Decision:* Java is compiled by ECJ 3.46 in-process. ECJ normally looks for a JDK module image
(which ART does not have) for Java 9+; Build It overrides that lookup so android.jar is the system
library, which allows Java 17 source. Lambda/string-concat bootstrap classes missing from
android.jar are provided by signature-only stubs compiled from source on first use (the role of
AGP's core-lambda-stubs.jar); they are never packaged. D8 (R8 9.5) converts to DEX with Java 8+
language desugaring; apksig signs (v1 when minSdk < 24, v2, v3) and verifies the APK like the
platform. android.jar is downloaded from `dl.google.com/android/repository` using the official
index, only after the user accepts the Android SDK License shown in the app, and only installed if
its SHA-1 matches. The debug key is a per-device PKCS#12 keystore created on first build.
*Consequences:* the app's APK grows noticeably (the R8 jar alone is 21 MB before dexing; to be measured when the APK is first built in Phase 8). Builds run in the app process for now
(`largeHeap`); a separate build process is planned with Phase 8 performance work.

### D15. AI assistant: user's own provider, whole-file proposals, applied only on confirmation
*Decision:* The user brings their own provider. Claude is called through Anthropic's official Java
SDK (`com.anthropic:anthropic-java`, streamed; default model `claude-opus-5-5`). Any other
provider is reached through the OpenAI chat-completions protocol at a URL the user enters (for
example a local model); plain `http://` is only accepted for localhost and private networks. The
API key is encrypted with an AES-GCM key held in the Android Keystore; only ciphertext is stored,
in app-private preferences excluded from backup. It is never logged, never in project files and
never part of the settings JSON. The model sees only the files the user attaches (plus, if ticked,
the last build's problems). File changes come back as complete files between `<<<FILE path="…">>>`
and `<<<END>>>` markers; they are parsed, diffed locally (Myers line diff) against the file on
disk, and shown per file with Apply and Discard. Apply writes only if the file still has exactly
the content the diff was made against; otherwise it reports "changed since" and writes nothing.
Paths outside the project, Build It metadata, build output and replies cut off mid-file are
refused. An open editor tab reloads an applied file, or shows a conflict if it has unsaved edits.
*Why whole files instead of patches:* models produce complete files more reliably than exact
patches, and the local diff makes the review identical either way.
*Consequences:* long files cost more output tokens; the SDK adds Jackson and OkHttp to the APK
(size measured in Phase 8). Nothing runs without a key, and no request is made in tests: the
clients are tested against a local HTTP server that speaks the providers' streaming formats.

### D16. Debug console through a bridge injected into debug builds
*Decision:* Debug builds made on the device get one generated ContentProvider class
(`dev.buildit.debugbridge.BuildItDebugBridge`) and the manifest entries it needs (`<provider>`,
`<queries>` for Build It). It starts with the app's process and forwards the app's own logcat
lines (`logcat --pid`, which apps may read without permissions), activity lifecycle events and
uncaught exceptions with stack traces, in batches, to Build It's exported insert-only console
provider. Build It identifies the caller by its UID through the system and accepts entries only
from apps signed with this device's Build It debug key. The user's sources are never changed,
release builds never contain the bridge, and if Build It is not installed the bridge does nothing.
*Consequences:* entries are kept in memory (newest 5,000 per app), not on disk. Logs from before
the first bridge start (e.g. a crash in `Application` static init) may be missed. Behaviour on ART
is verified with the emulator job in Phase 8; the JVM tests check the compiled APK with aapt2 and
dexdump and the provider with Robolectric.

### D17. ECJ made to run on Android by patching two Java-only API uses
*Decision:* ECJ 3.46 references `javax.lang.model.SourceVersion` and `java.lang.Runtime.Version`,
neither of which exists on Android. The app ships a small `javax.lang.model.SourceVersion` enum,
and a Gradle task (`patchEcj`) rewrites ECJ's class files so `java/lang/Runtime$Version` becomes
`dev/buildit/compat/RtVer1` (same name length, so the constant pool is patched byte for byte),
a Kotlin re-implementation of the parts ECJ uses. The original jar is unchanged for JVM tests.
*Consequences:* upgrading ECJ needs the emulator job to confirm no new Java-only API is used.
Verified on ART by the on-device build test.

### D18. Libraries: Maven resolution and a non-namespaced merge in the on-device builder
*Decision:* Projects list their libraries in `buildit.json` (`app.dependencies`); the Library
Manager edits that list and Build It writes it to `app/build.gradle.kts`. The build resolves them
like Gradle for an Android app (`engine:deps`): Gradle module metadata first (Android variant,
Kotlin Multiplatform redirects, dependency constraints), POM otherwise (parents, properties, BOM
imports), highest version wins. Files come from Google Maven and Maven Central into one cache
shared by all projects. Resources of all libraries and the app are compiled together into the
app package (library first, app overrides), as AGP does without namespacing. The app's R holds
every id; each library package gets an R whose nested classes extend the app's, so library code
(compiled against non-final R fields) finds the ids by field resolution through the superclass.
Library manifests are merged by element key with `tools:node="remove"` and `${applicationId}`.
Each library JAR is dexed once and cached; the app's classes are dexed on every build.
`google-services.json` is turned into string resources as the Google Services plugin does.
*Consequences:* not supported: annotation processors, Kotlin-only/Compose APIs (projects are
Java), Gradle plugins other than google-services, manifest placeholders other than
`${applicationId}`, and R8 shrinking of the user's APK. Library resources are recompiled on every
build (seconds on a phone for Material). Verified on ART by the emulator test.

## D19 — Sketchware-style workflow, own identity; components in the block file

*Decision:* the workspace is organised the way Sketchware Pro users expect (Design / Events /
Components, screen picker and Run at the bottom, tools in a drawer), but with Build It's own
names, icons, colours and block shapes. Non-visual components are stored in the screen's block
document (`components`), so they move, undo and save with the blocks; each type has blocks and an
event, lowered to IR and printed by the Java and Kotlin backends like other blocks. Images, sounds
and fonts are plain files in res/drawable, res/raw and res/font (the project stays a normal
Android project); import refuses to replace a resource of the same name.
*Consequences:* old block files load unchanged (the field defaults to empty). Sound blocks name a
res/raw file and are checked against the folder. Component kinds need code in the engine; there
is no plugin system for them.

---

## D20 — Template blocks and procedures with parameters

*Decision:* most new blocks are declared with a Java and a Kotlin code template (`{input}` and
`{field}` placeholders) instead of a dedicated IR node and printer per block. Lowering converts
inputs (text inputs to text), records the imports and helper methods a block needs, and the
backends substitute. Blocks with structure (for each) keep dedicated IR. Procedure parameters live
in the `params` field of the define block (`name:type,...`); a call's inputs are derived from the
procedure it calls (`BlockDocument.defOf`), and changing parameters moves orphaned argument blocks
out to loose stacks rather than deleting them.

*Why:* the Sketchware catalog is large; templates let it grow quickly without per-block code in
three places. Correctness is guarded by CatalogTest, which places every block of the registry in
one document and compiles the generated Java (javac) and Kotlin (kotlinc) against android.jar.

*Trade-off:* templates are text, so a bad template is only caught by that test, not by the type
system; value templates must be single atomic expressions.

---

## D21 — AppCompat switch, generated themes.xml and the collection

*Decision:* the Library Manager's AppCompat switch is a project setting (`app.appCompat`). Turning
it on adds androidx.appcompat and Material Components (downloaded and checked before anything
changes), generated screens extend AppCompatActivity, and the app theme comes from Material
Components. To make that possible, `res/values/themes.xml` is now a generated file like the
manifest: it holds `AppTheme` plus the `AppTheme.NoActionBar` / `AppTheme.Fullscreen` styles used by
the per-screen window option. The themes.xml of older projects is adopted only if it is still the
original default; a hand-edited one is left alone, reported, and the manifest falls back to
framework themes. Screens whose source is still exactly Build It's template switch base class;
any other screen is only reported, never rewritten.

The collection (blocks, widgets, images, sounds) lives in the app's own storage, outside every
project, as plain files: blocks as JSON (the block plus the variables it uses), widgets as a
standalone layout XML fragment. Placing them always assigns fresh block ids and view ids.

*Why:* AppCompatActivity crashes without an AppCompat theme, so the switch has to own the theme;
keeping the user's edits safe follows the same rule as every other generated file (D5).

## Part C — Risks and mitigations (§20 item 11)

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Kotlin compiler does not run on ART | Medium | High for Kotlin projects | Spike still open (no ART runtime available in the development environment; planned with the emulator CI job in Phase 8). Until then Kotlin projects stop at the Check step with a clear message; block codegen defaults to Java. |
| Resource compiler fidelity (D13 replaces aapt2) | Medium | High | Output checked by the SDK's own aapt2 (`dump badging/xmltree/resources`), dexdump and apksigner in tests on every CI run; unsupported inputs fail with a clear message instead of being skipped. |
| R8/D8 memory on low-end devices | High | Medium | Separate build process, `largeHeap`, memory-class check before build, D8-only fallback, clear messaging. |
| Google Play policy on downloading toolchains | Medium | High for distribution | Direct-mode tools ship inside the APK (no downloaded executable code); Gradle mode only in non-Play builds. |
| Scope: the full vision is many months of work | Certain | High | Strict phases with acceptance criteria; nothing marked complete without tests; DEVELOPMENT_STATUS.md kept honest. |
| Dependency resolution edge cases | High | Medium | Start with Maven POM semantics; report unsupported metadata clearly; offline cache; tests against real AndroidX POMs. |
| Block editor performance with large workspaces | Medium | Medium | Canvas renderer with viewport culling, cached block geometry, incremental layout; benchmark with 2,000+ blocks. |
| Code editor performance on large files | Medium | Medium | Editor abstraction; piece-table text model; incremental highlighting. |
| AI change overwrites newer user edits | Medium | High | Changes apply only on tap and only if the file is byte-for-byte what the diff was made against; files changed after sending are flagged; editors with unsaved edits show a conflict (D15). |
| API key leakage | Low | High | Keystore-encrypted, excluded from backup, never logged or written into projects; sent only to the endpoint the user configured; http only for local networks (D15). |
| Console provider abused by other apps | Low | Low | Insert-only, caller identified by UID and accepted only when signed with this device's debug key (D16). |
| Data loss from bugs in file operations | Low | Very high | Atomic writes, staging dirs, refusal to touch paths outside the workspace, backups before migrations and restores, tests for each. |
