# Releasing

Releases are cut by pushing a tag. GitHub Actions does the rest.

```bash
git tag v0.1.0
git push origin v0.1.0
```

`.github/workflows/release.yml` runs the tests, assembles the release APK and attaches it to a
GitHub release.

## Signing

**Release builds are signed with the project's own upload key**, held in the repository secrets
and never in this repository. `release.yml` refuses to publish when that key is missing, because
a release signed with a temporary key cannot be updated in place: Android rejects an install
whose signing certificate differs from the installed one
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so the in-app updater silently stops working for every
user who installed the previous build.

That failure is not hypothetical — it is what happened before 0.12.6. Releases were signed with
the CI runner's throwaway debug key, a *different* key on every run, so no build could ever be
installed over another. The one-time cost of moving to a stable key is that installs older than
0.12.6 have to be uninstalled and installed fresh once; users should export their settings
(Settings → Backup) first and restore them afterwards.

### Setting up real signing

1. Generate a keystore, and keep it **out of the repository**:

   ```bash
   keytool -genkeypair -v -keystore upload.jks -storetype PKCS12 -keyalg RSA -keysize 4096 \
     -validity 10000 -alias opentv
   ```

2. Add these repository secrets under **Settings → Secrets and variables → Actions**:
   `KEYSTORE_BASE64` (the output of `base64 -w0 upload.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD`. PKCS12 normally uses one password for both, so the last two are usually equal.

3. That is all — `release.yml` and `build.gradle.kts` pick them up. The release step reports
   `signed: yes` instead of `debug-key`.

The fingerprint of the current key is printed by `apksigner verify --print-certs` on any release
APK; check it when you take over, and keep the value somewhere with the backup.

**The keystore must belong to the project, not to one person.** A single maintainer holding
the only copy is the same single point of failure this project exists to avoid — if they go
quiet, nobody can ship an upgrade, and every user is stranded on whatever version they have.
Share it among at least two maintainers, and back it up somewhere that is not one laptop.

Losing the keystore is unrecoverable. There is no reset.

## Version numbers

Bump `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` must increase on
every release; Android uses it, not `versionName`, to decide what counts as an upgrade.
