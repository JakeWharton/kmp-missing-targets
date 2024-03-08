package com.jakewharton.kmpmt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GradleModuleMetadata(
	val formatVersion: String,
	val variants: List<GradleModuleVariant>,
)

@Serializable
internal data class GradleModuleVariant(
	val name: String,
	val attributes: GradleModuleAttributes,
)

@Serializable
internal data class GradleModuleAttributes(
	@SerialName("org.gradle.docstype")
	val gradleDocsType: String? = null,
	@SerialName("org.gradle.usage")
	val gradleUsage: String? = null,
	@SerialName("org.jetbrains.kotlin.platform.type")
	val kotlinPlatformType: String? = null,
	@SerialName("org.jetbrains.kotlin.native.target")
	val kotlinNativeTarget: String? = null,
	@SerialName("org.jetbrains.kotlin.wasm.target")
	val kotlinWasmTarget: String? = null,
)
