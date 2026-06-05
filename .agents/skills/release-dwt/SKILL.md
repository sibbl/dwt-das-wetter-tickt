---
name: release-dwt
description: Prepare, validate, and publish DWT – Das Wetter tickt releases. Use when creating a release, checking release readiness, updating store assets, or troubleshooting the tag-driven GitHub release workflow.
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
7. Use the AAB for Google Play and the APK for direct GitHub distribution.

Never commit a keystore or signing credentials. Do not create a release if the
DWD publication status is unresolved, the tag is not semantic, tests fail, or
store assets are missing.
