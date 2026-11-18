Build It for Android 8.0 and newer.

New in 0.9.0: libraries. A new Libraries tab searches Google Maven and Maven Central, adds a
library at the version you choose (with everything it needs, downloaded and checked first), turns
libraries on and off, and shows every library in your build. The on-device builder now builds
apps that use AndroidX, Material Components, Google Play services, Firebase (with
google-services.json) and other Maven libraries. Verified on an Android 14 emulator: an
AppCompat + Material 3 + ConstraintLayout app built on the device, installed and ran.

Also works: project management, UI designer, block editor, code editor, AI assistant (your own
Claude or OpenAI-compatible key; every change is shown as a diff you apply yourself), console tab,
and signed debug APKs of Java projects built on the phone.

Not yet: Kotlin sources, annotation processors (e.g. Room), Compose, and Gradle plugins other than
google-services. See DEVELOPMENT_STATUS.md.
