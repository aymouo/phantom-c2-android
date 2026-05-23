# Phantom C2 — Android App Map

## Overview
This is a command-and-control (C2) Android implant that communicates over Discord (WebSocket gateway). It masquerades as "System Services" and provides remote surveillance, data theft, crypto mining, and device control.

---

## Project Structure

```
phantom-c2-android/
├── build.gradle              # Root: declares plugins
├── settings.gradle           # Root project: "OpenAccessSDK"
├── gradle.properties         # JVM args, AndroidX, Kotlin
├── gradlew / gradlew.bat     # Gradle wrapper (8.4)
├── encrypt_miner.py          # XOR encrypts XMRig binary
├── mixer.py                  # APK builder/binder (Python)
├── MAP.md                    # THIS FILE
├── ui-templates/             # UI disguise templates
│   ├── vpn_ui.kt             # SecureVPN theme
│   ├── movie_ui.kt           # StreamFlix theme
│   ├── weather_ui.kt         # Weather app theme
│   ├── settings_ui.kt        # Settings app theme
│   ├── filemanager_ui.kt     # File manager theme
│   └── music_ui.kt           # SoundWave theme
└── app/
    ├── build.gradle           # App: compileSdk34, minSdk21
    ├── proguard-rules.pro
    ├── proguard-debug.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── .gitkeep
        │   ├── data.bin              # XOR'd XMRig binary
        │   └── libxmrig.so.gz        # GZipped XMRig (fallback)
        ├── jniLibs/arm64-v8a/
        │   ├── libnativebridge.so
        │   └── librust_agent.so
        ├── res/
        │   ├── drawable/             # 8 XML drawables (VPN UI)
        │   ├── mipmap-*/             # Launcher icons
        │   ├── values/
        │   │   ├── colors.xml
        │   │   ├── ic_launcher_background.xml
        │   │   ├── strings.xml
        │   │   └── themes.xml
        │   └── xml/
        │       ├── accessibility_service_config.xml
        │       ├── device_admin.xml
        │       └── file_paths.xml
        └── java/
            ├── com/openaccess/sdk/
            │   ├── MainActivity.kt             # Launcher: starts service, finishes
            │   ├── OpenAccessApp.kt             # Application: crash handler, init
            │   ├── ScreenCaptureActivity.kt     # Requests screen capture permission
            │   ├── service/
            │   │   ├── SystemNetworkService.kt  # MAIN SERVICE (2351 lines) - Discord C2, camera, mic, location, shell, etc.
            │   │   ├── AccessibilityHelper.kt   # Accessibility service: keylog, app tracking, screenshots
            │   │   ├── InputHelper.kt           # Gesture injection, PIN/pattern grab, auto-install
            │   │   ├── DisplayCapture.kt        # Screen capture (MediaProjection, screencap, root)
            │   │   ├── AdminReceiver.kt          # Device admin: lock, wipe, camera disable
            │   │   └── NotifService.kt           # Notification listener: capture all notifications
            │   └── update/
            │       ├── ConfigManager.kt          # Remote config via Discord (feature toggles)
            │       └── UpdateManager.kt          # APK download, root install, accessibility install
            └── com/google/system/
                ├── DiscordGatewayClient.kt   # WebSocket/REST Discord bot client (1194 lines)
                ├── DiscordConfig.kt          # Bot token, gateway URL (XOR-obfuscated)
                ├── StealthLayer.kt           # Emulator/root/debug detection, process spoofing
                ├── AdvancedFeatures.kt       # WiFi passwords, network scan, system info
                ├── AnimatedGifEncoder.kt     # GIF encoder for screen recordings
                ├── CryptoLayer.kt            # AES-256-GCM encryption, PBKDF2, HMAC
                ├── GrabberModule.kt          # Data theft: browsers, messengers, wallets, tokens, files
                ├── LiveStreamEncoder.kt      # H.264 screen stream encoder (MediaCodec)
                ├── MiningPoolManager.kt      # XMRig pool config, auto-failover, latency test
                ├── MiningMonitor.kt          # Mining stats: hashrate, shares, temp, battery
                ├── RealMiner.kt              # XMRig binary extraction, execution, process management
                └── plugins/
                    ├── PluginInterface.kt    # Plugin interface
                    ├── PluginManager.kt      # Plugin loader & config
                    └── MinerPlugin.kt        # Crypto miner plugin wrapper
```

