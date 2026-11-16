# Agent guide — jellyfin-androidtv (pop fork)

Primary agent guide for this repo; no nested `AGENTS.md` yet. The page roadmap
is in `docs/pop-roadmap.md`.

## What this is

A personal **fork of the official Jellyfin Android TV app**
(github.com/jellyfin/jellyfin-androidtv; Kotlin, Compose with Leanback
leftovers, Media3, Jellyfin Kotlin SDK, Koin) for the pop. Jellyfin server,
watched on a Fire TV. Upstream gives playback, saved users and the user picker;
the fork adds pages fed by the server plugin `jellyfin-plugin-pop-requests`
(My library first) and its own signed releases. It installs **beside** the
official app: application id `com.antomarioni.jellyfintv`, label "Jellyfin Pop".

> **Fork note.** `master` mirrors upstream untouched; all fork work is on
> **`pop`** (default branch). Keep the diff rebase-friendly: fork code lives in
> its own package and upstream files change only at the hooks listed under
> Conventions.

## How to run things

JDK 21 (`.tools-versions`) and the Android SDK (`ANDROID_HOME`); on this Mac
`JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/*.apk, id com.antomarioni.jellyfintv.debug
./gradlew detekt lint test       # upstream's linters and unit tests
adb install -r app/build/outputs/apk/debug/*.apk
adb shell am start -n com.antomarioni.jellyfintv.debug/org.jellyfin.androidtv.ui.startup.StartupActivity

# Release: CI (.github/workflows/pop-release.yaml) builds the signed APK and publishes the GitHub Release
git tag v0.20.0-pop.<N> && git push origin v0.20.0-pop.<N>

# Signed release build by hand (CI passes the same variables from repo secrets)
KEYSTORE_FILE=… KEYSTORE_PASSWORD=… SIGNING_KEY_ALIAS=pop SIGNING_KEY_PASSWORD=… \
  JELLYFIN_VERSION=v0.20.0-pop.<N> ./gradlew :app:assembleRelease

# Take in a new upstream version
git fetch upstream --tags
git checkout master && git merge --ff-only upstream/master && git push origin master
git checkout pop && git rebase master && git push --force-with-lease origin pop
```

Without a TV emulator, a phone AVD works as a 960×540 dp TV:
`adb shell settings put system user_rotation 1`, `adb shell wm size 1080x1920`,
`adb shell wm density 320`. Drive it with `adb shell input keyevent DPAD_DOWN`
(`DPAD_CENTER`, `BACK`…) only: a tap puts the window in touch mode, where focus
is not shown. Sign in with Quick Connect (the user approves the code in
Jellyfin web); never type their password.

On the Fire TV: `adb connect <ip>:5555` then `adb install -r <release.apk>`, or
open the release's APK URL with the Downloader app.

## Quality gate

1. `./gradlew :app:assembleDebug detekt lint test` succeeds (what
   `pop-build.yaml` runs on every push to `pop`). detekt and lint only report:
   upstream itself has findings, so they don't fail the build. Don't add
   avoidable ones, and don't chase upstream's.
2. The page you touched works on a TV-sized screen with the D-pad only,
   against the live server: focus is visible on entry, Back returns to the
   element you left, no key press crashes it while loading.

Upstream's own workflows (`app-*.yaml`) only run for `master`, `release-*` and
pull requests; `app-publish.yaml` never runs here (owner check).

## Conventions

- **Fork code lives in `org.jellyfin.androidtv.pop`** (`app/src/main/java/org/jellyfin/androidtv/pop/`),
  with `res/values*/strings_pop.xml`, `.github/workflows/pop-*.yaml` and
  `buildSrc/.../PopVersion.kt`. Upstream files are touched only at these hooks,
  each marked `Fork (pop)`:
  - `di/KoinInitializer.kt`: loads `popModule` last.
  - `ui/shared/toolbar/MainToolbar.kt`: calls `PopToolbarButtons()`.
  - `auth/store/AuthenticationPreferences.kt`: auto sign-in default is
    `DISABLED` (ask which user on open).
  - `app/build.gradle.kts`: one block appended at the end (application id, app
    name, version code).
  A new hook is one line calling into the pop package; think twice before
  adding one, and list it here.
- **`popModule` is loaded last**, so a definition in it replaces upstream's of
  the same type. That is how the app opens on My library
  (`NavigationRepositoryImpl(PopDestinations.myLibrary)`) without editing
  `AppModule`.
- **Plugin calls go through `PopRequestsApi`**, which uses the SDK's
  `ApiClient`: it sends `Authorization: MediaBrowser … Token="…"` for the
  signed-in user (the server refuses legacy `X-Emby-Token`). No second HTTP
  client, no stored tokens of our own. Models are `@Serializable` data classes
  with defaults, decoded with `ignoreUnknownKeys`.
