# SnapText Android releases

## One-time signing setup

GitHub Actions must sign every public APK with the same Android signing key used by the version already installed on users' phones. If the signing key is different, Android will reject the update and users would have to uninstall first (which can erase local app data).

In the Android repository, open **Settings → Secrets and variables → Actions** and add these repository secrets:

- `SNAPTEXT_KEYSTORE_BASE64`: base64-encoded `my-upload-key.jks` release keystore
- `SNAPTEXT_STORE_PASSWORD`: keystore password
- `SNAPTEXT_KEY_PASSWORD`: key password

The configured key alias in `app/build.gradle.kts` is `upload`. Use the original release keystore and passwords that match that alias. Do not commit the keystore or passwords to GitHub.

To encode a keystore file locally:
- macOS/Linux: `base64 -w 0 my-upload-key.jks` (on macOS, use `base64 my-upload-key.jks | tr -d '\n'`)
- PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('my-upload-key.jks'))`

Do not generate a replacement key if the installed app was signed with another key. If the app currently installed on your phone is a debug build, a release-signed APK will not update it in place unless the installed build was signed with the matching release key. Confirm the signing key before publishing the first release.

## Publish a release

1. Merge the app changes into `main`.
2. Create and push a new version tag, such as `v1.2.0`, or run **Actions → Publish SnapText Android release → Run workflow** and enter `1.2.0`.
3. The workflow builds and tests the app, signs the release APK, and attaches `SnapText-1.2.0.apk` to a GitHub Release.
4. The website reads the latest published GitHub release automatically. No Supabase upload or Vercel environment-variable edit is needed for each release.

Use increasing semantic versions (major.minor.patch). The workflow computes Android's version code from the version as major × 1,000,000 + minor × 1,000 + patch, so the version code must always increase. Keep minor and patch below 1000.
