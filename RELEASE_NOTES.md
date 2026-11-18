Build It for Android 8.0 and newer.

Works: project management, UI designer, block editor, code editor, AI assistant (your own Claude
or OpenAI-compatible key; every change is shown as a diff you apply yourself), console tab.

**Known problem:** building an APK on the phone stops at the Compile step on Android: the Java
compiler needs Java APIs that Android lacks. All desktop tests pass, but it has not worked on a
device yet. See DEVELOPMENT_STATUS.md.
