# Development status

Last updated: 2026-11-18

## Summary

Phases 0, 1 and 2 are implemented. The app shell, design system, project management and the
project workspace (settings + file explorer) work end to end and are covered by tests. Editors
(designer, blocks, code), the build engine and the AI assistant are **not implemented yet** and do
not appear in the app. Per the owner's instruction, no APK is built or published until the final
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

## Build status

- `./gradlew testDebugUnitTest :engine:project:test` — passing locally (35 tests).
- `./gradlew :app:compileDebugKotlin` — compiles.
- APK: intentionally not built yet (owner's instruction: only after the final phase).

## Test results (latest local run)

| Suite | Tests | Result |
|---|---|---|
| engine:project ProjectStoreTest | 17 | pass |
| engine:project ProjectFilesTest | 5 | pass |
| engine:project ProjectValidationTest | 5 | pass |
| core:database ProjectDaoTest | 3 | pass |
| core:data ProjectRepositoryTest | 4 | pass |
| app AppFlowTest (Robolectric, end-to-end create → workspace) | 1 | pass |

## Known issues

- None critical.
- The development container's network drops some Maven requests; this does not affect the
  project, only local setup (CI downloads directly).

## Next

Phase 3: `engine:layout` (layout model, XML parser/serializer with round-trip tests, component
catalog, commands with undo/redo), then `feature:designer` (canvas, tree, inspector, live preview).
