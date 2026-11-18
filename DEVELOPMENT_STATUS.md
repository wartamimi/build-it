# Development status

Last updated: 2026-11-18

## Summary

Phases 0–5 and 7 are implemented, Phase 6 is partly done, and Phase 8 published the APK but found that the on-device Java compile does not run on Android yet (see Phase 8). The app shell, design system,
project management, the project workspace (settings + file explorer), the visual layout designer,
the block editor (with Java and Kotlin code generation), the code editor, the on-device build of
**Java** projects into signed, verified debug APKs, the AI assistant with reviewed changes and the
debug console work end to end and are covered by tests. Not yet: library dependencies, Kotlin
compilation, AAB and release signing UI in the build engine, and any run on a real device or
emulator (Phase 8). Per the owner's instruction, no APK is built or published until the final
phase; CI compiles the app and runs the tests.

## Phase 1 — Foundation: complete

Completed features
- Modular Gradle project with convention plugins and version catalog.
- Design system: Build It palette, light/dark/dynamic color, typography, spacing, shapes,
  elevation, buttons, top bar, cards, text fields, dialogs, bottom sheet, rows, loading/empty/error
  states, icon set, launcher icon.
- Navigation: Home, Projects, Settings (bottom bar on phones, rail on ≥600 dp), project workspace.
- Room database (project index) with exported schema.
- Home dashboard: new/import quick actions, recent projects, workspace storage usage.
- Settings: theme (system/light/dark), dynamic color (Android 12+), language (system/English/
  Indonesian, per-app locale), editor font size, auto-save preference, rescan workspace, about.
- English and Indonesian strings for every screen.

## Phase 2 — Project management: complete, with known gaps

Completed features
- Project format v1 (`buildit.json`), documented in `docs/PROJECT_FORMAT.md`, with migration
  framework and automatic backup of the pre-migration manifest.
- Create from templates (Empty Activity in Java or Kotlin) writing a real Android source tree plus
  Gradle files (opens in Android Studio).
- Open, rename, duplicate, delete (with confirmation), search and sort.
- ZIP export and import through the Storage Access Framework (zip-slip and size protection; an
  imported project gets a new id).
- Backups (ZIP inside the project, last 5 kept) and restore from a backup picker (restore first
  backs up the current state, so it can be undone).
- Startup recovery: interrupted operations are cleaned up and the index is rebuilt from disk.
- Project settings: app name, application ID, version code/name, min/target/compile SDK, with
  validation; saving regenerates `AndroidManifest.xml` and `app/build.gradle.kts`.
- Hand-edited generated files are detected (hash tracking) and never overwritten without asking.
- File explorer: browse, create file/folder, rename, delete, read-only preview; Build It's own
  metadata is protected.

Known gaps (planned)
- Multiple activities and multiple modules: the format supports them; UI to add activities comes
  with the designer (Phase 3), modules later.
- Only two templates (Empty Activity Java/Kotlin). More templates arrive with the designer and
  blocks so they can contain real layouts and logic.
- Projects live in the app's private workspace; editing projects in place in a user-selected
  folder is not supported (export/import via SAF is).

## Phase 3 — UI designer: complete, with known gaps

Completed features
- `engine:layout` (pure JVM): lossless XML parser/writer (comments, unknown elements and
  attributes preserved; stable attribute order), component catalog (24 components in 10
  categories with typed properties), edit commands (insert, move, remove, set attribute,
  duplicate) with undo/redo, document validation, drop-target resolution, resource value
  resolution (`@string`, `@color`, `@dimen`).
- Live preview of **real Android views** built from the document (no fake mock-ups) for phone,
  small phone, foldable and tablet profiles, portrait/landscape, light/night and zoom. Anything
  that can't be shown (custom views, runtime-only content, unsupported attributes) is reported in
  the Problems panel instead of being faked.
- Canvas: tap to select, long-press and drag to move a view, with the target container
  highlighted.
- Component library with search: tap to add next to the selection, or long-press and drag onto
  the canvas.
- Component tree with selection, move up/down, duplicate and delete.
- Properties inspector grouped by Identity/Layout/Spacing/Appearance/Text/Behavior with typed
  editors (id, size presets, enums, flags, booleans, colors with swatch) plus editing, adding and
  removing any other attribute.
- Problems list: duplicate/invalid ids, missing sizes, children in non-containers, too many
  children, missing contentDescription, misplaced layout_weight, plus preview issues.
