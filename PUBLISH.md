# Publishing Guide - Favie X Tracker

This project ships an **Android APK** built with Capacitor. It is not an
Electron desktop app: the `electron/`, `public/` and Flask-backend fragments
that earlier versions of this document described were never committed and are
not part of the build. `npm run build` produces a web bundle, not an installer.

## What you get

| Command | Output | Use |
| --- | --- | --- |
| `npm run apk` | `android/app/build/outputs/apk/debug/app-debug.apk` | Local testing, sideloading |
| `npm run apk:release` | `.../apk/release/app-release.apk` (signed) | Distribution |
| `npm run apk:publish` | same as above, plus tests and a signature check | Cutting a release |

`npm run apk:publish` is the one to use for a release. It runs the config and
vision-parity tests first, refuses to continue if signing is not configured, and
verifies the signature afterwards.

## Prerequisites

- **Node.js 20+**
- **JDK 21** — Capacitor 7 requires it
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.0`

```bash
npm install
```

`android/local.properties` is gitignored, so a fresh checkout has no SDK path.
Write `sdk.dir=/path/to/android-sdk` into it before running gradle.

## One-time: create a signing key

A release APK must be signed to be installable, and Play requires every update
to use the same key. Create it once and keep it safe — losing it means you can
never update the app under the same identity.

```bash
keytool -genkeypair -v \
  -keystore android/favie-release.jks \
  -alias favie \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Favie X Tracker, O=Favie, C=US"

cp android/keystore.properties.example android/keystore.properties
# then fill in the passwords you just chose
```

Both `android/keystore.properties` and `*.jks` are gitignored. Do not commit
either one, and do not paste their contents into an issue or a chat.

Without `keystore.properties` the release build still succeeds but emits
`app-release-unsigned.apk`, which stores and devices will reject.
`npm run apk:publish` fails early instead of letting you ship that.

## Build a release

```bash
npm run apk:publish
```

It ends by printing the signed APK path and the signer certificate, so you can
confirm the artifact is the one you expect.

## Distribute via GitHub Releases

The simplest free option, and a good fit for a sideloaded app.

```bash
git tag -a v1.0.0 -m "Favie X Tracker v1.0.0"
git push origin v1.0.0

gh release create v1.0.0 \
  --title "Favie X Tracker v1.0.0" \
  --notes "On-device screen capture, object detection and cup tracking." \
  android/app/build/outputs/apk/release/app-release.apk
```

Then point people at
`https://github.com/favie1963-lgtm/Favie-X-Tracker-/releases`.

Users install it by enabling "install unknown apps" for their browser or file
manager and opening the APK. Because it is sideloaded, Play Protect may warn
that the app is from an unknown developer.

## Distribute via Google Play

1. Create a Google Play Developer account (one-off USD 25).
2. In Play Console, create the app and upload the **signed** release APK
   (or an AAB — see below).
3. Complete the Data safety form. This app captures the screen and stores
   session history on-device; it has no server component and uploads nothing.
   Declare the capture behaviour accurately.
4. Complete the content rating questionnaire.
5. Roll out to an internal testing track first, then production.

### If Play asks for an AAB

Play prefers an Android App Bundle over an APK for new apps. The project is
already set up for it:

```bash
npm run build:android && cd android && ./gradlew bundleRelease
```

The bundle lands in `android/app/build/outputs/bundle/release/`. It is signed
with the same `keystore.properties` when that file is present.

### Play submission notes

- The app requests `FOREGROUND_SERVICE_MEDIA_PROJECTION` and shows a persistent
  notification while capturing. Play scrutinises screen-capture apps, so the
  store listing must make the purpose obvious.
- Play requires a privacy policy URL for apps that capture the screen. Even
  though everything is on-device, provide one that says so.
- Set `versionCode` and `versionName` in `android/app/build.gradle` before each
  upload. `versionCode` must strictly increase.

## Versioning

Both values live in `android/app/build.gradle`:

```gradle
defaultConfig {
    versionCode 1
    versionName "1.0"
}
```

Bump `versionCode` for every Play upload. The in-app footer version comes from
`package.json`, so keep the two in step.

## Verifying an artifact before you ship it

```bash
export ANDROID_HOME=/path/to/android-sdk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  android/app/build/outputs/apk/release/app-release.apk
```

Check that the signer DN matches your key. `npm run apk:publish` runs this for
you, but it is worth knowing how to do it by hand.

## Continuous integration

`.github/workflows/webpack.yml` runs the vision-parity and config tests and the
web build on every push and pull request to `main`. It does not build an APK or
hold signing keys — release artifacts are built locally, where the keystore
lives.
