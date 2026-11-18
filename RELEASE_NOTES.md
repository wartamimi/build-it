Build It for Android 8.0 and newer.

New in 0.10.0: a new workflow inspired by Sketchware Pro, in Build It's own style. Design,
Events and Components tabs; a bottom bar to pick the screen and Run (save, build and install in
one tap); every other tool in the Project tools drawer. The designer has the widget palette
beside the phone and a property bar for the selected view. Events lists the screen's events;
tapping one opens the full-screen block editor with a slide-up block drawer. New non-visual
components with their own blocks: Timer, Storage, Dialog and Sound player. New Images, Sounds and
Fonts managers import files into the project.

In 0.9.0: libraries. A new Libraries tab searches Google Maven and Maven Central, adds a
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
