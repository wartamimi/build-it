# Build It

**Design. Build. Create.** An Android development environment that runs on Android: manage
projects, design screens, program with visual blocks, edit code, and build apps on the device.

> Status: early development. Phases 1–2 (foundation and project management) are implemented.
> The designer, block editor, code editor, local build engine and AI assistant are in progress.
> See [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

## Documents

- [ARCHITECTURE.md](ARCHITECTURE.md): modules, layers, design system, block/designer/build engine design
- [TECHNICAL_DECISIONS.md](TECHNICAL_DECISIONS.md): feasibility assessment, decisions, risks
- [ROADMAP.md](ROADMAP.md): phases
- [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md): what works today, test results, known gaps
- [docs/PROJECT_FORMAT.md](docs/PROJECT_FORMAT.md): the versioned project format

## Building Build It

Requirements: JDK 17+, Android SDK with platform 37.

```
./gradlew testDebugUnitTest :engine:project:test   # tests
./gradlew :app:compileDebugKotlin                  # compile check
```
