# Build It architecture

Build It is an Android development environment that runs on Android: projects, a visual layout
designer, a visual block language, a code editor, an AI assistant and a local build engine.
This document describes how it is put together (master prompt §20 items 2, 5, 6, 7, 8, 9).
Decisions and their reasons are in [TECHNICAL_DECISIONS.md](TECHNICAL_DECISIONS.md); progress is
in [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

## 1. Layers

```
┌──────────────────────────────── app ────────────────────────────────┐
│ MainActivity · navigation shell · AppContainer (manual DI)          │
├──────────────────────────────── feature:* ──────────────────────────┤
│ home · projects · workspace · settings · (designer · blocks · code  │
│ · resources · build · ai · console as their phases land)            │
│ Compose UI + ViewModel (MVVM, StateFlow, one-off message channels)  │
├──────────────────────────────── core:* ─────────────────────────────┤
│ designsystem (theme, tokens, components)  data (repositories)       │
│ database (Room index)  model (plain data)  common (dispatchers…)    │
├──────────────────────────────── engine:* (pure Kotlin/JVM) ─────────┤
│ project (format, store, migration, archive, generated files)        │
│ layout (Phase 3) · blocks (Phase 4) · build (Phase 6) · ai (Phase 7)│
└─────────────────────────────────────────────────────────────────────┘
```

Dependency rule: `app → feature → core → engine`. Features never depend on each other; the app
wires navigation between them. Engines never depend on Android, so they run on ART unchanged and
are tested on the JVM.

Clean Architecture mapping: engines hold the domain logic (entities + use cases), `core:data`
holds repositories (interfaces + implementations), features hold presentation. Coroutines and
Flow throughout; blocking I/O runs on the injected `io` dispatcher.

## 2. Module structure

| Module | Type | Responsibility |
|---|---|---|
| `app` | Android app | Activity, navigation shell (bottom bar / rail ≥600dp), DI container, locale switching, startup recovery scan. |
| `core:common` | JVM | `DispatcherProvider`, `Clock`, `IdGenerator`. |
| `core:model` | JVM | Plain models shared by UI: `UserSettings`, `ProjectSummary`. |
| `core:designsystem` | Android + Compose | Theme (light/dark/dynamic), palette, typography, spacing, shapes, elevation, components (buttons, top bar, cards, fields, dialogs, bottom sheet, rows, loading/empty/error states), icons. |
| `core:database` | Android (Room) | Project index (`projects` table), exported schemas for migrations. |
| `core:data` | Android | `ProjectRepository`, `SettingsRepository` (DataStore), `WorkspaceInfo`. |
| `engine:project` | JVM | Project format v1, validation, store (create/load/save/rename/duplicate/delete), migrations, ZIP import/export, backups/restore, templates, generated-file tracking, safe project file operations. |
| `engine:layout` | JVM | Layout XML model (lossless parser/writer), component catalog, edit commands with undo/redo, validation, drop resolution, resource values. |
| `feature:designer` | Android + Compose | Visual designer: real-View preview renderer, canvas host, component library, tree, properties inspector, problems; contributed to the workspace as the Design tab. |
| `engine:blocks` | JVM | Block document model + JSON store, block definitions, editing ops with undo/redo, validation, IR, Java/Kotlin backends, safe source sync. |
| `feature:blocks` | Android + Compose | Block editor: Canvas renderer and layout, gestures/snapping, palette, variables, problems, code preview; contributed as the Blocks tab. |
| `engine:editor` | JVM | Code editor logic: lexers for highlighting, search/replace, syntax checks, edit history and auto-indent. |
| `feature:code` | Android + Compose | Code editor: file tabs, `EditText`-based editor with gutter and highlighting, find/replace, go to line, problems, conflict handling; contributed as the Code tab and opened from Files. |
| `feature:home` | Android + Compose | Dashboard: quick actions, recent projects, storage. |
| `feature:projects` | Android + Compose | Project list, search/sort, create sheet, rename/duplicate/delete, ZIP import/export (SAF), backups. |
| `feature:workspace` | Android + Compose | Open project: project settings (app ID, versions, SDK levels) and file explorer. Editor tabs are added per phase. |
| `feature:settings` | Android + Compose | Theme, dynamic color, language, editor font size, auto-save, rescan workspace, about. |
| `build-logic` | Gradle | Convention plugins shared by all modules. |

Planned modules: `engine:build`, `toolchain:*`,
`feature:build` (Phase 6); `engine:ai`, `feature:ai`, `feature:console` (Phase 7);
`feature:resources` (later).

## 3. Storage

```
filesDir/workspace/
  <project-slug>/
    buildit.json               project manifest (versioned, see docs/PROJECT_FORMAT.md)
    .buildit/                  Build It metadata (generated-file hashes, backups, later: blocks, build cache)
    settings.gradle.kts        so the project also opens in Android Studio
    build.gradle.kts
    app/build.gradle.kts       generated from buildit.json (hash-tracked)
    app/src/main/AndroidManifest.xml   generated (hash-tracked)
    app/src/main/java|kotlin/… sources
    app/src/main/res/…         resources, layouts
  .<name>.creating|copying|importing|deleting…   staging dirs, removed at startup
```

The Room database is only an index of the folders above and is rebuilt from disk at startup.
User-selected storage locations are reached through the Storage Access Framework (ZIP import,
export, backups); projects are edited inside the app's private workspace for speed and safety.

## 4. UI/UX design specification (§20 item 6)

**Identity.** Name "Build It", tagline "Design. Build. Create." The mark is an open bracket next
to two stacked blocks: code and visual building in one glyph. Original palette:

| Role | Light | Dark | Use |
|---|---|---|---|
| Primary "Blueprint" | `#2D57C0` | `#B4C5FF` | Main actions, selection, links |
| Secondary "Kiln" | `#A63D0B` | `#FFB597` | Build/run actions and status |
| Tertiary "Mint" | `#136B50` | `#85D7B4` | Success, completed states |
| Error | `#BA1A1A` | `#FFB4AB` | Errors only |
| Neutrals "Graphite" | `#FAF9FF` surface | `#13151A` surface | Calm surfaces for editors |

Extended semantic colors (success, warning, build, and syntax colors for keyword, string, number,
comment, type, tag, attribute) are provided through `BuildItThemeExt.colors` for both themes.

**Type.** System sans for UI (no font download), SemiBold titles; monospace for code, logs, package
names and paths (`CodeTypography`).

**Spacing.** 4 dp grid: 2, 4, 8, 12, 16, 24, 32, 48. Minimum touch target 48 dp. Readable content
max width 840 dp on tablets.

**Shapes.** 4 / 8 / 12 / 16 / 24 dp corner radii (extra-small → extra-large).
**Elevation.** Tonal elevation levels 0 / 1 / 3 / 6 dp; no heavy shadows, no gradients.

**Components** (`core:designsystem`): `BiButton` (primary, tonal, outlined, text; built-in loading
state that blocks double taps), `BiIconButton` (content description required), `BiTopBar`
(title + subtitle, max two visible actions), `BiCard`, `BiSectionHeader`, `BiTextField` (inline
error/supporting text, optional monospace), `BiConfirmDialog` (destructive variant),
`BiBottomSheet`, `BiSwitchRow`, `BiClickRow`, `BiLoadingState`, `BiEmptyState` (purpose + one
action), `BiErrorState` (human explanation + retry, announced to screen readers).

**Navigation.** Phones: bottom navigation bar. ≥600 dp: navigation rail. Top level: Home,
Projects, Settings (AI Assistant, Build Manager and Debug Console join when implemented). Inside a
project: tabs; the final tab set is Design, Blocks, Code, Resources, Manifest, Build.

**Principles.** One primary action per screen; destructive actions always confirm and say what
will be lost; validation messages sit under the field and explain how to fix the problem;
errors explain in words, raw details are secondary; no decorative animation; every string in
English and Indonesian.

## 5. Block engine architecture (Phase 4, §20 item 7)

```
Workspace (blocks + positions)          ← edited by the renderer through Commands (undo/redo)
   │  validate (types, required inputs, scopes, event context)
   ▼
Typed AST (Event, Stmt, Expr nodes with resolved types and symbols)
   │  lower
   ▼
IR (language-neutral, explicit types, structured control flow, resolved imports/permissions)
   │  generate
   ├── Java backend  ──► deterministic source (stable ordering and naming)
   └── Kotlin backend ──► deterministic source
```

- **Model.** Nested tree: `Block(id, type, fields, inputs: Map<name, Block>, statements:
  Map<name, List<Block>>, disabled)`; top-level `BlockStack(x, y, blocks)`. Statement order is the
  list order, so there are no next/parent pointers to keep consistent.
- **Definitions.** A `BlockDefinition` registry declares each type's category, fields (with
  validators), inputs (accepted types), output type, scope rules, required imports/permissions
  and its lowering to AST. Categories: Events, Logic, Variables, Math, Text, Loops, Lists,
  Functions, Components, Intent, Network, JSON, Database, Firebase, Files, Media, Custom Code.
