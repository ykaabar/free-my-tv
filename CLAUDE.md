# Free My TV: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Free My TV and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
LG TVs (webOS) and Samsung TVs (Tizen) get the same home screen as a web app: `web/`, packed by `webos/` and `tizen/`
(see below).
The website (freemytv.io) is in `docs/`: static HTML, CSS and JS, no build step. A push to `main` that changes it
publishes it to GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (English) and `docs/pt/index.html`
(Brazilian Portuguese) are the same page in two languages, so change both; visitors from Brazil land on the
Portuguese one by themselves, and the PT/EN switch in the menu is remembered.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: WSL, then as on Linux. Or, with no tools but adb: the *Build the APK* workflow on their fork
     (`.github/workflows/apk.yml`), then `adb install -r FreeMyTV.apk` from the downloaded artifact.
     Its *edition* input: *mine* (every secret) or *family*, the build the owner gives to family and friends: the TMDB
     key only (picks, the films screensaver, Ambience by time of day); no iCloud album, LED strip or Unsplash key
     (Unsplash's free tier is for one app in testing, 50 requests an hour); the settings then hide what needs them.
     Every build is numbered (the run number is the versionCode); a family build is also published as the release
     `family-<number>`, and the family's TVs install it themselves (`Updater`, twice a day at most, from the home
     screen; Android asks on screen; `fire/menage-fire.bat` allows "install unknown apps" for the app).
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `tmdb_region` (two-letter country), `show_crypto`, `currency` and `temperature` to match.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`. On TCL, also `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Free My TV* → *Remote Buttons* picks what each opens); *Original* turns it off.
8. **They switch on the TV:** *Free My TV* → *Home Screen* → *Free My TV*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Installing it on an LG TV (webOS)

On LG it's an app, installed through LG's Developer Mode, and it changes nothing else on the TV: LG's home screen
stays, and Free My TV opens with a long press of a number key.

1. **Ask** for the TV's IP address and their city, if they didn't say. Tools: Python 3 and OpenSSH (`ssh`,
   `ssh-keygen`), already on macOS, Linux and Windows 10+.
2. **On the TV** (they do it): LG Content Store → *Developer Mode* → sign in with a free LG developer account
   (webostv.developer.lge.com) → *Dev Mode Status* on (the TV restarts) → *Key Server* on. It shows the IP and a
   passphrase.
3. **Settings:** the same `config.properties` as on Android (the first `python3 webos/build.py` creates it). The
   TMDB key rules above apply.
4. **Build:** `python3 webos/build.py` must end with `Built FreeMyTV-webos.ipk`.
5. **Install:** the first time needs the TV's passphrase, typed by them: ask them to run
   `python3 webos/install.py <IP>` in their own terminal (yours can't answer its prompt, and the passphrase doesn't go
   in the chat). It keeps the unlocked key in `~/.ssh/freemytv_webos_<IP>`, so later updates run from your terminal
   with the same command.
6. **Tell them:** while Free My TV is open, holding a number key pins it to that key. Developer Mode lasts about 40
   days; *EXTEND* in that app renews it (if it runs out, the TV removes developer apps, and installing again brings it
   back). `--uninstall` removes Free My TV.

## Installing it on a Samsung TV (Tizen)

On Samsung it's an app too (2017 and newer, Tizen 3+), installed through Samsung's Developer Mode; Samsung's home
screen stays. Two ways:

- **The release, no tools:** TizenBrew Installer (github.com/reisxd/TizenBrewInstaller) installs the latest GitHub
  release when they type `simbasjungles/free-my-tv`, and on 2023+ TVs signs it with their Samsung account. That build
  has no TMDB key (no picks) and takes the city from the TV's time zone. Walk them through the README's steps.
- **Their own build** (their city, their TMDB key), below.

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **On the TV** (they do it): *Apps* → type `1 2 3 4 5` on the remote → *Developer mode* on, *Host PC IP* = this
   computer's IP → restart the TV.
3. **Tools:** Samsung's Tizen tools, the `tizen` and `sdb` commands (Tizen Studio's CLI, or Samsung's Claude Code
   plugin `tizen-sdk-skills` from github.com/Samsung/tizen-agent-skills, which installs them and makes Samsung
   certificates). `tizen` runs on Java; Samsung's `sdb` for macOS is an Intel binary, so on Apple Silicon it needs
   Rosetta (`softwareupdate --install-rosetta`): ask them first.
4. **Certificate, once:** 2023+ TVs (Tizen 7+) need a Samsung certificate, made with their Samsung account for this TV
   (its DUID): the plugin's Samsung profile opens the browser for them to sign in, or Tizen Studio's Certificate
   Manager does it. 2017-2022 TVs take a Tizen certificate (`tizen certificate`, then `tizen security-profiles add`).
   Certificate passwords are theirs: they type them in their own terminal, never in the chat.
