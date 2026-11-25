# Nova VPN — Third-Party Dependencies & License Review

Review all third-party licenses carefully before commercial or public store distribution, especially copyleft engines (`GPL-2.0` / `GPL-3.0`) when bundling optional native AARs in `app/libs/`.

| Component / Artifact | Group / Source | Purpose | License |
|---|---|---|---|
| Jetpack Compose & Material 3 | `androidx.compose.*` | Native UI framework | Apache-2.0 |
| Navigation Compose | `androidx.navigation:navigation-compose` | Type-safe routing | Apache-2.0 |
| Room Database & KSP | `androidx.room:*` | Local encrypted SQLite storage | Apache-2.0 |
| DataStore Preferences | `androidx.datastore:datastore-preferences` | Reactive settings storage | Apache-2.0 |
| WorkManager | `androidx.work:work-runtime-ktx` | Periodic discovery & maintenance | Apache-2.0 |
| Kotlin Coroutines & Serialization | `org.jetbrains.kotlinx:*` | Async flows & JSON compilation | Apache-2.0 |
| OkHttp | `com.squareup.okhttp3:okhttp` | HTTPS discovery, IP lookup, speed test | Apache-2.0 |
| WireGuard Android Tunnel | `com.wireguard.android:tunnel` | Real userspace WireGuard VPN engine | Apache-2.0 |
| Android Desugar JDK Libs | `com.android.tools:desugar_jdk_libs` | Java time/stream compatibility | GPL-2.0 with Classpath Exception |
| sing-box Native Core (`libbox-android`) | `net.clever-vpn:libbox-android` (`SagerNet/sing-box`) | Bundled multi-protocol proxy engine (VLESS/VMess/Trojan/SS/Hy2/TUIC) | **GPL-3.0-or-later** |
| OpenVPN3 Native Core (`vpnprotocols-openvpn`) | `io.github.tim06:vpnprotocols-openvpn` (`tim06/VPNProtocols`) | Dedicated bundled OpenVPN3 `.ovpn` engine (`libovpn3.so`) | **AGPL-3.0 / Apache-2.0** |
| Vazirmatn Font | Google Fonts (bundled `.ttf`) | Persian RTL typography | SIL OFL 1.1 |
| Space Grotesk Font | Google Fonts (bundled `.ttf`) | Latin brand & protocol badges | SIL OFL 1.1 |
| JetBrains Mono Font | Google Fonts (bundled `.ttf`) | IP, latency, speed & log readouts | SIL OFL 1.1 |
| Robolectric & JUnit | `org.robolectric`, `junit` (test only) | JVM unit & Android integration tests | MIT / EPL-1.0 |
