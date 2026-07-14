package dev.yukivpn.vpn

enum class TunnelStatus {
    IDLE,
    PROBING,
    AUTHENTICATING,
    CONTROL_CONNECTED,
    CONNECTED,
    FAILED,
}
