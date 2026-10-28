# Cellprobe

Android app for background monitoring of hostile network filtering
(whitelist mode on mobile networks, targeted proxy blocking, etc.).
It periodically probes several sets of resources, classifies the current
network mode and suggests a suitable circumvention tool.

Status: early development, nothing usable yet.

## Building

Requirements: JDK 17 or newer (Android Studio's bundled JBR works),
Android SDK with platform 37.

```
./gradlew assembleFdroidDebug
./gradlew :core:test
```

## License

[GNU General Public License v3.0 or later](LICENSE).