5. **Settings:** the same `config.properties`; the TMDB key rules above apply.
6. **Build and sign:** `python3 tizen/build.py --sign <profile>` must end with `Built FreeMyTV-tizen.wgt (...,
   signed with "<profile>")`.
7. **Install:** `sdb connect <IP>`, then `tizen install -n FreeMyTV-tizen.wgt -t <serial from sdb devices>` and
   `tizen run -p FreeMyTVio.FreeMyTV -t <serial>` to open it. Updates need the same certificate: the TV refuses one
   signed by another (`tizen uninstall -p FreeMyTVio.FreeMyTV -t <serial>` first).
8. **Tell them:** *Autorun Last App* in the TV's settings (under Start Screen Options; where it is changes by year)
   makes the TV turn on into Free My TV.

## Releases

TizenBrew Installer takes the first `.wgt` of the latest GitHub release, so every release carries
`FreeMyTV-tizen.wgt`: built with `--generic` (no config.properties: no key, the city from the time zone) and signed
with a Tizen certificate (`--sign`), which 2017-2022 TVs install as it is; on 2023+ TVs the installer signs it again
with the person's Samsung certificate. Sign every release with the same author certificate: older TVs refuse an
update signed by another. `python3 webos/build.py --generic` makes the LG `.ipk` that goes along.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

**Fire TV (Fire OS 8)**: Amazon's launcher can't be replaced, and Fire OS 8.1.0.3+ refuses an app's own ADB
connection to 127.0.0.1 (it closes it at once), so `HomeMode.FIRE` uses no ADB of its own: once, from the computer,
`fire/menage-fire.bat` grants `WRITE_SECURE_SETTINGS` (`HomeMode.FIRE_GRANT`) and turns off the Amazon entries of
`DEBLOAT` (`fire/restaurer-fire.bat` undoes both). The switch on the TV then turns `FireHomeService` on or off by
itself: an accessibility service that opens our home screen when Amazon's launcher comes to the front (a short flash
of Amazon's home on each Home press). Fire OS holds back JobScheduler jobs of sideloaded apps, so immediate switches run
in `ModeService`; the boot and update broadcasts put the service back at once (`HomeMode.fireRestore`), and the
service opens our home once when it binds in the first minutes after a restart (Amazon's home came up before it could
see it). Fire OS keeps only warnings in the log (`AppLog` logs at that level). The sleep timer uses FireHomeService's
"lock screen" global action there (`FireHomeService.sleepNow`), which puts the stick to sleep. Keep the `.bat` files in step with `DEBLOAT`.

