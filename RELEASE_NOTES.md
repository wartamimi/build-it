Build It for Android 8.0 and newer.

New in 0.19.0 (owner feedback on 0.18.0): the Events tab is laid out like Sketchware's, with
Activity, View, Component and More block on the left, the screen's events on the right and + to
add one. Each event opens in its own full-screen editor, without the tabs, the tools menu or the
run bar, and shows only that event and loose blocks. Event blocks (when screen is created, ...)
stay in place; other blocks move only after a long press, and blocks are added from the drawer
with a long press (drag them onto the canvas). Shorter toolbars: preview options and zoom moved
into menus. The designer's "+" opens a narrow widget column beside the layout and turns into
"×". A selected view is filled with a soft tint instead of outlined, and the preview keeps its
size when a view is selected. New projects: choose the version, minimum Android version,
AppCompat and Material, and the theme's main and accent colours before creating. New simple
line icons.

New in 0.18.0: a new look built around the preview, with warm colours and a brick-orange
accent. The preview fills the designer; widgets sit in a round dock on its left (tap + to open the
full list, or drag an icon onto the screen). Tap a view and a bubble next to it offers
Properties, Events, Collection, Duplicate and Delete; Events jumps straight to that view's events.
"Screen events" in the designer's toolbar opens the screen's own events. The Events tab shows one
card per thing that has events (the screen, each view, each component, your procedures) with a
chip per event: a tick means the screen has it (tap to open its blocks), + adds it. The blocks
drawer has a search field and a row of round category bubbles.

New in 0.17.1: the blocks drawer slides up from the bottom (blocks on the left, categories on
the right) and blocks are smaller, at the same scale as the rest of the screen.

New in 0.17.0: the designer and the block editor look and feel closer to Sketchware. The widget
palette is a narrow column of short names with a drawing for each widget (Linear(H), Linear(V),
Scroll(H), Scroll(V), Button, ...) under Layouts, Widgets, Lists & media and AndroidX; drag a
widget sideways onto the screen right away (a long press still works), or tap it. The preview
shows the status bar and the title bar of the screen in the app's colours. The bar under the
selected view shows its id (tap it to pick another view), Basic/Recent/All tabs and a card per
property. Blocks have Sketchware-style shapes: a notch on top and a tab underneath so they click
together, a rounded cap on events, pointed true/false values, round number values, white boxes for
typed text and numbers, and brighter colours per category. The blocks drawer slides in from the
right and shows each block exactly as it will look. While dragging a block a grey outline shows
where it will land, with a short vibration when it snaps.

New in 0.16.0: Kotlin projects now build on your phone. The first Kotlin build downloads the
Kotlin compiler (about 60 MB) and prepares it once; Kotlin and Java files in one project can call
each other. New projects can start from six small apps, in Java or Kotlin: Counter, Calculator,
To-do list, Notes, Stopwatch and Dice, made of normal layouts and blocks you can change. Release
APKs: create a signing key or import a .p12 keystore, build a release APK signed with it, and
export the keystore as a backup. Project settings have a permissions manager. Fixed: saving
project settings removed the project's libraries and the AppCompat switch; Kotlin code for
setting the text of a text field did not compile. Library downloads retry when Maven Central is
busy. Faster first launches (baseline profile). Not yet: App Bundles (AAB).

New in 0.14.0: tap a property card under the selected view to edit it in a popup (sizes,
colours from a palette, choices, numbers with presets), with Basic, Recent and All tabs. A
collection shared by all your projects: save views, blocks, images and sounds once and reuse them.
Libraries has an AppCompat and Material switch: screens then use AppCompatActivity and a Material
Components theme.

In 0.13.0: more widgets, closer to Sketchware: Spinner, SeekBar, RatingBar, ListView,
GridView, VideoView, CalendarView, ToggleButton, RadioGroup, AutoComplete and Space. Their events
(item selected or clicked, moved, rating changed, text changed, long click, video finished, date
picked) and blocks (list items, selection, rating, video playback, calendar date). Each screen
has options for orientation, keyboard and fullscreen (layout menu → Screen options).

In 0.12.0: 12 new components with their blocks and events: Intent, Vibrator, Calendar,
Animator, Sensor, Text to speech, Speech to text, File picker, Request network, Notification,
Date picker and Time picker. Components add the Android permissions they need to the app's
manifest when you save.

In 0.11.0: many more blocks, closer to Sketchware. Text, math, lists of numbers, maps,
for-each loops, stop/skip, views (background, text size, hint, image, opacity, rotation,
progress, keyboard) and app blocks (clipboard, share, time). Procedures can take parameters.
Long-press a block in the drawer and drag it onto the canvas; the canvas scrolls when you hold a
block near the edge.

In 0.10.0: a new workflow inspired by Sketchware Pro, in Build It's own style. Design,
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