- **Validation** produces diagnostics anchored to block ids (shown as error badges).
- **Serialization.** JSON per activity in `.buildit/blocks/<Activity>.json`, versioned with the
  project format; round-trip tested.
- **Renderer** (separate module) draws on a Compose `Canvas` with its own measure/layout pass,
  viewport culling, cached geometry, hit testing, snapping to compatible connections, zoom/pan,
  minimap, multi-select, copy/paste, search and context menus. It never contains code-generation
  logic.
- **Undo/redo** is a command stack on the model (move, connect, disconnect, edit field, add,
  delete, group), shared by keyboard, touch and AI-applied changes.
- **Code synchronization** uses marked generated regions plus `GeneratedFiles` hashes; see
  TECHNICAL_DECISIONS A4.

## 6. UI designer architecture (Phase 3, §20 item 8)

- **Source of truth:** `res/layout/*.xml`, parsed into an immutable `XmlElement` tree (each node
  has a session-stable `uid`); comments, text, unknown elements and attributes are preserved.
- **Component catalog:** definitions of supported views (TextView … BottomNavigation, custom view)
  with their editable attributes, container rules (which parents accept which children) and
  defaults.
- **Edits** are commands on the document (insert, move, reorder, delete, set attribute) with
  undo/redo and validation (duplicate ids, invalid parent, missing required attributes).
