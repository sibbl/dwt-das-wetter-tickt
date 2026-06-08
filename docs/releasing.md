# Releasing

GitHub release tags are the source of truth for the app version. Tags must use
`vMAJOR.MINOR.PATCH`, for example `v1.2.3`.

The release workflow builds and attaches:

- `dwt-das-wetter-tickt-MAJOR.MINOR.PATCH.apk` for direct installation
- `dwt-das-wetter-tickt-MAJOR.MINOR.PATCH.aab` for Google Play

## GitHub Secrets

Configure these repository secrets before publishing a release:

- `SIGNING_KEY_BASE64`: Base64-encoded upload keystore
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Encode the keystore with:

```sh
base64 < release.jks | tr -d '\n'
```

## Publish

1. Resolve the DWD publication status documented in `DATA_SOURCES.md`.
2. Ensure `main` is clean and tests pass.
3. Run `.agents/skills/release-dwt/scripts/preflight.sh vMAJOR.MINOR.PATCH`.
4. Create and publish a GitHub release using the next semantic version tag.
5. Wait for the `Build release` workflow to attach the signed APK and AAB.
6. Upload the AAB to the Wear OS track in Google Play Console, or dispatch the
   guarded `Publish Google Play release` workflow after the initial manual release.

## Google Play

DWT is a paid app. Configure the customer-visible Germany price as **EUR 0.99
before the first publication** and verify the displayed price after tax
conversion. Do not publish it as free first; Google Play does not allow an app
that has been offered for free to later become paid.

Store listing materials are in `store-assets/`:

- `play-store-icon.png`
- `feature-graphic.png`
- `phone-screenshots/`
- `wear-screenshots/`
- English and German listing copy in `listing/`

Publish `PRIVACY.md` at a public URL and add that URL to the Play Console store
listing. Complete the Play Console data safety and Wear OS declarations based
on the app's optional location access and direct DWD network requests.

The first AAB must be uploaded manually so package `net.sibbl.dwt`, pricing,
store listing, declarations, and track eligibility exist in Play Console.

For later releases, `.github/workflows/play-publish.yml` downloads the signed AAB
from a GitHub release and uploads it through the Google Play Developer API.
Configure:

- GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`: service-account JSON with release
  permissions for `net.sibbl.dwt`
- GitHub variable `PLAY_PUBLISH_ENABLED=true`: automatically publish every new
  GitHub release to the `wear:production` track

Leave the variable disabled to publish only through manual workflow dispatch.
Manual track values are `wear:internal`, `wear:alpha`, `wear:beta`, and
`wear:production`.

The public privacy policy URL is:

```text
https://github.com/sibbl/dwt-das-wetter-tickt/blob/main/PRIVACY.md
```

For a new personal Play developer account, complete account identity, Android
device, and contact-phone verification before creating the app. Google also
requires a closed test with at least 12 opted-in testers for 14 continuous days
before production access can be requested:

- https://support.google.com/googleplay/android-developer/answer/10841920
- https://support.google.com/googleplay/android-developer/answer/14316361
- https://support.google.com/googleplay/android-developer/answer/14151465

Local debug builds use version `0.0.0-dev`. To verify a release build locally,
set `APP_VERSION_NAME` and all four signing environment variables before running:

```sh
./gradlew testDebugUnitTest assembleRelease bundleRelease
```
