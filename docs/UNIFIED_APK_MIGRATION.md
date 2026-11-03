# OpenTV single-APK migration — development branch

## Goal
One GitHub repository, one Android application/APK and one version number.
Android TV keeps the original remote-controlled TV Compose UI. Phones/tablets
use the mobile touch UI. All devices share one provider/data implementation.

## Implemented (2026-11-03)
- Worktree: `C:\Users\birad\Projects\OpenTV\opentv-unified`.
- Git branch: `feat/unified-single-apk-20261103`, based on TV `main`.
- `UniversalMainScreen` selects TV `MainScreen` for TV, `MobileMainScreen` for touch devices.
- Android device detection reuses existing `MainActivity.isRunningOnTelevision`.
- Mobile: REC TV-style five-tab shell, featured hero, real catalog, dark/red theme.
- Mobile Hero Watch is routed through the shared provider repository and Media3 handoff.
- TV MainScreen and d-pad screen implementation preserved unchanged.
- Brought over the mobile catalog's whole-section selection, per-provider pagination and media-type filter.
- Added native phone search and preserved TV's on-screen d-pad keyboard.
- Added offline download repository, queue receiver, Downloads screen and movie/episode controls with additive TV database migration.
- `shared/opentv-core` checked in as a normal Gradle module rather than a Git submodule;
  based on former core GitHub commit `1dd5ffd722824d4e5473031b05bafa442f24ddeb`.
- Existing TV production package ID `app.opentv` preserved; debug is `app.opentv.debug`.
- Original separate mobile application `app.opentvmobile` and its data left untouched.

## Verified
- `:app:compileDebugKotlin` and `:app:assembleDebug` successful.
- 85 TV app unit tests passed; focused HDFilmCehennemi provider tests passed (12).
- Samsung S25 Ultra: one APK installed as separate `app.opentv.debug` app;
  MobileMainScreen, dark/red theme, five bottom tabs, catalog/film browsing shown.
- Existing `app.opentvmobile.debug` remains installed (no overwrite).
- After functional parity fixes: `:app:assembleDebug` and `:app:testDebugUnitTest` PASS (92 tests, 0 failures).
- Room v13 -> v14 schema comparison: no prior entities removed or changed; only `downloads` added.
- Newly built APK has NOT been installed on TV or phone; device-level migration remains unverified.

## Release blockers — do not merge into main until resolved
1. Existing private mobile-package data (`app.opentvmobile`) cannot be transferred by Android
   to the unified `app.opentv` package automatically. The new unified download feature works from
   its own additive database table; legacy download files require a separately authorized import.
2. Confirm mobile-specific browsing/player gestures and remaining screens retain feature
   parity (series, Drama, live TV, captions, offline playback); the native search code is in place
   but still needs a real-device check.
3. Validate the updated APK on real Mi Box and Toshiba Android TV: d-pad navigation,
   focus restoration, playback, streams, audio, subtitle selection and additive DB upgrade.
   Prior Mi Box PASS was for the earlier unified build, not this code revision.
4. Plan data migration between original mobile package `app.opentvmobile` and release
   unified package `app.opentv`. Android does NOT transfer private app data between IDs.
5. Confirm release signing, version-code upgrade path and one-device installation update.
6. Preserve user-approved visual design. No browser/Custom Tabs playback fallback.

## Workflow
Only this feature branch should change during migration. The current production
`main` branches and installed TV/mobile release apps must remain unchanged.
