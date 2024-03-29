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
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.konan.target.KonanTarget.Companion.deprecatedTargets
import org.jetbrains.kotlin.konan.target.KonanTarget.Companion.predefinedTargets

@CacheableTask
public abstract class MissingTargetsTask : DefaultTask() {
	@get:Input
	internal abstract val coordinateToModuleJson: MapProperty<DependencyCoordinates, String>

	@get:Input
	internal abstract val projectName: Property<String>

	@get:Input
	internal abstract val sourceSetName: Property<String>

	@get:Input
	internal abstract val sourceSetTargets: SetProperty<String>

	@get:OutputDirectory
	internal abstract val outputDir: DirectoryProperty

	@TaskAction
	public fun execute() {
		val logger = Logging.getLogger(MissingTargetsTask::class.java)!!
		val jsonFormat = Json { ignoreUnknownKeys = true }

		val currentTargets = sourceSetTargets.get().toSortedSet()

		val coordinateToTargets = coordinateToModuleJson.get()
			.filterKeys { coordinate ->
				// The common stdlib has empty module metadata and is a dependency of the regular stdlib.
				coordinate.group != "org.jetbrains.kotlin" || coordinate.artifact != "kotlin-stdlib-common"
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

		val targetsToMissingCoordinates = coordinateToTargets.values
			.fold(emptySet(), Set<String>::union)
			.associateWith { seenTarget ->
				coordinateToTargets.filterValues { seenTarget !in it }.keys
			}
			.filterValues(Set<*>::isNotEmpty)

		val possibleTargets = coordinateToTargets.values
			.reduceOrNull(Set<String>::intersect)
			?: throw IllegalStateException("Project has zero dependencies (not even the stdlib)")

		val missingTargets = possibleTargets - currentTargets

		if (logger.isDebugEnabled) {
			logger.debug(
				buildString {
					append(sourceSetName)
					append(": ")
					appendLine(currentTargets)

					if (coordinateToTargets.isNotEmpty()) {
						appendLine()
						for ((coordinate, coordinateTargets) in coordinateToTargets) {
							append(coordinate)
							append(": ")
							appendLine(coordinateTargets)
						}
						appendLine()
					}

					append("possible: ")
					appendLine(possibleTargets)
					append("missing: ")
					appendLine(missingTargets)
					append("blocked: ")
					appendLine(targetsToMissingCoordinates.keys)
				},
			)
		}

		val projectName = projectName.get()
		val sourceSetName = sourceSetName.get()

		val outputFile = outputDir.get().file("$sourceSetName.md").asFile
		outputFile.parentFile.mkdirs()

		outputFile.writeText(
			buildString {
				append("# Project '")
				append(projectName)
				append("' `")
				append(sourceSetName)
				appendLine('`')
				appendLine()
				appendLine("## Current targets")
				for (target in currentTargets) {
					append("- `")
					append(target)
					appendLine('`')
				}
				appendLine()
				appendLine("## Available targets")
				for (target in missingTargets) {
					append("- `")
					append(target)
					appendLine('`')
				}
				appendLine()
				appendLine("## Unavailable targets")
				for (target in targetsToMissingCoordinates.keys) {
					append("- `")
					append(target)
					appendLine('`')
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
				if (coordinateToTargets.isEmpty()) {
					appendLine()
					appendLine("None!")
				} else {
					for ((coordinate, coordinateTargets) in coordinateToTargets) {
						appendLine()
						append("## `")
						append(coordinate)
						appendLine('`')
						for (target in coordinateTargets) {
							append("- `")
							append(target)
							appendLine('`')
						}
					}
				}
			},
		)

		check(missingTargets.isEmpty()) {
			buildString {
				appendLine("Missing targets detected!")
				for (target in missingTargets) {
					append("- `")
					append(target)
					appendLine('`')
				}
			}
		}
	}

	private fun extractEffectiveTargets(coordinates: DependencyCoordinates, metadata: GradleModuleMetadata) = buildSet {
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
					if (coordinates.group == "org.jetbrains.kotlin" && coordinates.artifact == "kotlin-stdlib") {
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

	private fun String.nativeTargetNameToCamelCase(): String {
		return split('_')
			.withIndex()
			.joinToString("") { (index, value) ->
				if (index == 0) {
					value
				} else {
					value.replaceFirstChar(Char::uppercase)
				}
			}
	}

	internal fun configurationToCheck(configuration: Provider<Configuration>) {
		val dependencies = project.dependencies
		val configurations = project.configurations
		val moduleJsons = configuration
			.flatMap { it.incoming.resolutionResult.rootComponent }
			.map { root ->
				val directDependencies = loadDependencyCoordinates(
					logger,
					root,
				)
				val moduleFiles = directDependencies.coordinates.fetchModuleFiles(
					root.variants,
					dependencies,
					configurations,
				)
				moduleFiles.associate {
					it.dependencyCoordinates to it.moduleFile.readText()
				}
			}

		this.coordinateToModuleJson.set(moduleJsons)
	}

	private fun Set<DependencyCoordinates>.fetchModuleFiles(
		variants: List<ResolvedVariantResult>,
		dependencies: DependencyHandler,
		configurations: ConfigurationContainer,
	): List<DependencyCoordinatesWithModuleFile> {
		val moduleDependencies = map {
			dependencies.create(it.moduleCoordinate())
		}.toTypedArray()

		val withVariants = configurations.detachedConfiguration(*moduleDependencies).apply {
			for (variant in variants) {
				attributes {
					val variantAttrs = variant.attributes
					for (attrs in variantAttrs.keySet()) {
						@Suppress("UNCHECKED_CAST")
						it.attribute(attrs as Attribute<Any?>, variantAttrs.getAttribute(attrs)!!)
					}
				}
			}
		}.artifacts()

		val withoutVariants = configurations.detachedConfiguration(*moduleDependencies).artifacts()

		return (withVariants + withoutVariants).mapNotNull {
			val coordinates = (it.id.componentIdentifier as ModuleComponentIdentifier).toDependencyCoordinates()
			try {
				DependencyCoordinatesWithModuleFile(coordinates, it.file)
			} catch (e: GradleException) {
				null
			}
		}.distinctBy { it.dependencyCoordinates }
	}

	private data class DependencyCoordinatesWithModuleFile(
		val dependencyCoordinates: DependencyCoordinates,
		val moduleFile: File,
	)

	private fun ModuleComponentIdentifier.toDependencyCoordinates() = DependencyCoordinates(group, module, version)
	private fun Configuration.artifacts() =
		resolvedConfiguration.lenientConfiguration.allModuleDependencies.flatMap { it.allModuleArtifacts }
}
