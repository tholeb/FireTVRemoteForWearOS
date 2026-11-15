## Releases (GitHub Actions)

`.github/workflows/release.yml` runs on every **published GitHub release** (or by hand from
the Actions tab). It runs the tests and lint, builds the minified release APK with
`versionName` = the tag (a leading `v` is dropped) and `versionCode` = the run number, and
attaches `FireTVRemote-wear-<tag>.apk` plus `SHA256SUMS.txt` to the release.

### Creating a signing key (`.jks`)

A `.jks` is a Java keystore that holds the key your APKs are signed with. Android only
installs an update if it's signed with the **same key** as the installed app, so create it
once and keep it safe: lose it and you'll have to uninstall the app before installing a
newly signed one. `keytool` comes with every JDK, including the one bundled with Android
Studio (`jbr/bin/keytool`).

Linux / WSL / macOS:

```bash
keytool -genkeypair -v -keystore release.jks -alias firetvremote -keyalg RSA -keysize 4096 -validity 10000
```

Windows PowerShell (Android Studio's JDK):

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore release.jks -alias firetvremote -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a keystore password and a name; the name can be anything, it's only shown in
the certificate. Then turn the file into a single line for the GitHub secret:

```bash
base64 -w0 release.jks > release.jks.b64
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content release.jks.b64
```

**Never commit `release.jks`** (`*.jks` is in `.gitignore`). Keep a backup in your password
manager, together with its passwords.

Signing: add these repository secrets (*Settings → Secrets and variables → Actions*) to sign
with your own key:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `firetvremote` (the `-alias` above) |
| `RELEASE_KEY_PASSWORD` | key password (same as the keystore password unless you set a different one) |

Without them, the CI build is signed with the runner's throwaway debug key. Android then
refuses to install it over a locally built copy (signature mismatch), so uninstall first, or
set up the secrets once and always use CI builds.

Locally you can use the same variables, with `RELEASE_KEYSTORE_PATH` pointing to the `.jks`
file instead of the base64 secret.