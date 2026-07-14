# YukiVPN

YukiVPN is an Android L2TP client under active development. Android 12 and newer no longer expose the legacy L2TP/IPsec client in AOSP, so this project owns the client lifecycle through `VpnService`.

## Current milestone

- Compose profile screen with Android VPN permission handling
- Multi-profile management with add, edit, delete, and active-profile switching
- AES-GCM credential storage backed by Android Keystore
- Foreground `VpnService` lifecycle and explicit connection states
- L2TPv2 tunnel and incoming-call session negotiation with retransmission
- PPP LCP plus PAP, CHAP-MD5, and MS-CHAPv2 authentication
- IPCP IPv4 and DNS negotiation
- Android TUN creation and bidirectional IPv4 packet forwarding
- JVM tests for protocol encoding and malformed packets

The current build can establish a plaintext L2TP/PPP IPv4 tunnel. It deliberately refuses to connect a profile containing a pre-shared key until IKE/IPsec transport protection is implemented, preventing silent credential downgrade. Plaintext mode is intended for controlled interoperability testing, not untrusted networks.

## Build

Use JDK 17 and Android SDK 35:

```powershell
./gradlew.bat test assembleDebug
```

## Roadmap

1. IKEv1 Main/Aggressive Mode and IPsec transport mode, or a maintained native IPsec engine
2. L2TP teardown, keepalive timers, and reconnect policy
3. IPv6CP, diagnostics export, and broader interoperability tests
4. Multi-tunnel flow scheduling for concurrent connections

Only modern cryptographic suites should be enabled by default. Compatibility algorithms such as 3DES and SHA-1 need an explicit per-profile opt-in and a visible warning.
