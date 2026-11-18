# Development status

Last updated: 2026-11-18

## Summary

Phases 0–5 are implemented. The app shell, design system, project management, the project
workspace (settings + file explorer), the visual layout designer, the block editor (with Java
and Kotlin code generation) and the code editor work end to end and are covered by tests. The
build engine and AI assistant are **not implemented yet** and do not appear in the app. Per the owner's instruction, no APK is built or published until the final
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

## Build status

- `./gradlew testDebugUnitTest :engine:project:test :engine:layout:test :engine:blocks:test :engine:editor:test` —
  passing locally (96 tests) and on CI (GitHub Actions, JDK 21).
- `./gradlew :app:compileDebugKotlin` — compiles.
- APK: intentionally not built yet (owner's instruction: only after the final phase).

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
| core:database ProjectDaoTest | 3 | pass |
| core:data ProjectRepositoryTest | 4 | pass |
| app AppFlowTest (Robolectric, end-to-end create → designer → saved XML → blocks → saved block file → settings → files → code tab) | 1 | pass |

## Known issues

- None critical.
- The development container's network drops some Maven requests; this does not affect the
  project, only local setup (CI downloads directly).

## Next

Phase 6: local build engine. First a time-boxed spike on running the Kotlin compiler on ART, then
the toolchain manager, dependency resolver, AAPT2/ECJ/D8 pipeline, APK signing and verification.
