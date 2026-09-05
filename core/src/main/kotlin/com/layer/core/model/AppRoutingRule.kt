package com.layer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRoutingRule(
    val packageName: String,
    val appName: String,
    val mode: AppRoutingMode,
)
