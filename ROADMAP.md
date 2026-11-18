# Build It roadmap

Phases follow the master prompt §17. A phase is complete only when its core features work, its
integration points work, tests pass, the app compiles, no critical issue is known, and the docs
are updated (§19). Status details are in [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

| Phase | Scope | Status |
|---|---|---|
| 0 | Technical assessment, architecture, decisions | Done |
| 1 | Foundation: project setup, architecture, design system, navigation, database, home dashboard, settings | Done |
| 2 | Project management: create, explorer, file management, templates, import/export, backups, auto-recovery | Done (see known gaps) |
| 3 | UI designer: layout model, XML parse/serialize, component catalog, drag-and-drop canvas, tree, inspector, live preview | Done (see known gaps) |
| 4 | Block engine: model, definitions, validation, AST/IR, Java/Kotlin codegen, Canvas renderer, undo/redo | Done (see known gaps) |
| 5 | Code editor: editor abstraction, Java/Kotlin/XML/JSON/Gradle highlighting, find/replace, tabs, diagnostics | Done (see known gaps) |
| 6 | Local build engine: Toolchain Manager, Kotlin-on-ART spike, dependency resolver, AAPT2/ECJ/D8/R8, APK/AAB, signing, verification | Not started |
| 6b | Gradle compatibility mode (experimental, non-Play builds) | Not started |
| 7 | AI providers and assistant (reviewed proposals), debug console, runtime log bridge | Not started |
| 8 | Integration, performance and memory work, compatibility testing, security review, release (APK built and published here) | Not started |

## Phase 1 implementation plan (as executed)

1. Gradle setup: version catalog, convention plugins, AGP 9.5.1 / Gradle 9.6.0 / Kotlin 2.4.21.
2. Modules: `core:common`, `core:model`, `core:designsystem`, `core:database`, `core:data`,
   `engine:project`, features `home`, `settings` (+ `projects`, `workspace` for Phase 2).
3. Design system: palette, light/dark/dynamic themes, typography, spacing, shapes, elevation,
   components, icons, EN/ID strings.
4. Navigation: type-safe Navigation Compose routes, bottom bar / rail.
5. Database: Room project index with exported schema and DAO tests.
6. Home dashboard: quick actions, recent projects, storage summary, empty state.
7. Settings: theme, dynamic color, language, editor font size, auto-save, rescan, about.
8. Tests: engine unit tests, DAO and repository tests, end-to-end UI flow test.
9. CI: unit tests and compile checks on every push.