LG and Samsung: the web app above. Other TVs that don't run Google TV or Android TV: the way is a Google TV streamer on
HDMI (Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 28 (Android 9), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 has no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 9 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step.
  To check for newer APIs, compile against `platforms/android-28/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded) and `Recording.Node` should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 9 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("pt", "en")`, with the Spanish, German, Russian and French in `Spanish.java`, `German.java`,
  `Russian.java` and `French.java` (keyed by the English); text built at runtime uses `Ui.tr(pt, en, es)`, and its German and Russian
  come from those files too (patterns for the parts built at runtime). Add them all. Comments in English, short, only where the why
  isn't obvious.
- The screensaver shows the picks' images, Unsplash's landscapes and animals (`Nature`, with the person's own free
  Access Key in `unsplash_key`; never the Secret Key), both, or the iCloud album (portrait photos shown whole over
  a blurred copy, `Backdrop.photo(..., fit)`), per *Home Settings* → *Screensaver*
  (`Prefs.screensaver`). Unsplash's rules: credit the photographer on screen and report each kept photo to its
  `download_location` once. The key follows the TMDB key's rules: theirs, never printed or committed.
- The bottom drawer's cards: weather (with rain in the next 3 hours, air quality and pollen, all Open-Meteo, no key),
  quotes, *Today* (`Daily`: a built-in French quote of the day and a child's age from a name and birth date typed on
  the TV, never in config.properties) and the photo card (`Album`: an iCloud shared album's link in `icloud_album`;
  iOS 26 `photos.icloud.com/shared/album/<key>` links go through CloudKit's public resolve and the zone's changes,
  older `#B0…` links through sharedstreams; both undocumented by Apple). The weather takes the room the others leave;
  when the row is too full the photo card goes first, then *Today*.
- *Home Settings* → *Theme* → *Ambience* (`Prefs.ambiance`, `ambianceSource`): time of day = one mountain in four lights in
  `assets/scenes` (the same frame, aligned to the pixel, so only the light changes, in an 8 s fade: dawn from 45 min
  before sunrise to 1 h 30 after, day, sunset from 1 h before sunset to 40 min after, then night;
  `HomeView.sceneFor`, credits in `CREDITS.txt`), each with a blurred `-glass.jpg` for the clock and a `-fg.webp`
  cut-out (one outline for the four: the summit crosses the big clock, as on iOS; drawn opaque in its own layer, `fgLayer`, that stays on the photo while the clock rises or the drawer moves);
  the weather over it (`HomeView.wxKind`, from Open-Meteo's code and the rain now): cloudy, fog, rain, snow and storm
  grade the photo, its cut-out and the plain clock glass (`graded`), veil the sky or the valley (`drawWeatherVeils`),
  and rain and snow fall in their own layer, redrawn alone on each frame and hidden under the drawer (`drawWeather`;
  60 drops on the older TVs, 120 elsewhere); mountains = Unsplash (`Nature.mood`); my photos = the iCloud album. The
  clock is Antonio Light (`Ui.clock`) in glass (after couch-tv, MIT), placed per photo in `SCENE_AT`; the date and
  weather sit above it, today's line (or tonight's picks, with the sparkles button) above the apps, which use the glass
  design's row. **Design rule from the owner: every change to this screen updates the design canvas's "Écran actuel"
  board, rendered by `tools/preview_ambiance.py` from the same values, before they build.**
- Picks come only from the services left on in *Home Settings* → *Tonight: apps* (`Prefs.pickApp`; an app installed
  without a subscription is turned off there). In Ambience, "Tonight" follows the app focused on the shelf: that
  service's own list (`Recs.forApp`, from `byApp`), the mix of them all for any other app.
- Picks: the focused card is the wide one and the background follows it; holding OK on a card plays its trailer
  (TMDB videos → the YouTube app); badges show TMDB's rating (30+ votes) and NEW / NEW SEASON (last 30 days).
- The light button (`Lights`, over `Tuya`: protocols 3.3 to 3.5 on TCP 6668, no library) drives a Tuya LED strip on
  the local network: OK turns it on or off, OK held opens its panel (`HomeView.drawLightsPanel`: switch, brightness,
  six colours, four moods (OK held on a mood keeps the colour and brightness in use in it, held again on it unchanged brings back the built-in one; `Lights.keepMood`), two automations); `LightsAuto` turns it off when the TV goes to standby (at once or after the delay picked in the panel,
  `Lights.OFF_DELAYS`, the stick held awake until then) and on when it
  wakes in the evening (from sunset, 30 min before when it rains; once per evening while it stays on), from the screen
  broadcasts, in one process only (the main one on Fire TV, kept alive by FireHomeService; `:home` elsewhere), so the
  `lights` preferences are multi-process. On Fire TV, `LightsKeyService` (in `:keys`, turned on with FireHomeService)
  gives the remote's Menu key to the strip from any app: short = on/off, held 0.6 s = a small panel at the bottom
  (with "Good night": dim light, the sleep timer at 30 min)
  (an accessibility overlay), Menu left alone while an on-screen keyboard is up. Amazon keeps Home: a key filter never
  sees it (tested on Fire OS 8.1.8.5). It shows only when `tuya_id`, `tuya_key` and
  `tuya_ip` are set. `tools/tuya_keys.py` finds them (QR login with the Smart Life app, then a scan of the Wi-Fi); the
  local key follows the TMDB key's rules. The first answer tells the newer data points (20-24) from the older (1-5).
- The moon button is the sleep timer (`Sleep`): 30/60/90 min, an exact alarm wakes `HomeReceiver`, which presses
  Sleep (then Power if still awake) through the local ADB. The kid profile (`Prefs.kidMode`, a 4-digit code) shows
  only its own shelf (`kidShelf`), no picks, drawer or All Apps; the gear asks for the code. `PhotosActivity` is the
  album full screen, from the photo card (↓ from the drawer's buttons, then OK).
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons) wins over those first values.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL and
  Vestel TVs, the service is on only in Free My TV mode, and *Original* takes it out of the enabled accessibility
  services. The system waits for the service on every key, so it lives alone in the `:keys` process: what it reads is
  in `remote-keys.txt` (`Prefs.keyApp`), never in SharedPreferences, and `onKeyEvent` does no heavy work.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.
- LG and Samsung (`web/`): plain ES5 JavaScript and CSS without variables, grid or backdrop-filter, since older TVs
  (webOS 3 and 4, Tizen 3) run a Chromium from 2015 to 2017; only transform and opacity animate. `web/tv.js` is the
  TV's side: on LG, Luna (`listLaunchPoints`, `launch`, the inputs); on Samsung, `tizen.application` (`getAppsInfo`,
  `launch`), the `VIDEOSOURCE` property and `tizen.tvwindow.setSource` for the inputs, then Samsung's viewer
  (`org.tizen.tv-viewer`) to show them. `web/home.js` follows `HomeView`'s layout and behavior, so a design change on
  Android belongs there too. Text: `tr(pt, en, es)`, with the German and Russian in `web/strings.js` (keyed by the English). Built without a city (`--generic`), `web/zones.js`
  (`tools/make_zones.py`, from the tz database) turns the TV's time zone into the city, the currency and the region.
  `tools/webapp.py` is what both builds share. Preview: `python3 webos/build.py --preview` (or `tizen/build.py`), then
  open `out/webos/index.html` in a browser (a stand-in lists sample apps).

## Always

- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`).
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
