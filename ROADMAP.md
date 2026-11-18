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
| 6 | Local build engine: Toolchain Manager, Kotlin-on-ART spike, dependency resolver, AAPT2/ECJ/D8/R8, APK/AAB, signing, verification | Partly done: Java projects, with Maven libraries (Phase 9), build to signed, verified APKs on the device; see known gaps (Kotlin, AAB) |
| 6b | Gradle compatibility mode (experimental, non-Play builds) | Not started |
| 7 | AI providers and assistant (reviewed proposals), debug console, runtime log bridge | Done: Claude (official SDK) and OpenAI-compatible providers, reviewed per-file diffs, console bridge in debug builds; not yet run on a device (Phase 8) |
| 8 | Integration, performance and memory work, compatibility testing, security review, release (APK built and published here) | Done for release: signed GitHub releases from CI, emulator tests of the on-device build, console and UI; performance/memory work and a security review remain |
| 9 | Libraries (owner request): AndroidX, Material, Google Play services, Firebase and Maven libraries in the on-device build, Library Manager | Done: resolver, resource/manifest merge, dex cache, Library Manager tab; verified on the emulator (AppCompat + Material + ConstraintLayout app built, installed and run on the device) |
| 10 | Workflow inspired by Sketchware Pro (owner request): Design/Events/Components tabs, Run bar, project tools drawer, non-visual components, image/sound/font managers | Done for Timer, Storage, Dialog, Sound player; more component kinds later |
| 11 | Sketchware parity: block catalog (text, math, number/text lists, maps, control, views, intents, time), More Blocks with parameters, editor smoothness (drag from drawer to canvas, snapping, auto-scroll, trash, fast drag) | Done (procedures without return values) |
| 12 | Sketchware parity: components (Intent, FilePicker, Calendar, Vibrator, ObjectAnimator, SoundPool, sensors, TextToSpeech, SpeechToText, RequestNetwork, Notification, date/time pickers, Firebase via libraries) | Done (no built-in Firebase components) |
| 13 | Sketchware parity: widgets (Spinner, SeekBar, RatingBar, ListView, GridView, VideoView, CalendarView, ...), their events and blocks, and per-screen options (orientation, keyboard, title bar, fullscreen). Custom list item layouts, RecyclerView, drawer and FAB are not built yet | Done |
| 14 | Sketchware parity: per-attribute property popups, Basic/Recent properties, Collection Manager (reusable blocks, widgets, images, sounds), AppCompat/AndroidX on/off switch in the Library Manager | Planned |
| 15 | On-device Kotlin builder (compile Kotlin sources in the app); Sketchware parity: project managers (custom Java/Kotlin files, AndroidManifest editor, assets, permissions), release signing and AAB | Planned |
| 16 | Simple app templates for new projects; integration: emulator tests per phase, performance pass, docs, final signed APK | Planned |

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
