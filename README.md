# Nova VPN — Native Android Multi-Engine VPN Client

Nova VPN is a native Android VPN application built in **Kotlin** and **Jetpack Compose (Material 3)** following **Clean Architecture + MVVM**, featuring Persian (RTL) primary localization with English protocol identifiers and a live English (LTR) switcher.

## Key Capabilities

- **Premium Commercial Dashboard (Dark Navy + Electric Blue/Violet)**: Animated orbital Connect/Disconnect hero control, real connection state machine (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`, `DISCONNECTING`, `ERROR`), real public IP & VPN exit IP lookup, live download/upload telemetry, session timer, and history.
- **European Server Explorer**: Prioritizes **Sweden (SE)** and **Switzerland (CH)** followed by Germany, Netherlands, France, Finland, Norway, Denmark, Austria, Belgium, Poland, Czech Republic, Romania, and other European countries, with country/protocol/favorites filters, search, sorting, real TCP/TLS latency probes, and **Smart Server Selection** (`Auto Select`, `Best Latency`, `Best Stability`, `Best Speed`, `Manual`).
- **Configuration Manager**: Safe parsing, validation, Keystore-encrypted local storage, editing, export, and subscription import for `OpenVPN .ovpn`, `VLESS` (TLS & Reality), `VMess`, `Trojan`, `Shadowsocks`, `Hysteria2`, `TUIC`, `WireGuard`, `Sing-box JSON`, `Xray JSON`, and `Clash YAML`.
- **Automatic European Discovery**: Configurable HTTPS-only allowlist of public sources (including VPNGate academic OpenVPN directory and curated public GitHub registries), bounded-concurrency TCP/TLS reachability testing, GeoIP country resolution, deduplication, WorkManager periodic maintenance, and explicit user approval required before connecting to untrusted public servers.
- **Multi-Engine Architecture (`EngineManager`)**:
  - **Engine A — sing-box (`SingBoxVpnEngine`)**: Bundled natively via Maven Central `net.clever-vpn:libbox-android:2.1.4` (`io.nekohasekai.libbox.CommandServer` + `PlatformInterface`), with sing-box 1.12+ JSON compilation and `NovaVpnService` TUN + socket protection for VLESS/Reality/TLS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, SOCKS, HTTP, and WireGuard.
  - **WireGuard Engine (`WireGuardVpnEngine`)**: Bundled natively via official Maven Central `com.wireguard.android:tunnel` (`GoBackend`) with authentic RX/TX traffic counters.
  - **Engine B — OpenVPN (`OpenVpnEngine`)**: Strictly isolated from sing-box, with dangerous `.ovpn` script directive sanitization (`script-security`, `up`, `down`, `plugin`) + `app/libs/ics-openvpn.aar` bridge.
- **Zero Fabrication Integrity**: Nova VPN never fabricates servers, IPs, speeds, or connection states.

## Optional: Bundling Native `ics-openvpn.aar` (OpenVPN)

Both **sing-box (`libbox-android`)** and **WireGuard (`tunnel`)** are already bundled directly from Maven Central and work out of the box. For dedicated `.ovpn` tunneling without an external OpenVPN client, clone `https://github.com/schwabe/ics-openvpn`, build its library AAR with NDK/CMake, and copy the resulting AAR to `app/libs/ics-openvpn.aar` (Gradle automatically includes `app/libs/*.aar`).

## Building & Running Tests

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

## Security & Privacy Architecture

- **At-Rest Encryption**: Raw VPN configs, private keys, and credentials are encrypted via **Android Keystore AES-256-GCM** (`CryptoVault`) before persisting to Room (`android:allowBackup="false"`).
- **Redacted Diagnostics**: All diagnostic logs pass through `SecretRedactor` to strip private keys, PEM blocks, UUIDs, passwords, and tokens.
- **Safe Source Fetching**: Discovery enforces HTTPS-only URLs and rejects loopback/private/link-local addresses.
- **Untrusted Config Gate**: Publicly discovered configs are marked untrusted by default and require explicit user approval before opening a tunnel.
- **Play-Policy Minimal Permissions**: Uses Android Storage Access Framework (`OpenDocument`) instead of broad storage permissions, and `<queries>` launcher intent instead of `QUERY_ALL_PACKAGES` for Split Tunneling.

See [`LICENSES.md`](LICENSES.md) for the complete dependency and license inventory.