- **UI is Compose built from upstream's `ui/base` pieces** (`Button`, `Text`,
  `Icon`, `Popover`, `Badge`, `JellyfinTheme`), not Material. Shared fork
  pieces are in `pop/ui` (`PosterCard`, `PickerPill`, `TogglePill`,
  `PopColors`). A page is a `Fragment` whose view is `content { … }`, shows
  `MainToolbar(MainToolbarActiveButton.None)` and provides `LocalPopPage`.
- **Focus rules** (they come from real bugs):
  - Something on the page must hold focus from the first frame, a focusable
    loading placeholder included: upstream's activity throws on a key press
    while the previous screen's removed view is still the focused one.
  - Take focus on explicit events (page entered, content replaced the
    spinner), never "whenever nothing has focus": that steals focus back while
    navigating away. `MyLibraryScreen` + `MyLibraryViewModel.entries` is the
    pattern.
  - A card's focused look comes from `onFocusChanged`, not an interaction
    source (it stays "focused" after leaving the page).
  - Scroll state and the focused key live in the view model: the fragment's
    view is destroyed while another screen is on top.
- **Text** goes in `strings_pop.xml` (English) and `values-es/strings_pop.xml`;
  never in upstream's `strings.xml` (Weblate owns it).
- Kotlin style is upstream's: tabs, 140 columns (`.editorconfig`).
- **Versions.** Tags are `v<upstream version>-pop.<N>`; `N` is the Android
  version code (`getPopVersionCode`), counts fork releases and never resets,
  also when the fork moves to a newer upstream. Tag from `pop` only.
- **Signing.** The keystore is never committed. CI reads the repo secrets
  `KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Losing
  the key means every device must reinstall.

## Where things live

```
app/src/main/java/org/jellyfin/androidtv/
  pop/                         # everything the fork adds
    PopModule.kt               # Koin: fork definitions + overrides
    PopDestinations.kt         # fork screens (next to upstream's Destinations)
    data/                      # PopRequestsApi, plugin models, per-user choices
    library/                   # My library: fragment, view model, screen
    ui/                        # toolbar buttons, PosterCard, pills, colors
  ui/shared/toolbar/           # upstream toolbar (hook)
  ui/navigation/               # upstream Destinations, NavigationRepository
  ui/itemdetail/               # upstream details screen (future "Requested by" hook)
  ui/startup/, auth/           # upstream user picker, saved users, sessions
app/src/main/res/values*/strings_pop.xml
app/build.gradle.kts           # upstream + the fork block at the end
buildSrc/src/main/kotlin/PopVersion.kt
.github/workflows/pop-build.yaml pop-release.yaml
playback/ preference/ design/  # upstream modules, untouched
docs/pop-roadmap.md            # agreed pages and their order
```

## Adding a fork page

My library is the example to copy.

1. Data: models + a method in `pop/data/PopRequestsApi.kt` (shapes come from
   the plugin, see Ripple awareness).
2. `pop/<page>/`: view model (state flow, scroll state, focused key,
   `entries`), screen composable, fragment; register the view model in
   `PopModule.kt` and the destination in `PopDestinations.kt`.
3. Toolbar: a value in `PopPage` and a button in `PopToolbarButtons`.
4. Strings in both `strings_pop.xml` files.
5. Gate (above), then tag a release when it should reach the TV.

## Ripple awareness

- **`jellyfin-plugin-pop-requests`** — github.com/nantomarioni/jellyfin-plugin-pop-requests —
  expected at `../jellyfin-plugin-pop-requests`. This app reads
  `GET /PopRequests/library?requester=&sort=&hideWatched=` as the viewer; the
  shape is `Services/LibraryModel.cs` there and `pop/data/PopLibrary.kt` here
  (camelCase, ids without dashes). Change a field on both sides. Pushing that
  repo restarts Jellyfin: check nobody is watching.
- **Jellyfin server API** (the pop. server runs 12.3): Continue watching is
  `UserItems/Resume` + `Shows/NextUp` through the SDK.
- **GitHub Releases of this repo** are the app's update channel: the tag
  format and the `*-release.apk` asset name are a contract with the (future)
  in-app updater.
- **Upstream** — github.com/jellyfin/jellyfin-androidtv (remote `upstream`).
  A rebase can move the hook sites: re-check each hook listed above.

Open the sibling's guide before declaring a cross-repo change done.

## When stuck

- Page empty or "Could not load…" → `adb logcat | grep "My library"`, then the
  same request in a browser signed in as that user.
- Focus lost or a crash in `FocusFinder` (`parameter must be a descendant of
  this view`) → the focus rules above.
- A hook no longer compiles after a rebase → `git diff master -- <file>` shows
  the fork's line; reapply it at the new spot.
- Install fails on a device → another build signed with a different key has
  the same application id; uninstall it first.
- What to build next → `docs/pop-roadmap.md`.
