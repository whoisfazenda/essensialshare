# Verifying the downloads

EShare is open source, and the release files are built from this repository. To be sure that what you download is what was published, compare its SHA-256 and look at the VirusTotal report.

## EShare 1.1.0

| File | SHA-256 | VirusTotal |
| --- | --- | --- |
| `EShare.apk` (Android 14+) | `be66e6405f86566b061c219810307dfea3a0c91884be4be2fc71fb133b8dbd60` | report pending |
| `EShare-Setup.exe` (Windows) | `54738334441b460545a9b3cabf7b3744559091fe14aac9da26e2bd9f6435a406` | report pending |

Check a file on Windows:

```
certutil -hashfile EShare.apk SHA256
certutil -hashfile EShare-Setup.exe SHA256
```

## Signing

The APK is signed with the same key as EShare 1.0.0 (then called Essential Share), so it updates the installed app in place. Its certificate SHA-256 is:

```
61a3aa76cc0129dcb66343e5e85c0ceb5edd3f779bbe144290acfdc7fc03467c
```

Check it with `apksigner verify --print-certs EShare.apk` from the Android SDK build tools.

The Windows installer is not code-signed (no certificate), so SmartScreen may warn. It is an IExpress self-extractor that runs `installer/install.ps1`, which you can read in this repository.

## Permissions the Android app asks for, and why

| Permission | Why |
| --- | --- |
| Internet, network and Wi-Fi state, multicast | Find devices on the local network and move files over it |
| Nearby devices, Bluetooth scan and advertise | Touch to share: find the phone you touch. Files never go over Bluetooth |
| Display over other apps | Show the touch animation when the app is closed. Optional |
| Notifications, foreground service | Stay reachable in the background and tell you about incoming files |
| Install unknown apps | Open an APK you received from another device. Optional |
| Vibrate | The "knock" when two phones touch |
| Receive boot completed | Start the quiet background service again after a restart |
| Battery optimisation exemption | Keep receiving with the screen off. Optional |

The apps have no analytics, no accounts and no server of their own. Everything is peer to peer.
