package com.jakewharton.kmpmt

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.reporting.ReportingExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin.CHECK_TASK_NAME
import org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.MAIN_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.js.KotlinWasmTargetType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

// HEY! If you update the minimum-supported Gradle version check JVM and Kotlin API target in build.gradle.
private val minimumGradleVersion = GradleVersion.version("8.0")

@Suppress("unused") // Instantiated reflectively by Gradle.
public class MissingTargetsPlugin : Plugin<Project> {
	override fun apply(project: Project) {
		val gradleVersion = GradleVersion.current()
		check(gradleVersion >= minimumGradleVersion) {
			"Plugin requires Gradle ${minimumGradleVersion.version} or later. Found ${gradleVersion.version}"
		}

		var gotKmp = false
		project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
			gotKmp = true
			project.configureKmp()
		}
		project.afterEvaluate {
			check(gotKmp) { "org.jetbrains.kotlin.multiplatform is required" }
		}
	}

	private fun Project.configureKmp() {
		val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)

		val missingTargetsExtension = MissingTargetsExtensionImpl()
		extensions.add(
			MissingTargetsExtension::class.java,
			"kotlinMissingTargets",
			missingTargetsExtension,
		)

		val missingTargetsTask = tasks.register("kmpMissingTargets", MissingTargetsTask::class.java) {
			it.description = "Check for missing targets based on the supported targets of common dependencies"
			it.group = VERIFICATION_GROUP

			it.projectName.set(project.name)
			it.ignoredTargetToReasons.set(provider(missingTargetsExtension::ignoredTargets))

			val reporting = project.extensions.getByType(ReportingExtension::class.java)
			it.outputFile.convention(reporting.baseDirectory.file("kmp-missing-targets.md"))
		}
		tasks.named(CHECK_TASK_NAME).configure {
			it.dependsOn(missingTargetsTask)
		}

		kotlin.targets.configureEach { target ->
			if (target.platformType == KotlinPlatformType.common) {
				val compilation = target.compilations.getByName(MAIN_COMPILATION_NAME)
				val runtimeConfigurationName = compilation.compileDependencyConfigurationName
				val runtimeConfiguration = project.configurations.named(runtimeConfigurationName)
				missingTargetsTask.configure {
					it.configurationToCheck(runtimeConfiguration)
				}
			} else {
				val name = when (target) {
					is KotlinNativeTarget -> target.konanTarget.name.nativeTargetNameToCamelCase()
					is KotlinJvmTarget -> "jvm"
					is KotlinAndroidTarget -> "androidTarget"
					is KotlinJsIrTarget -> when (target.wasmTargetType) {
						null -> "js"
						KotlinWasmTargetType.WASI -> "wasmWasi"
						KotlinWasmTargetType.JS -> "wasmJs"
					}
					else -> error("Unknown target $target (${target::class.java})")
				}
				missingTargetsTask.configure {
					it.sourceSetTargets.add(name)
				}
			}
		}
	}
}
