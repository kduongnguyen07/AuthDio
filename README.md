# AuthDio

Offline-first Android Hi-Fi audio downloader & Media3 player (`com.example.mdownload` / HiFiDeck).

## Build

```powershell
# 64-bit ARM (Android 15+ / arm64-v8a)
.\gradlew.bat :app:assembleArm64Debug

# Universal multi-ABI
.\gradlew.bat :app:assembleUniversalDebug

# Unit tests
.\gradlew.bat :app:testArm64DebugUnitTest
```
