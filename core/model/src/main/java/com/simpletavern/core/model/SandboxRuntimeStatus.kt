package com.simpletavern.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SandboxRuntimeStatus(val sandboxId: String, val abi: String, val runtime: String, val bundled: Boolean,
    val initialized: Boolean, val isolationVerified: Boolean = false,
    val warnings: List<String> = listOf("proot_path_virtualization_not_kernel_isolation", "device_file_boundary_not_verified"))
