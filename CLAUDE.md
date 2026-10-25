# Diecut: notes for Claude

Read `README.md` first: it has the idea and the version 1 scope. This is a new project.
Nothing is built yet and no stack is set in stone, so the first session should settle
the open questions at the end of this file with the user before writing code.

## The technique (proven in TomeVoice)

A widget's face is **one bitmap** drawn on a `Canvas` and set on a `RemoteViews`
`ImageView` (`scaleType="fitXY"`). The shape is a `Path`. Cut-outs are holes made with
`Path.op(holes, Path.Op.DIFFERENCE)` on an `ARGB_8888` bitmap, and the launcher shows
the transparency as-is, so the wallpaper comes through.

Reference: `D:\Projects\TomeVoice\app\android_overlay\app\src\main\kotlin\app\tomevoice\tomevoice_spike\Widgets.kt`
- `Widgets.stampFace()`: the postage stamp. Holes of radius
  `min(w, h) * 0.024` dp (clamped to 2.6 to 4.2 dp), about 3.6 radii apart, so each
  tooth is about a hole wide. The holes are spread evenly along each edge with one
  centred on every corner.
- `BaseWidget.draw()`: reads the widget's size from `getAppWidgetOptions` (portrait
  `MIN_WIDTH` x `MAX_HEIGHT`) and draws at no more than 2.5 px per dp. RemoteViews
  bitmaps go over IPC, and big ones fail silently.
- `BaseWidget.place()`: on Android 12 and later, invisible `ImageButton`s are moved
  over the buttons drawn on the bitmap with `setViewLayoutMargin` and
  `setViewLayoutWidth`/`Height`, so taps land where things are drawn.
- `widget_player_stamp_info.xml` and `drawable-nodpi/widget_preview_player_stamp.png`:
  the widget picker shows `previewImage`, so each shape needs its own preview, cut
  the same way.

Things learned there:
- Don't give the widget root a background with `@android:id/background`: on Android
  12+ that opts into the launcher's rounded-corner clipping and would cut the shape.
- A bitmap can't keep a clock ticking. For clocks, draw the shape as the bitmap and
  put a `TextClock` (digital) over it in the RemoteViews. It updates for free; redrawing
  every minute costs battery. Analogue hands need another plan (decide early).
- Photos: use the system Photo Picker (no storage permission), copy the picture into
  app storage, and downsample it to the widget's size.
- App shortcuts: Android 11+ package visibility needs a `<queries>` entry for
  `MAIN`/`LAUNCHER` to list apps. Launch each one with a `PendingIntent` built from its
  launch intent.
- Per-widget settings go in a configuration activity (`android:configure`,
  `widgetFeatures="reconfigurable|configuration_optional"`), keyed by widget id.
  Clear them in `onDeleted`.

## How the user works (carry these over)

- **Builds run on GitHub Actions, not this PC.** The PC is kept light: no JDK,
  Android SDK, Gradle or emulators installed. Flutter isn't installed either; a Dart
  SDK is. Set up CI that builds the APK on each pull request.
- **Test on the real phone**: a Nothing Phone (A059, Android 16, Nothing launcher) on
  USB. `adb` is on PATH. Tap by on-screen label (dump the UI with `uiautomator`), never
  by coordinates; the user may be using or rotating the phone. Test and screenshot in
  portrait.
- **Done means seen**: a change counts as done once a screenshot from the phone shows
  it working. Otherwise say plainly what is untested.
- Phone audio can't be captured here (scrcpy records 0-byte files on this PC).
- Delete screenshots, preview HTMLs, downloaded APKs and browser downloads once a
  task is done.
- Ask before publishing anything as an Artifact; a local HTML file is the default.
- Never name a branch like a version tag (`v1.0.0`); it collides with release tags.
- Commit and push only when the user asks. Work on a branch and open a PR, as in
  TomeVoice.

## Decisions (settled in the first session, 2026-10-26)

1. **Stack**: native Kotlin, Jetpack Compose for the app's screens, no Gradle wrapper
   committed (CI picks Gradle 8.13). AGP 8.11, Kotlin 2.2, compile/target SDK 36.
2. **App id** `app.diecut`, **minSdk 31** (Android 12: movable tap targets).
3. **Content**: photo, clock, apps, dates (birthdays and special days). **Shapes**:
   stamp, film, polaroid, frame, circle, squircle, ticket, scallop, arch.
4. **Editor**: the configuration activity, with a live preview from the same renderer.
5. **Price**: not decided; shapes live in one enum so some can be locked later.
6. **Repo**: github.com/Sayandeep1013/diecut (public). Releases are `v*` tags; CI
   attaches the APK. The release key is
   kept outside the repo in `~/.diecut/` and in the repo's secrets.

Also learned here: analogue hands work as `AnalogClock` icons (`setDial`,
`setHourHand`, `setMinuteHand`) on the Nothing launcher. `tools/art.py` draws the icon,
the picker previews and the confetti; `tools/showcase.py` draws the README pictures.
