---
name: release-dwt
description: Prepare, validate, and publish DWT – Das Wetter tickt releases to GitHub and Google Play. Use when creating a release, checking release readiness, updating store assets, publishing to Play Console, or troubleshooting release workflows.
---

# Release DWT – Das Wetter tickt

Use `vMAJOR.MINOR.PATCH` tags. The GitHub release tag is the source of truth for
Android `versionName`; Gradle derives `versionCode` from the semantic version.

## Workflow

1. Read `docs/releasing.md` and inspect the current worktree.
2. Confirm `store-assets/`, `PRIVACY.md`, and `DATA_SOURCES.md` still match the app.
3. Run `scripts/preflight.sh vMAJOR.MINOR.PATCH`.
4. Review release notes and create the tag only when the worktree is clean.
5. Publish a GitHub release for the tag.
6. Verify the `Build release` workflow attaches a signed APK and AAB.
7. For the first Play release, configure the paid app price and complete the
   Play Console requirements manually before uploading the AAB.
8. For later Play updates, run the `Publish Google Play release` workflow or
   enable its guarded release trigger.
9. Verify the Play release status and use the APK for direct GitHub distribution.

Never commit a keystore or signing credentials. Do not create a release if the
DWD publication status is unresolved, the tag is not semantic, tests fail, or
store assets are missing.

## Google Play

The app is paid. Keep the customer-visible Germany price at **EUR 0.99**. Verify
the displayed country price after tax conversion. Set pricing before the first
publication because an app that has been offered for free cannot later be
changed to paid.

The first release must be completed manually in Play Console:

1. Create/select package `net.sibbl.dwt`.
2. Set the app to paid and configure a customer-visible Germany price of EUR 0.99.
3. Complete app access, ads, content rating, target audience, data safety,
   privacy policy, store listing, and Wear OS declarations.
4. Add Wear OS under advanced distribution and upload the AAB to a dedicated
   Wear OS test track.
5. Submit the store and Wear OS declarations for review.
6. For a new personal account, run a closed Wear OS test with at least 12
   opted-in testers for 14 continuous days before requesting production access.

Future AAB uploads can use `.github/workflows/play-publish.yml` after:

1. Creating a Google Cloud service account with Android Publisher API access.
2. Granting that account release permissions for `net.sibbl.dwt` in Play Console.
3. Saving its JSON key as GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`.
4. Setting repository variable `PLAY_PUBLISH_ENABLED=true` only when every new
   GitHub release should automatically publish to the `wear:production` track.

For a controlled update, leave the variable disabled and manually dispatch the
workflow with the desired release tag, Wear OS track, and status.
