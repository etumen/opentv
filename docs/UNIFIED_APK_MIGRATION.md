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
- Restored mobile Live and VOD swipe gestures/brightness/volume controls on phone routes, while preserving TV d-pad player handling.
- Restored mobile VOD preference for Turkish audio when an identified Turkish track exists, Turkish-caption selection for series, local-file playback and safe resume-position save on exit.
- Shared layout class now reaches nested navigation routes, not just the mobile home tab.
- Phone-only Live screen now has touch channel list, category/provider selector, search, favourites,
  recent channels, sorting and long-press channel actions; TV list, keyboard and navigation remain on their
  original code branch. Advanced older-mobile My Categories filtering still requires a separate parity review.
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
- After functional parity fixes and the phone Live catalogue port: `:app:compileDebugKotlin`,
  `:app:assembleDebug`, and `:app:testDebugUnitTest` PASS (95 tests, 0 failures, including caption language mapping).
- Room v13 -> v14 schema comparison: no prior entities removed or changed; only `downloads` added.
- Earlier unified APK was installed on Mi Box and S25 Ultra on 2026-11-03 and both activities opened;
  the latest Phone Live APK has NOT been installed. In this check Mi Box ADB was offline, and
  S25 Ultra had ChatGPT foreground, so no disruptive screen navigation was attempted.
  End-to-end paging/language/audio/DB-upgrade checks remain outstanding.

## Verification follow-up (2026-11-03)
- Mi Box: film Aksiyon provider pages 2-4 and series Aksiyon pages 2-9 loaded; pagination verified by on-device logs.
- The first movie-screen composition showed "Film yok" before catalogue loading began. Fixed initial
  loading state; real-device confirmation of this revision still pending.
- A movie detail contained both "Türkçe Dublaj" and "Türkçe Altyazılı" as provider category
  names. These are now labeled as *source claims* in detail pages, not verified playback languages.
- Android Media3 reports audio/text language only after track metadata is available during playback.
  In the sampled Mi Box stream the displayed audio language could not yet be verified; do not
  assert the stream has Turkish audio or subtitle support without usable track metadata.
- :app:assembleDebug and :app:testDebugUnitTest PASS (99 tests, 0 failures).

## Release blockers — do not merge into main until resolved
1. Existing private mobile-package data (`app.opentvmobile`) cannot be transferred by Android
   to the unified `app.opentv` package automatically. The new unified download feature works from
   its own additive database table; legacy download files require a separately authorized import.
2. Confirm remaining mobile-specific browsing, Live list/long-press, sorting, player gestures,
   search, series, Drama, captions and offline playback on real devices. The older mobile's
   'My Categories' feature and country-preserving category grouping are not yet ported;
   do not replace the working TV list wholesale.
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
