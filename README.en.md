# GhostLock HONOR

[简体中文](README.md) | English

A derivative fork of [ghostlock-app](https://github.com/YuKongA/ghostlock-app) for restoring temporary root on the HONOR 400. It provides standalone and Shizuku-assisted recovery, initialization, operation history, and manual recovery-marker reset.

App name: `GhostLock HONOR`. Package name: `dev.ghostlock.honor`.

Based on upstream commit `437b3b257462e17d395cfd2cd8fb81536d6c6358`. Native sources are in `native/`, retaining the synchronized shutdown fix. The Android UI and recovery coordination logic are in `app/`. See [NOTICE](NOTICE) for attribution and licensing.

## Screenshots

| Root status and recovery options | Standalone recovery setup |
| --- | --- |
| <img src="docs/images/recovery.png" alt="Recovery screen showing root ready and standalone recovery enabled" width="300"> | <img src="docs/images/settings.png" alt="Settings screen with standalone recovery initialization and authorization controls" width="300"> |

Captured on a real device with the Chinese UI. The system status bar and gesture navigation area have been cropped out. The images contain no accounts, unique device identifiers, or operation logs. The displayed state reflects the device only at the time of capture.

## Supported configuration

- HONOR 400 international model / DNY-NX9
- `DNY-N39 10.0.0.213(C636E4R3P2)`
- Kernel `5.15.180-android13-8-00018-g9b2308ac0ad6-ab14563692`
- Official KernelSU Manager `v3.3.1`

### Regional differences and compatibility limits

HONOR 400 hardware differs across markets. The product name alone does not establish compatibility:

| Specification | Mainland China | Singapore | United Kingdom |
| --- | --- | --- | --- |
| Processor | Snapdragon 7 Gen 4 | Snapdragon 7 Gen 3 | Snapdragon 7 Gen 3 |
| Typical battery capacity | 7200mAh | 6000mAh | 5300mAh |
| Wired fast charging | 80W | 80W | 66W |

Specification sources: [HONOR mainland China launch announcement](https://www.honor.com/cn/news/honor-400-launch/), [Singapore specifications](https://www.honor.com/sg/phones/honor-400/spec/), [Singapore product page](https://www.honor.com/sg/phones/honor-400/), and [UK product page](https://www.honor.com/uk/phones/honor-400/). These markets are listed only to compare specifications; this project has not been validated on every market variant shown.

The mainland China model is [DNN-AN00](https://developer.honor.com/cn/docs/game_center/guides/jieruzhinan/jixing); international models include [DNY-NX9](https://www.honor.com/uk/support/phones/honor-400/). **Only the international DNY-NX9 has been validated, and only with the exact firmware, full kernel version, and KernelSU Manager combination listed above. This does not establish support for the mainland China DNN-AN00, other regional variants, other models, or other firmware and kernel versions.** Even for DNY-NX9, compatibility cannot be assumed across all regional firmware builds. The international model identifier alone does not identify the specific country of sale.

Regional variants and system updates may change the kernel, drivers, and boot images. Other combinations require separate adaptation and validation; existing configurations or images cannot simply be reused.

Device configuration and pinned hashes are in `config/device.json`. Other firmware versions are outside the supported scope. Root is temporary and is lost after a reboot; native recovery may fail or cause a reboot. This project does not flash boot partitions, unlock the bootloader, or perform automatic recovery.

## Build

Requires Python 3.10+, Java 21, Android SDK Platform 35, Build Tools, and Android NDK r29.

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk-r29
python3 tools/build.py --enable-recovery
```

The build compiles native components, verifies dependencies, runs host tests, and produces `build/GhostLock-HONOR-1.0.0.apk`. It uses a locally generated debug key by default. Build outputs, keys, and APKs are excluded from Git. Omit `--enable-recovery` to build a check-only version; add `--offline` when dependencies are already cached.

To sign with your own key:

```sh
python3 tools/build.py --enable-recovery \
  --keystore /path/to/release.keystore \
  --key-alias release \
  --password-file /path/to/password-file
```

Native host tests:

```sh
make -C native native-host-tests
```

## Usage

1. Start Shizuku's shell service, connect and authorize this app, then check and restore root.
2. Grant this app root access in KernelSU, then tap “启用独立恢复” (Enable standalone recovery) to complete initial setup.
3. After the next boot, you can use standalone recovery. If recovery fails and the device reboots, you can switch to Shizuku.
4. When needed, use “强制清除恢复标记” (Force clear recovery markers) in Settings. This requires neither root nor an additional verification step. Initialization and authorizations are retained, and original logs are archived. Clearing markers does not automatically run recovery.

Operation history supports deleting individual entries or clearing all entries. This app has its own package name; initialization and authorizations from other apps are not migrated automatically.

## On-device validation of 1.0.0

On 2026-10-29, the released `dev.ghostlock.honor` 1.0.0 APK completed the following flows with the system, kernel, and manager versions listed above:

- Initial setup succeeded, and initialization credentials persisted across reboots.
- After a normal reboot and confirmation that root was lost, Shizuku-assisted root recovery succeeded. In this run, Shizuku was started through USB debugging from a computer.
- After another normal reboot and confirmation that root was lost, standalone recovery succeeded. Shizuku was not running, and wireless debugging was disabled. USB was used only to install the app, operate the UI, and collect validation results; the app itself performed recovery.
- Both recovery paths were verified by an actual `su` call returning UID 0. Standalone recovery also passed this app's root-authorization check. SELinux remained Enforcing, boot partition hashes were unchanged, and no unexpected reboot occurred during recovery.

Each recovery path succeeded once in this validation round. This is insufficient to establish stability, and **the previously observed intermittent reboot issue cannot yet be considered resolved**. Root remains temporary and must be restored again after a normal reboot.

See the [v1.0.0 release notes (Chinese)](docs/releases/v1.0.0.md).

## License

Apache-2.0. The MIT license for the Shizuku API is included in `licenses/`. The KernelSU loader is read from the installed official manager and is not distributed in this repository.
