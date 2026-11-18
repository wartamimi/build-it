# Build It project format

Current version: **1**

A Build It project is a directory. Its root contains `buildit.json`, which identifies the
directory as a Build It project and stores its configuration.

## `buildit.json` (format 1)

```json
{
  "formatVersion": 1,
  "id": "6f1c…",                 // stable unique id; a new id is assigned on duplicate/import
  "name": "My App",              // display name, 1–50 chars, no / \ : * ? " < > |
  "createdAt": 1763460000000,    // epoch ms
  "updatedAt": 1763460000000,
  "app": {
    "packageName": "com.example.myapp",   // applicationId
    "namespace": "com.example.myapp",     // source package of R; optional, defaults to packageName
    "dependencies": [                     // optional (added in 0.9, no format change): Maven libraries
      { "coordinate": "androidx.appcompat:appcompat:1.7.1", "enabled": true }
    ],
    "versionCode": 1,
    "versionName": "1.0",
    "minSdk": 24,
    "targetSdk": 36,
    "compileSdk": 36,
    "language": "java"                    // "java" | "kotlin"
  },
  "modules": [
    {
      "name": "app",
      "type": "application",              // "application" | "library"
      "activities": [
        { "name": "MainActivity", "layout": "activity_main", "launcher": true }
      ]
    }
  ]
}
```

Unknown keys are ignored when reading, so adding an optional key with a default does not need a
new format version. Removing, renaming, or changing the meaning of a key does.

## Directory layout

```
buildit.json
.buildit/generated.json      SHA-256 of each file Build It generated (see "Generated files")
.buildit/backups/            backup-<epochMs>.zip and manifest-v<N>-<epochMs>.json
settings.gradle.kts, build.gradle.kts, app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/…  or  app/src/main/kotlin/…
app/src/main/res/…
```

## Generated files

`AndroidManifest.xml` and `app/build.gradle.kts` are generated from `buildit.json`. Build It
records their hashes; if a file was edited by hand, Build It will not regenerate it without the
user's explicit confirmation.

## Migrations

- Each format change adds a `FormatMigration(fromVersion = N)` that transforms the raw JSON from
  N to N+1. Migrations run in order on load.
- Before a migrated manifest is written, the original is copied to
  `.buildit/backups/manifest-v<N>-<time>.json`.
- A project with a newer format than the app supports is not opened; the user is told to update
  Build It. Nothing is modified.

## ZIP archives

Export contains the project directory contents at the archive root, excluding `build/`,
`.gradle/` and `.buildit/backups/`. Import rejects entries that escape the target directory,
archives above 20,000 entries or 512 MB uncompressed, and archives without `buildit.json`.
