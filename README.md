# YukiVPN

YukiVPN is an Android L2TP client under active development. Android 12 and newer no longer expose the legacy L2TP/IPsec client in AOSP, so this project owns the client lifecycle through `VpnService`.

## Current milestone

- Compose profile screen with Android VPN permission handling
- Multi-profile management with add, edit, delete, and active-profile switching
- AES-GCM credential storage backed by Android Keystore
- Foreground `VpnService` lifecycle and explicit connection states
- L2TPv2 control-packet codec
- UDP/1701 SCCRQ -> SCCRP -> SCCCN reachability probe
- JVM tests for protocol encoding and malformed packets

The current build is a protocol probe, not a production VPN. It does not establish a TUN interface or route device traffic. L2TP/IPsec requires IKE/IPsec transport protection, and a usable data tunnel additionally requires PPP LCP, authentication, and IPCP negotiation.

## Build

Use JDK 17 and Android SDK 35:

```powershell
./gradlew.bat test assembleDebug
```

## Roadmap

1. IKEv1 Main/Aggressive Mode and IPsec transport mode, or a maintained native IPsec engine
2. L2TP control retransmission, hello/stop handling, and call-session negotiation
3. PPP LCP plus PAP, CHAP, and MS-CHAPv2 authentication
4. IPCP address/DNS negotiation and TUN packet bridge
5. IPv6, reconnect policy, diagnostics export, and interoperability tests

Only modern cryptographic suites should be enabled by default. Compatibility algorithms such as 3DES and SHA-1 need an explicit per-profile opt-in and a visible warning.
