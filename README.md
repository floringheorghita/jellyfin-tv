# Nexus Cinema

Jellyfin for Android TV and Fire TV, styled to match the Nexus dashboard.

A TV build of the official [Jellyfin Android app](https://github.com/jellyfin/jellyfin-android)
for Android TV and Fire TV. Like the LG webOS app, it shows the server's **web interface**
(so Custom CSS skins such as Nexus, Media Bar and Home Screen Sections all appear) in the
**TV layout** driven by the remote, and hands video to the app's native ExoPlayer for
proper codec support.

## Changes from upstream
- Separate app ID (`uk.tonbridgeit.jellyfintv`), so it installs alongside the official apps
- Appears on the Android TV / Fire TV launcher with a Nexus banner; no touchscreen required
- Web interface defaults to the TV layout (remote / D-pad navigation)
- Web view takes focus on load so the remote works straight away
- Battery-optimisation prompt removed
- Built as the "libre" flavour (no Google Play services, which Fire TV doesn't have)

## Building
GitHub Actions builds a signed APK on every push to `main` and publishes it to the
`latest` release. Run it by hand from the Actions tab with "Run workflow".

Signing uses a keystore stored in the repo's Actions secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`), not in the code. Keep using the same key: Android only installs an
update over the existing app when it's signed with the same key.

## Licence
GPL-2.0, as upstream (see LICENSE.md).