- Layout files: switch layouts, create a layout with a chosen root, add a new activity (class,
  layout and manifest entry).
- Saving: autosave (800 ms after the last edit, respects the setting), manual save, save on
  leaving/backgrounding; if the file was changed elsewhere since it was opened the designer asks
  before overwriting. With autosave off, leaving asks to save or discard.
- Adaptive layout: three panes on wide screens (≥840 dp), canvas plus tabbed panel on phones.
- English and Indonesian strings.

Known gaps (planned)
- ConstraintLayout constraints are edited as attributes (`app:layout_constraint*`); there are no
  drag handles for constraints yet.
- No resource editor yet (strings/colors/dimens are read for the preview; editing them is planned
  with the code editor in Phase 5).
- The preview uses the library versions bundled in Build It (Material, ConstraintLayout,
  RecyclerView, CardView, DrawerLayout), not the exact versions the project declares. Components
  that need a library show a "Needs …" note; the dependency is not added automatically yet (the
  dependency manager comes with the build engine in Phase 6).
- Reordering in the tree uses move up/down buttons; dragging inside the tree is not supported.

## Phase 4 — Block engine and editor: complete, with known gaps

Completed features
- `engine:blocks` (pure JVM): block document per activity (`.buildit/blocks/<Activity>.json`,
  versioned), 46 block types in 10 categories (Events, Control, Logic, Math, Text, Variables,
  Lists of text, Components, App, Custom code), typed values (true/false, number, text, list of
  text) with implicit to-text conversion, editing operations (detach/insert/delete/duplicate,
  fields, variables with rename-everywhere) and whole-document undo/redo.
- Validation anchored to block ids: missing inputs, type mismatches, unknown/incompatible views,
  unknown variables/procedures/screens, duplicate events, loose blocks, invalid numbers/colors.
- Typed IR, then Java and Kotlin backends producing deterministic, readable activity classes
  (fields for views and variables, listener methods, lifecycle overrides, procedures, small
  helpers only when used). **Generated Java is compiled against the real `android.jar` in the
  tests.**
- Safe source sync: the untouched template activity is replaced on first generation; any hand
  edit afterwards is detected and the user chooses "Replace" or "Keep my code". Blocks with
  errors never overwrite the source.
- Block editor (Blocks tab): Canvas renderer with its own layout, culling of off-screen stacks,
  pan, pinch and button zoom, drag to move/connect with snapping (statements, C-mouths, value
  inputs with type checking), drop on the trash to delete, tap fields to edit (text, number,
  dropdowns, views of the layout filtered by widget family, variables, procedures, screens, code),
  selection actions (duplicate, enable/disable, delete), palette by category, variables panel,
  problems list (tap to jump), read-only generated-code preview, autosave/save with the same rules
  as the designer.
- Block labels and every editor string in English and Indonesian.

Known gaps (planned)
- Not yet available as blocks: Intent extras, Network, JSON, Database, Firebase, Files, Media.
  These need runtime libraries and dependency handling that arrive with the build engine (Phase 6)
  and are deliberately not shown until they generate working code.
- Procedures take no parameters and return no value; lists hold text only.
- No minimap, multi-select, block search or copy/paste between screens yet; blocks are added from
  the palette by tapping (not dragging).
- Kotlin output is verified by tests on the generated text; it is compiled for real once the build
  engine's Kotlin path exists (Phase 6).
- Turning hand-edited source back into blocks is not supported (by design, see
  TECHNICAL_DECISIONS A4); the user keeps their code or replaces it.
- Large-workspace performance has culling but has not been benchmarked with 2,000+ blocks yet.

## Phase 5 — Code editor: complete, with known gaps

