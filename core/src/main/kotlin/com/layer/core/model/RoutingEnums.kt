package com.layer.core.model

enum class AppRoutingMode {
    SMART,
    VPN,
    DIRECT,
}

enum class DomainRoutingMode {
    VPN,
    DIRECT,
}

enum class RoutingOutbound {
    DIRECT,
    VPN,
}

enum class RoutingReason {
    APP_DIRECT,
    APP_VPN,
    USER_DOMAIN_DIRECT,
    USER_DOMAIN_VPN,
    AUTOMATIC_RULE_SET,
    LOCAL_TRAFFIC,
    DEFAULT_DIRECT,
}
