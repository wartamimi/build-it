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
| **AAPT2** | **Only as a native binary built for Android.** | The Maven `aapt2` artifact contains Linux/macOS/Windows x86 binaries only. Build It must ship `aapt2` compiled for `arm64-v8a` and `x86_64` Android (AOSP sources, Apache 2.0) packaged as `libaapt2.so` in `jniLibs` so it lands in the executable `nativeLibraryDir`. |
| **apksig** (APK signing v1–v4) | **Yes**, in-process. | Pure Java (Apache 2.0). |
| **bundletool** (AAB) | **Yes**, in-process. | Pure Java. Needs AAPT2 `--proto-format` output; same pipeline otherwise. |
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

---

## Part C — Risks and mitigations (§20 item 11)

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Kotlin compiler does not run on ART | Medium | High for Kotlin projects | Time-boxed spike first in Phase 6; Java-first block codegen; Gradle mode fallback. |
| AAPT2 Android binaries (build + license + updates) | Medium | High | Build from AOSP in CI for arm64-v8a/x86_64, pin to Build Tools 36, verify with golden-file tests; document provenance. |
| R8/D8 memory on low-end devices | High | Medium | Separate build process, `largeHeap`, memory-class check before build, D8-only fallback, clear messaging. |
| Google Play policy on downloading toolchains | Medium | High for distribution | Direct-mode tools ship inside the APK (no downloaded executable code); Gradle mode only in non-Play builds. |
| Scope: the full vision is many months of work | Certain | High | Strict phases with acceptance criteria; nothing marked complete without tests; DEVELOPMENT_STATUS.md kept honest. |
| Dependency resolution edge cases | High | Medium | Start with Maven POM semantics; report unsupported metadata clearly; offline cache; tests against real AndroidX POMs. |
| Block editor performance with large workspaces | Medium | Medium | Canvas renderer with viewport culling, cached block geometry, incremental layout; benchmark with 2,000+ blocks. |
| Code editor performance on large files | Medium | Medium | Editor abstraction; piece-table text model; incremental highlighting. |
| Data loss from bugs in file operations | Low | Very high | Atomic writes, staging dirs, refusal to touch paths outside the workspace, backups before migrations and restores, tests for each. |
