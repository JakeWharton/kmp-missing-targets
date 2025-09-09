/*
 * Copyright (C) 2024 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jakewharton.kmpmt

import java.io.File
import java.util.SortedMap
import java.util.SortedSet
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.konan.target.KonanTarget.Companion.deprecatedTargets
import org.jetbrains.kotlin.konan.target.KonanTarget.Companion.predefinedTargets

@CacheableTask
public abstract class MissingTargetsTask : DefaultTask() {
	@get:Input
	internal abstract val dependenciesToModuleJson: MapProperty<VersionedDependency, String>

	@get:Input
	internal abstract val projectName: Property<String>

	@get:Input
	internal abstract val sourceSetTargets: SetProperty<String>

	@get:Input
	internal abstract val ignoredTargetToReasons: MapProperty<String, List<String>>

	@get:OutputFile
	internal abstract val outputFile: RegularFileProperty

	@TaskAction
	public fun execute() {
		val logger = Logging.getLogger(MissingTargetsTask::class.java)!!
		val jsonFormat = Json { ignoreUnknownKeys = true }

		val currentTargets: SortedSet<String> = sourceSetTargets.get().toSortedSet()

		val ignoredTargetToReasons: SortedMap<String, List<String>> = ignoredTargetToReasons.get().toSortedMap()
		val ignoredTargets: SortedSet<String> = ignoredTargetToReasons.keys.toSortedSet()

		val dependenciesToTargets: SortedMap<VersionedDependency, SortedSet<String>> = dependenciesToModuleJson.get()
			.filterKeys { (coordinate) ->
				// The common stdlib has empty module metadata and is a dependency of the regular stdlib.
				coordinate != kotlinStdlibCommon
			}
			.mapValues { (coordinates, json) ->
				try {
					jsonFormat.decodeFromString(GradleModuleMetadata.serializer(), json)
				} catch (e: Exception) {
					throw IllegalStateException("Unable to parse module metadata for $coordinates", e)
				}
			}
			.mapValues { (coordinates, metadata) ->
				try {
					extractEffectiveTargets(coordinates, metadata).toSortedSet()
				} catch (e: Exception) {
					throw IllegalStateException("Unable to extract targets for $coordinates", e)
				}
			}
			.toSortedMap()

		val knownTargets: SortedSet<String> = dependenciesToTargets.values
			.fold(emptySet(), Set<String>::union)
			.toSortedSet()

		val targetsToMissingCoordinates: SortedMap<String, Set<VersionedDependency>> = knownTargets
			.associateWith { seenTarget ->
				dependenciesToTargets.filterValues { seenTarget !in it }.keys
			}
			.filterValues(Set<*>::isNotEmpty)
			.toSortedMap()

		val possibleTargets: SortedSet<String> = dependenciesToTargets.values
			.reduceOrNull(Set<String>::intersect)
			?.toSortedSet()
			?: throw IllegalStateException("Project has zero dependencies (not even the stdlib)")

		val unavailableTargets: SortedSet<String> = (targetsToMissingCoordinates.keys - ignoredTargets).toSortedSet()
		val missingTargets: SortedSet<String> = (possibleTargets - currentTargets - ignoredTargets).toSortedSet()

		if (logger.isDebugEnabled) {
			logger.debug(
				buildString {
					appendLine(currentTargets)

					if (dependenciesToTargets.isNotEmpty()) {
						appendLine()
						for ((coordinate, coordinateTargets) in dependenciesToTargets) {
							append(coordinate)
							append(": ")
							appendLine(coordinateTargets)
						}
						appendLine()
					}

					append("known: ")
					appendLine(knownTargets)
					append("possible: ")
					appendLine(possibleTargets)
					append("ignored: ")
					appendLine(ignoredTargets)
					append("missing: ")
					appendLine(missingTargets)
					append("blocked: ")
					appendLine(unavailableTargets)
				},
			)
		}

		val unknownIgnoredTargets = ignoredTargets - knownTargets
		check(unknownIgnoredTargets.isEmpty()) {
			buildString {
				appendLine("Unknown ignored targets specified!")
				for (target in unknownIgnoredTargets) {
					append("\n- ")
					append(target)
				}
			}
		}

		val usedIgnoredTargets = ignoredTargets.intersect(currentTargets)
		check(usedIgnoredTargets.isEmpty()) {
			buildString {
				appendLine("Ignored targets are used! Either remove the ignore or the declaration.")
				for (target in usedIgnoredTargets) {
					append("\n- ")
					append(target)
				}
			}
		}

		val outputFile = outputFile.get().asFile
		outputFile.parentFile.mkdirs()

		outputFile.useBufferedWriter {
			append("# Project '")
			append(projectName.get())
			appendLine('\'')
			appendLine()

			appendLine("## Current targets")
			for (target in currentTargets) {
				append("- `")
				append(target)
				appendLine('`')
			}

			if (missingTargets.isNotEmpty()) {
				appendLine()
				appendLine("## Available targets")
				for (target in missingTargets) {
					append("- `")
					append(target)
					appendLine('`')
				}
			}

			if (unavailableTargets.isNotEmpty()) {
				appendLine()
				appendLine("## Unavailable targets")
				for (target in unavailableTargets) {
					append("- `")
					append(target)
					appendLine('`')
				}
			}

			if (ignoredTargetToReasons.isNotEmpty()) {
				appendLine()
				appendLine("## Ignored targets")
				for ((target, reasons) in ignoredTargetToReasons) {
					append("- `")
					append(target)
					append("` because")
					if (reasons.size == 1) {
						append(' ')
						appendLine(reasons[0])
					} else {
						appendLine()
						for (reason in reasons) {
							append("  - ")
							appendLine(reason)
						}
					}
				}
			}

			appendLine()
			appendLine()
			appendLine("# Unsupported dependencies by target")
			if (targetsToMissingCoordinates.isEmpty()) {
				appendLine()
				appendLine("None!")
			} else {
				for ((target, coordinates) in targetsToMissingCoordinates) {
					appendLine()
					append("## `")
					append(target)
					appendLine("` unsupported by")
					for (coordinate in coordinates) {
						append("- `")
						append(coordinate)
						appendLine('`')
					}
				}
			}

			appendLine()
			appendLine()
			appendLine("# Supported targets by dependency")
			if (dependenciesToTargets.isEmpty()) {
				appendLine()
				appendLine("None!")
			} else {
				for ((dependency, coordinateTargets) in dependenciesToTargets) {
					appendLine()
					append("## `")
					append(dependency.coordinate)
					appendLine('`')
					appendLine()
					append("Current version: ")
					appendLine(dependency.version)
					appendLine()
					for (target in coordinateTargets) {
						append("- `")
						append(target)
						appendLine('`')
					}
				}
			}
		}

		check(missingTargets.isEmpty()) {
			buildString {
				appendLine("Missing targets detected!")
				for (target in missingTargets) {
					append("\n- ")
					append(target)
				}
			}
		}
	}

	private fun extractEffectiveTargets(coordinates: VersionedDependency, metadata: GradleModuleMetadata) = buildSet {
		for (variant in metadata.variants) {
			if (variant.attributes.gradleDocsType != null) continue
			if (variant.attributes.gradleUsage != "kotlin-api" && variant.attributes.gradleUsage != "java-api") continue
			when (variant.attributes.kotlinPlatformType) {
				"common", null -> continue
				"wasm" -> {
					// TODO check legacy wasm support behavior here
					add("wasm" + variant.attributes.kotlinWasmTarget?.replaceFirstChar(Char::uppercase).orEmpty())
				}

				"native" -> {
					if (coordinates.coordinate == kotlinStdlib) {
						// The stdlib does not have proper native targets in its module metadata because it's managed
						// by the Kotlin Gradle plugin / Kotlin native compiler. Instead, ask the Kotlin Gradle plugin
						// for its set of supported, non-deprecated targets.
						// TODO This is the wrong place to filter these. We want to show this having deprecated targets
						//  and even display them as available but indicate they're deprecated.
						val allTargets = predefinedTargets.filter { it.value !in deprecatedTargets }.keys
						addAll(allTargets.map { it.nativeTargetNameToCamelCase() })
					} else {
						val target = requireNotNull(variant.attributes.kotlinNativeTarget) {
							"Variant '${variant.name}' has no Kotlin native target despite native platform type"
						}
						add(target.nativeTargetNameToCamelCase())
					}
				}

				else -> {
					add(variant.attributes.kotlinPlatformType)
				}
			}
		}
	}

	internal fun configurationToCheck(configuration: Provider<Configuration>) {
		val dependencies = project.dependencies
		val configurations = project.configurations
		val moduleJsons = configuration
			.flatMap { it.incoming.resolutionResult.rootComponent }
			.map { root ->
				val directDependencies = loadVersionedDependencies(
					logger,
					root,
				)
				val moduleFiles = directDependencies.versionedCoordinates.fetchModuleFiles(
					root.variants,
					dependencies,
					configurations,
				)
				moduleFiles.associate {
					it.dependency to it.moduleFile.readText()
				}
			}

		this.dependenciesToModuleJson.set(moduleJsons)
	}

	private fun Set<VersionedDependency>.fetchModuleFiles(
		variants: List<ResolvedVariantResult>,
		dependencies: DependencyHandler,
		configurations: ConfigurationContainer,
	): List<DependencyWithModuleFile> {
		val moduleDependencies = map {
			dependencies.create(it.moduleCoordinate())
		}.toTypedArray()

		val withVariants = configurations.detachedConfiguration(*moduleDependencies).apply {
			for (variant in variants) {
				attributes {
					val variantAttrs = variant.attributes
					for (attrs in variantAttrs.keySet()) {
						@Suppress("UNCHECKED_CAST")
						it.attribute(attrs as Attribute<Any>, variantAttrs.getAttribute(attrs)!!)
					}
				}
			}
		}.artifacts()

		val withoutVariants = configurations.detachedConfiguration(*moduleDependencies).artifacts()

		return (withVariants + withoutVariants).mapNotNull {
			val coordinates = (it.id.componentIdentifier as ModuleComponentIdentifier).toVersionedDependency()
			try {
				DependencyWithModuleFile(coordinates, it.file)
			} catch (_: GradleException) {
				null
			}
		}.distinctBy { it.dependency }
	}

	private data class DependencyWithModuleFile(
		val dependency: VersionedDependency,
		val moduleFile: File,
	)

	private fun Configuration.artifacts() = resolvedConfiguration
		.lenientConfiguration
		.allModuleDependencies
		.flatMap { it.allModuleArtifacts }

	private val kotlinStdlib = DependencyCoordinate("org.jetbrains.kotlin", "kotlin-stdlib")
	private val kotlinStdlibCommon = DependencyCoordinate("org.jetbrains.kotlin", "kotlin-stdlib-common")
}
