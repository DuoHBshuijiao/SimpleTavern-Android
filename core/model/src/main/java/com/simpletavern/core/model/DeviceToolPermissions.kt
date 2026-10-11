package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceToolPermissions(
    val calendarRead: Boolean,
    val calendarWrite: Boolean,
    val usageAccess: Boolean,
    val userUnlocked: Boolean,
    val appSettingsAction: String,
    val appSettingsUri: String,
    val usageSettingsAction: String,
)