- **Serializer** writes stable, formatted XML (attribute order fixed: id, layout_*, then
  alphabetical) so diffs stay small.
- **Preview** builds real Android `View` objects from the document inside a sandboxed container,
  per device profile (size, density, orientation, night mode) via `createConfigurationContext`;
  selection overlays map back to node ids; render errors are reported per node.
- **Panels:** component library (search + categories) · canvas · properties inspector ·
  component tree · problems. On wide screens they are side panes; on phones the canvas sits
  above a tabbed panel so the library can be dragged onto the canvas directly.
- **Workspace integration:** `WorkspaceScreen` accepts `WorkspaceExtraTab`s, so feature modules
  add tabs without the workspace depending on them; the app module wires the designer in.
  The top-bar back routes through the back dispatcher so a tab can save before leaving.

## 7. Build engine architecture (Phase 6, §20 item 9)

```
BuildRequest(project, variant, output=APK|AAB, signing)
  → BuildWorker (separate :build process, foreground service, cancellable, memory-checked)
     1 ProjectValidator        unsupported features reported here, before any work
     2 ToolchainValidator      ToolchainManager: android.jar, aapt2, ecj, kotlinc, d8/r8, bundletool, apksig — versions pinned and checksummed
     3 DependencyResolver      Maven POM resolver + AAR extraction + offline cache
     4 SourceGenerator         blocks → code, layouts, R/BuildConfig, google-services values
     5 JavaCompiler (ECJ)      in-process
     6 KotlinCompiler          in-process if the Phase 6 spike succeeds, otherwise Gradle mode
     7 ResourceProcessor       aapt2 compile + link (binary or proto format for AAB)
     8 Dexer                   D8 (debug) / R8 (release, memory permitting)
     9 Packager                APK (zipalign rules) or AAB (bundletool)
    10 Signer                  apksig v1–v3; keystore create/import
    11 ArtifactVerifier        signature verification, AAB structure validation
    12 BuildReport             logs, timings, diagnostics with explanations, history in Room
```

Every step is a `BuildStep` with inputs/outputs fingerprints for incremental builds; logs are
streamed to the Build console and preserved per build. A build is reported successful only when
step 11 verified a real artifact on disk.

## 8. Testing

- JVM unit tests for engines (fast, no Android).
- Robolectric tests for Room DAOs, repositories and an end-to-end UI flow through the real app
  shell (`AppFlowTest`).
- Phase-specific: block serialization/validation/codegen golden tests, layout XML round-trip
  tests, build pipeline integration tests against fixture projects, signature verification.
- Instrumented tests on devices/emulators are added for the preview renderer and build worker.
