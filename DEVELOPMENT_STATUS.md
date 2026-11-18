# Development status

Last updated: 2026-11-18

## Summary

Phases 0–3 are implemented. The app shell, design system, project management, the project
workspace (settings + file explorer) and the visual layout designer work end to end and are
covered by tests. The block editor, code editor, build engine and AI assistant are **not
implemented yet** and do not appear in the app. Per the owner's instruction, no APK is built or published until the final
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

## Build status

- `./gradlew testDebugUnitTest :engine:project:test :engine:layout:test` — passing locally (60
  tests) and on CI (GitHub Actions, JDK 21).
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
| core:database ProjectDaoTest | 3 | pass |
| core:data ProjectRepositoryTest | 4 | pass |
| app AppFlowTest (Robolectric, end-to-end create → designer → add component → saved XML → settings → files) | 1 | pass |

## Known issues

- None critical.
- The development container's network drops some Maven requests; this does not affect the
  project, only local setup (CI downloads directly).

## Next

Phase 4: `engine:blocks` (block model, definitions, validation, IR, Java/Kotlin code generation,
undo/redo) and `feature:blocks` (Canvas renderer, palette, event handlers bound to designer views).
