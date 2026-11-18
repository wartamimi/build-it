Build It for Android 8.0 and newer.

Works: project management, UI designer, block editor, code editor, AI assistant (your own Claude
or OpenAI-compatible key; every change is shown as a diff you apply yourself), console tab, and
building a signed debug APK of a Java project on the phone itself (Eclipse compiler + D8 +
apksig, no server). Verified on an Android 14 emulator: build, install, launch, and the app's log
and crash appear in the Console tab.

Fixed in 0.8.1: the Blocks tab crashed when opened on Android; the on-device Java compiler
needed Java APIs that Android lacks.

Not yet: projects that use libraries (AndroidX, Material, Google Play services) and Kotlin
sources cannot be built on the phone yet; library support is the next update.
See DEVELOPMENT_STATUS.md.
