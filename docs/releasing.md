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
6. Upload the AAB to the Wear OS track in Google Play Console.

## Google Play

Store listing materials are in `store-assets/`:

- `play-store-icon.png`
- `feature-graphic.png`
- `wear-screenshots/`
- English and German listing copy in `listing/`

Publish `PRIVACY.md` at a public URL and add that URL to the Play Console store
listing. Complete the Play Console data safety and Wear OS declarations based
on the app's optional location access and direct DWD network requests.

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
