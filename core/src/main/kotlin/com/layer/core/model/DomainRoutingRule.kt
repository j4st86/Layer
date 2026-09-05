package com.layer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DomainRoutingRule(
    val domain: String,
    val mode: DomainRoutingMode,
)