---

## File-by-File Breakdown

### App Entry Points
| File | Lines | Purpose |
|------|-------|---------|
| `MainActivity.kt` | 73 | Starts `SystemNetworkService`, finishes immediately (no UI) |
| `OpenAccessApp.kt` | 68 | Application class: calls `AppInitializer`, crash handler saves reports |
| `ScreenCaptureActivity.kt` | 33 | Requests `MediaProjection` screen capture permission |

### C2 / Communication
| File | Lines | Purpose |
|------|-------|---------|
| `DiscordGatewayClient.kt` | 1194 | WebSocket gateway + REST client: heartbeat, command dispatch, file upload |
| `DiscordConfig.kt` | 34 | XOR-obfuscated bot token, gateway URL, channel prefix |
| `ConfigManager.kt` | 161 | Feature toggle via remote JSON config from Discord |

### SystemNetworkService (Main Service)
| File | Lines | Purpose |
|------|-------|---------|
| `SystemNetworkService.kt` | 2351 | Central service: connects to Discord, dispatches 40+ commands (camera, mic, location, shell, contacts, SMS, screen recording, etc.) |

### Surveillance / Data Theft
| File | Lines | Purpose |
|------|-------|---------|
| `GrabberModule.kt` | 507 | Steals browser data, messengers, wallets, tokens, clipboard, content providers, logs |
| `AccessibilityHelper.kt` | 432 | Keylogger, app usage tracking, screenshot (Android 14+), black overlay |
| `InputHelper.kt` | 369 | PIN/pattern grab, auto-click permissions, auto-install APK, gesture injection |
| `DisplayCapture.kt` | 415 | Screen capture via MediaProjection, screencap, su root, AccessibilityService |
| `LiveStreamEncoder.kt` | 291 | H.264 screen stream encoding via MediaCodec, sends to bot HTTP endpoint |
| `NotifService.kt` | 53 | Captures all device notifications |
| `AdvancedFeatures.kt` | 340 | WiFi passwords (root), network scan, system properties, battery, storage |

### Crypto Mining
| File | Lines | Purpose |
|------|-------|---------|
| `RealMiner.kt` | 337 | Extracts XMRig from assets, runs it, process management, battery throttling |
| `MiningPoolManager.kt` | 201 | Pool config, latency test, auto-failover, XMRig JSON config generation |
| `MiningMonitor.kt` | 212 | Mining stats: hashrate parsing, CPU temp, battery, CPU usage, dashboard |
| `MinerPlugin.kt` | 152 | Plugin wrapper for miner controls |
| `PluginManager.kt` | 107 | Plugin system loader |
| `PluginInterface.kt` | 16 | Plugin interface definition |

### Stealth / Anti-Analysis
| File | Lines | Purpose |
|------|-------|---------|
| `StealthLayer.kt` | 204 | Emulator/root/debugger detection, process name spoofing, hide from recents |
| `CryptoLayer.kt` | 143 | AES-256-GCM file/string encryption, PBKDF2, HMAC integrity |

### Device Control
| File | Lines | Purpose |
|------|-------|---------|
| `AdminReceiver.kt` | 89 | Device admin: lock, wipe, disable camera, re-enable on disable |
| `AnimatedGifEncoder.kt` | 222 | GIF encoder for screen recordings |

### Update System
| File | Lines | Purpose |
|------|-------|---------|
| `UpdateManager.kt` | 306 | APK download, root silent install, accessibility auto-click install |

### Build Tools
| File | Lines | Purpose |
|------|-------|---------|
| `mixer.py` | 723 | APK builder: 8 presets, 6 UI templates, APK binding, feature selection |
| `encrypt_miner.py` | 50 | XOR-encrypts XMRig for embedding in assets |

---

## Dependencies (app/build.gradle)
| Dependency | Version |
|------------|---------|
| Kotlin stdlib | 1.9.22 |
| Kotlinx Coroutines | 1.7.3 |
| AndroidX Core KTX | 1.12.0 |
| AndroidX AppCompat | 1.6.1 |
| AndroidX Activity KTX | 1.8.2 |
| OkHttp3 | 4.12.0 |
| Gson | 2.10.1 |

## Build Config
- **compileSdk**: 34
- **minSdk**: 21
- **targetSdk**: 34
- **Gradle**: 8.4
- **AGP**: 8.2.2
- **Kotlin**: 1.9.22