Completed features
- `engine:editor` (pure JVM): lexers for Java, Kotlin, Gradle (Groovy and `.kts`), XML and JSON
  that never throw on broken input; search with match case, whole word and regex (with `$1`
  groups in replacements); syntax checks while typing (bracket balance, unterminated strings and
  comments, XML well-formedness via the designer's parser, JSON validity) with line/column;
  edit history with coalesced typing and auto-indent recorded as one step.
- Code tab: opening a text file from Files opens it in an editor tab (several files at once,
  dirty markers, close with save/discard). Platform `EditText`-based editor (TECHNICAL_DECISIONS
  D12) with a line-number gutter, highlighting of the visible lines, undo/redo (buttons and
  Ctrl+Z/Ctrl+Y), Tab = 4 spaces, auto-indent (one level deeper after `{`, `(` or `[`), font size from
  Settings, light/dark colours from the theme.
- Find and replace bar (next/previous, replace, replace all), go to line, problems list that jumps
  to the location.
- Saving follows the app rules: autosave after 1 s when enabled, flush when leaving or when the
  app stops; without autosave, leaving asks to save or discard. A file changed on disk since it was
  opened (e.g. regenerated by the designer or blocks) is a conflict: the user chooses Overwrite or
  Reload, nothing is overwritten silently. Generated files show a banner; hand edits are then
  protected by the existing Replace/Keep choice when blocks regenerate.
- Build It metadata (`buildit.json`, `.buildit/`) stays read-only.

Known gaps (planned)
- No code completion, go-to-definition or compiler diagnostics yet; the checks are syntactic. Real
  compiler errors come with the build engine (Phase 6).
- Files over 2 MB and binary files are not opened in the editor.
- Switching editor tabs recreates the view, so the scroll position is not kept per tab.
- "Replace all" is undone one replacement at a time.
- No split view, minimap or code folding.

## Phase 6 — Local build engine: partly done

Completed features
- Build tab in the workspace: shows whether the project's Android platform is installed; if not,
  downloads it from Google's official SDK repository after the user accepts the Android SDK
  License shown in the app, with progress and cancel, and installs it only if the SHA-1 matches
  the official index (TECHNICAL_DECISIONS D14).
- "Build debug APK" runs seven real steps with live status and timings: check project → compile
  resources and manifest → compile Java → convert to DEX → package → sign → verify signature.
- Resource compiler written in Kotlin (D13): values (strings with Android escaping rules, colors,
  dimensions, booleans, integers, fractions, ids, styles with parents, string/integer/typed arrays,
  plurals), XML files compiled to binary XML with attribute ids, enums and flags from the
  platform, references to app and public framework resources, common qualifiers (language/region,
  night, orientation, density, sw/w/h dp, version), manifest with package, version, uses-sdk,
  qualified class names and `debuggable` for debug builds; R.java and BuildConfig.java.
- Java 17 source compiled in process by ECJ against android.jar; D8 converts to DEX with Java 8+
  language desugaring; resources.arsc stored uncompressed and aligned; signed with apksig (v1 when
  minSdk < 24, v2, v3) using a per-device debug key, then verified like the platform does.
- Errors from every step carry the project file and line; tapping one opens the file at that line
  in the Code tab. Unsupported inputs (Kotlin sources, library dependencies, AndroidX/Material
  widgets, `app:` attributes, nine-patch images, `declare-styleable`) stop the build with a plain
  explanation instead of being skipped.
- The signed APK can be installed (system installer, through a FileProvider) or shared.
- Verified in tests with the SDK's own tools: `aapt2 dump badging/xmltree/resources`, `dexdump`
  and `apksigner verify` accept the APKs Build It produces.

Known gaps (planned)
- **Not yet run on a real device.** This environment has no Android runtime (no emulator/KVM);
  all build tests run on the JVM. ECJ, D8 and apksig are pure Java and used by other on-device
  IDEs, but the first on-device confirmation comes with the emulator CI job in Phase 8.
- Library dependencies (Maven/AAR resolution, AndroidX, Material) are not supported yet; neither
  are the AndroidX/Material components in the designer's palette when building on the device.
- Kotlin sources: not compiled on the device yet (Kotlin-on-ART spike still open, D14/risks).
- No AAB output; no release signing UI (the engine can sign with a PKCS#12 key); no R8
  shrinking; no incremental builds (every build is a full build, a few seconds for small apps).
- Java library APIs newer than minSdk (e.g. `List.of` below API 30) are not desugared; only
  language features are.
- Builds run in the app process; leaving the project cancels at the next step. A separate build
  process and foreground service come with Phase 8.
- Native libraries (`jniLibs`) are not packaged.

## Phase 7 — AI assistant and debug console: complete, with known gaps

Completed features
- AI tab with provider setup: Claude (Anthropic) through the official Java SDK, default model
  `claude-opus-5-5`, or any OpenAI-compatible endpoint (e.g. a local model). Model, endpoint and
  maximum reply length are editable; the API key is encrypted with the Android Keystore and kept
  out of project files and settings (TECHNICAL_DECISIONS D15).
- Chat that streams the reply as it arrives, with Stop. The user attaches project files (picker of
  the project's text files) and can include the last build's problems; the model sees nothing else.
- Every file the model wants to write is shown as a diff (added/removed lines with line numbers)
  with Apply, Discard and "Apply all". Applying writes only if the file is unchanged since the diff
  was made; files edited after they were sent, generated files, new files, and refused paths
  (outside the project, Build It metadata, build output, replies cut off mid-file) are labelled.
  Open Code tabs reload applied files, or show a conflict when they have unsaved edits.
- Errors are explained (invalid key, rate limit, overloaded, model not found, network, refusal,
  reply cut off by the length limit).
- Debug builds include a console bridge (D16): logs (`logcat --pid` of the app), activity
  lifecycle events and crashes with stack traces reach the Console tab. Release builds do not
  include it, and the project's files are not changed.
- Console tab: minimum level filter, search, follow, clear; crash cards whose stack frames open
  the source line in the Code tab when the class is in the project; "Ask AI about this crash"
  fills the AI composer with the stack trace and attaches the crashing file.
- The console provider is exported but insert-only and accepts entries only from apps signed with
  this device's Build It debug key (checked by UID through the package manager).

Known gaps
- **Nothing in this phase has run on a device yet** (no emulator here). The bridge APK is checked
  with aapt2 and dexdump, the provider with Robolectric, and the AI clients against a local server
  that replays each provider's streaming format; no request to a real provider is made in tests.
- The Keystore secret store is not unit-tested (Robolectric has no AndroidKeyStore); it is covered
  by the Phase 8 emulator job.
- Chat history is kept per open project in memory only; it is not saved across app restarts.
- The assistant cannot delete or rename files; it proposes full file contents only.
- Console entries live in memory (newest 5,000); logs from before the bridge starts (very early
  process start) can be missed.

## Phase 8 — Integration and release: on-device build working

Done
- Release APK (R8 shrinking, ~13.6 MB, signed with the Build It release key). CI builds a release
  APK after the unit tests pass and, with the signing secrets stored in the repository, publishes
  a signed GitHub release for every push to main (`v<version>-build.<run>`) or `v*` tag.
- Emulator job in CI (API 34) with instrumented tests. On ART these pass: building a Java project
  on the device (ECJ -> D8 -> apksig), installing it, launching it, and seeing its log and an
  intentional crash (with the source line) arrive through the debug console bridge; the Claude
  SDK streaming; API keys encrypted with the Keystore; a plain-http OpenAI-compatible server.
- Fixed from device testing:
  - ECJ 3.46 needs `javax.lang.model.SourceVersion` and `java.lang.Runtime.Version`, which Android
    lacks. A stand-in `SourceVersion` ships in the app, and the ECJ jar is patched at build time
    to use `dev.buildit.compat.RtVer1` instead of `Runtime$Version` (D17).
  - The Blocks tab crashed on open: Android's ICU regex rejects an unescaped `}`.
  - Plain http to local AI servers was blocked by Android's default network policy.
- UI test on the emulator: create a project, add a block, save, open every workspace tab.

Not done in this phase
- Kotlin-on-ART spike, separate build process, memory checks before builds, a formal security
  review. Library dependencies are Phase 9 (in progress).

## Phase 9 — Libraries and Library Manager (owner request)

Done
- `engine:deps`: resolver for Google Maven and Maven Central (module metadata, POM inheritance,
  BOMs, version ranges, dependency constraints, highest version wins), offline cache, search of
  Google's group index and Maven Central.
- On-device builder with libraries: AAR resources merged under the app's (attr,
  declare-styleable, macro, inline aapt:attr, res-auto attributes, nine-patch images, all
  qualifiers incl. BCP-47), R for app and library packages, merged manifests, library JARs on the
  compile classpath, per-JAR dex cache, Java resources, assets and native libraries packaged,
  `google-services.json` for Firebase.
- Library Manager tab: search, choose a version, add (everything is downloaded and checked
  before the project changes), turn on/off, change version, remove, list of every library the
  build includes and who needs it. EN/ID.
- Verified: JVM test builds an AppCompat + Material 3 + ConstraintLayout app and checks it with
  aapt2, dexdump and apksigner; the emulator test builds the same app on the device, installs and
  runs it with no crash.

Not done / limits
- Annotation processors (Room, Glide compiler), Kotlin-only and Compose APIs, Gradle plugins other
  than google-services (e.g. Crashlytics), manifest placeholders other than `${applicationId}`.
- Library resources are recompiled on every build; a large set (Material) costs seconds on a phone.
- Play services and Firebase are resolved and merged by the same rules but have not had their own
  device test yet.

## Build status

- `./gradlew testDebugUnitTest :engine:project:test :engine:layout:test :engine:blocks:test :engine:editor:test :engine:build:test :engine:deps:test :engine:ai:test` —
  passing locally (130 tests, none skipped). On CI the SDK-dependent tests are required, not skipped. and on CI (GitHub Actions, JDK 21).
- `./gradlew :app:compileDebugKotlin` — compiles.
- APK: CI publishes a signed GitHub release for each push to main. Emulator job: on-device build, install and console pass.

## Test results (latest local run)

| Suite | Tests | Result |
|---|---|---|
| engine:project ProjectStoreTest | 17 | pass |
| engine:project ProjectFilesTest | 5 | pass |
| engine:project ProjectValidationTest | 5 | pass |
| engine:project ActivitiesTest | 2 | pass |
| engine:layout XmlTest | 5 | pass |
| engine:layout LayoutEditorTest | 10 | pass |
| engine:layout DropAndValuesTest | 6 | pass |
| feature:designer PreviewRendererTest (Robolectric, real views) | 2 | pass |
| engine:blocks BlockOpsTest | 7 | pass |
| engine:blocks ValidatorTest | 3 | pass |
| engine:blocks CodegenTest (incl. javac against android.jar) | 6 | pass |
| feature:blocks BlockLayoutTest | 3 | pass |
| feature:blocks BlocksViewModelTest (save → generated Java on disk, hand-edit protection) | 3 | pass |
| engine:editor EditorEngineTest (lexers, search/replace, checks, history, indent) | 7 | pass |
| feature:code CodeViewModelTest (autosave to disk, conflict instead of overwrite, save/discard, problems) | 5 | pass |
| feature:code CodeEditTextTest (Robolectric, real view: auto-indent, undo/redo) | 2 | pass |
| engine:build FrameworkResourcesTest (ids/enums/flags vs android.jar's R classes) | 1 | pass |
| engine:build ResourceCompilerTest (checked with the SDK's aapt2; values, qualifiers, errors with lines) | 3 | pass |
| engine:build BuildPipelineTest (signed APK accepted by aapt2, dexdump, apksigner; console bridge only in debug builds; Java errors; unsupported inputs; cancel) | 5 | pass |
| engine:build ToolchainTest (official index format, SHA-1 check, cancel) | 3 | pass |
| feature:build BuildViewModelTest (license before download, real build, failed step) | 3 | pass |
| engine:ai AnthropicAiClientTest (official SDK against a local server: streaming, request body, auth error, cancel) | 3 | pass |
| engine:ai OpenAiCompatibleClientTest (streaming, usage, no-key requests, provider errors) | 2 | pass |
| engine:ai ProposalTest (parser, cut-off files, randomized diff round-trip, hunks) | 4 | pass |
| engine:ai ChangeSetTest (path escapes refused, apply only if unchanged, changed-since-sent) | 3 | pass |
| engine:ai AiSettingsStoreTest (key never in the config file, setup checks) | 2 | pass |
| feature:ai AiViewModelTest (attached files sent, nothing written until Apply, refused paths, setup gate, errors) | 2 | pass |
| feature:console ConsoleTest (Robolectric provider: unverified callers rejected, logcat parsing; stack-frame links; filters, capacity) | 3 | pass |
| core:database ProjectDaoTest | 3 | pass |
| core:data ProjectRepositoryTest | 4 | pass |
| app AppFlowTest (Robolectric, end-to-end create → designer → saved XML → blocks → saved block file → settings → files → code tab → build tab → console tab → AI setup) | 1 | pass |

## Known issues

- None critical.
- The development container's network drops some Maven requests; this does not affect the
  project, only local setup (CI downloads directly).

## Next

Phase 8: integration and an emulator CI job that runs the on-device build, the console bridge and
the Keystore store on ART (including the Kotlin-on-ART spike), performance and memory work, a
security review, then building and publishing the APK. The remaining Phase 6 items
(dependencies, Kotlin, AAB, release signing UI) are tracked above.
