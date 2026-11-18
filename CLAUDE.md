# CLAUDE.md — Jellyfin Offline

Personal fork of `jellyfin/jellyfin-androidtv` that adds offline downloads and playback for a
RayNeo Pocket TV (Google TV, 64 GB internal + SD card). The owner speaks Spanish: talk to them in
Spanish; code, comments and commits stay in English like upstream.

User-facing docs: [.github/README.md](.github/README.md) (shown on GitHub) and
[CHANGELOG.md](CHANGELOG.md) (release notes).

## Golden rule: keep the fork easy to rebase

- All fork code lives in `app/src/main/java/org/jellyfin/androidtv/offline/`.
- Upstream files only get small hooks, marked with an `// Offline fork` comment. Current hooks:
  - `app/build.gradle.kts` — `applicationId = org.jellyfin.androidtv.offline`, `UPDATE_REPO` build
    config field, APK name `jellyfin-offline-v…`
  - `app/src/main/AndroidManifest.xml` — offline activities, `dataSync` foreground service,
    FileProvider, permissions
  - `di/KoinInitializer.kt` — registers `offlineModule`
  - `ui/itemdetail/FullDetailsFragment.java` — adds the download button
  - `util/KeyProcessor.java` — download entries in the card long press menu
  - `ui/shared/toolbar/MainToolbar.kt`, `StartupToolbar.kt` — Downloads icon
  - `ui/startup/StartupActivity.kt` — opens Downloads when offline; only cancels the Leanback
    channel worker (upstream `cancelAllWork()` would kill downloads)
  - `integration/LeanbackChannelWorker.kt` — `PERIODIC_UPDATE_REQUEST_NAME` made public
  - `ui/browsing/MainActivity.kt` — `onOfflineMainResume()` (sync, resume queue, update check)
  - `res/values/strings.xml` — app name "Jellyfin Offline"
- Strings go in `res/values/strings_offline.xml` (English) + `res/values-es/strings_offline.xml`.
- Prefer calling into Kotlin from the Java hooks with one or two lines.

## Architecture (`offline/`)

| Path | Role |
|---|---|
| `model/OfflineItem.kt` | `@Serializable` metadata per download (`meta.json`), `DownloadQuality` presets |
| `data/OfflineStorage.kt` | One folder per item on the selected volume; scans **all** volumes when reading |
| `data/StorageSelector.kt` | Pure location choice: Auto = removable first; a chosen volume never falls back |
| `data/DownloadRepository.kt` | `StateFlow` of items, enqueue (single / season / range), space checks, playback position |
| `data/OfflineAuthStore.kt` | Authorization header per server so the worker runs without UI |
| `download/DownloadWorker.kt` | Single sequential queue (unique work `offline-download-queue`), foreground `dataSync` |
| `download/OfflineSyncWorker.kt` | Pushes offline watch progress via `itemsApi.updateItemUserData` |
| `player/OfflinePlayerActivity.kt` | Media3 `PlayerView`, FFmpeg audio, sidecar subtitles, resume, next episode |
| `ui/OfflineActivity.kt`, `DownloadsScreen.kt` | Compose downloads library + storage picker dialog |
| `ui/OfflineDownloadMenus.kt` | All download popups (quality, season, scope, range), shared by button and card menu |
| `ui/OfflineDetailsButton.kt` | Details screen button (+ long press = until season end on episodes) |
| `ui/OfflineItemMenu.kt` | Entries for the card long press menu |
| `update/UpdateChecker.kt`, `UpdateDialogs.kt` | GitHub releases updater (`version.txt` + `jellyfin-offline.apk`) |

### Gotchas learned the hard way
- **Transcoded downloads must be MPEG-TS** (`/Videos/{id}/stream.ts?static=false…`). Progressive
  MKV/MP4 from Jellyfin have no duration/seek index → no seeking, no resume.
- Original downloads use `/Items/{id}/Download` and resume with `Range`; transcodes restart.
- Files written by `adb shell` into `Android/data/<pkg>` are not readable by the app (FUSE);
  seed test data with `run-as` on the debug build instead.
- `AlertDialog` can't show a message and a list at once — put warnings in the title.
- The debug build is a different package (`.offline.debug`): it never auto-prompts updates.
- `getExternalFilesDirs()` lists portable SD/USB volumes only; adoptable ("internal") SD cards
  are merged with internal storage.
- Season episode counts come from `childCount` (`ItemFields.CHILD_COUNT` on `getSeasons`).

## Branches and remotes

- `origin` = `gnbazay/jellyfin-androidtv-offline`, `upstream` = `jellyfin/jellyfin-androidtv`.
- Work is based on **upstream stable tags**, never on `upstream/master` (dev code).
- Update to a new upstream release: `git fetch upstream --tags && git merge v0.21.0`, fix hooks,
  build, test, release `v0.21.0-offline.1`.

## Build and test locally

- JDK 21 is required (Gradle toolchain): `~/.jdks/jdk-21*` is registered in
  `~/.gradle/gradle.properties`. Android SDK at `~/Library/Android/sdk` (`local.properties`).
- Build: `JAVA_HOME=$(ls -d ~/.jdks/jdk-21*/Contents/Home) ./gradlew :app:assembleDebug`
- Unit tests (kotest): `./gradlew :app:testDebugUnitTest` — fork tests in `app/src/test/kotlin/offline/`.
- `./gradlew detekt` reports many upstream style issues; it doesn't fail the build.
- Emulator: AVD `jftv` (Android TV 14, arm64), start headless:
  `emulator -avd jftv -no-window -no-audio -no-snapshot`. Virtual portable SD:
  `adb shell sm set-virtual-disk true` then `adb shell sm partition <disk> public`;
  unmount/mount with `adb shell sm unmount|mount public:<id>`.
- Test server: Docker container `jftest` (`docker start jftest`, port 8096, emulator reaches it at
  `http://10.0.2.2:8096`), libraries "Peliculas" and "Series" with ffmpeg-generated media. Sign in
  with Quick Connect: read the code on screen and authorize it via `POST /QuickConnect/Authorize`.
- Open any item directly: `adb shell am start -n org.jellyfin.androidtv.offline.debug/org.jellyfin.androidtv.ui.startup.StartupActivity --es ItemId <id>`.
- Long press on the remote: `adb shell input keyevent --longpress DPAD_CENTER`.

## Release

1. Add the version on top of `CHANGELOG.md` (`## [v0.20.0-offline.N] - YYYY-MM-DD`, Spanish,
   user oriented). The release workflow uses that section as release notes and the app shows it in
   the update dialog.
2. Commit, then tag and push: `git tag v0.20.0-offline.N && git push origin v0.20.0-offline.N`.
3. `.github/workflows/offline-release.yaml` builds `assembleRelease`, signs it and publishes
   `jellyfin-offline.apk`, the versioned APK and `version.txt` (`v<name>=<code>`).

- `N` must be 1–98 and increase: `versionCode` = `MAJOR*1000000 + MINOR*10000 + PATCH*100 + N`.
- Signing keystore lives **outside the repo** in `../keystore/` (`jellyfin-offline.jks` +
  `keystore.properties`); the same values are the repo secrets `KEYSTORE`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`. Never commit it; without it updates can't be installed over the
  existing app (and uninstalling deletes all downloads).
- Upstream workflows `App / Publish`, `Repo / *` are disabled on the fork.
