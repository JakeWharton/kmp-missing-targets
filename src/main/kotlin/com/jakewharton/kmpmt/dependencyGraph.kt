package com.jakewharton.kmpmt

import java.io.Serializable
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.logging.Logger

internal data class DependencyCoordinates(
	val group: String,
	val artifact: String,
	val version: String,
) : Serializable,
	Comparable<DependencyCoordinates> {
	override fun toString() = "$group:$artifact:$version"
	fun moduleCoordinate() = "$this@module"
	override fun compareTo(other: DependencyCoordinates) = comparator.compare(this, other)

	private companion object {
		val comparator = compareBy(
			DependencyCoordinates::group,
			DependencyCoordinates::artifact,
			DependencyCoordinates::version,
		)
	}
}

internal fun loadDependencyCoordinates(
	logger: Logger,
	root: ResolvedComponentResult,
): DependencyResolutionResult {
	val warnings = mutableListOf<String>()

	val coordinates = mutableSetOf<DependencyCoordinates>()
	loadDependencyCoordinates(logger, root, coordinates, mutableSetOf(), depth = 1)

	return DependencyResolutionResult(coordinates, warnings)
}

internal data class DependencyResolutionResult(
	val coordinates: Set<DependencyCoordinates>,
	val configWarnings: List<String>,
)

internal fun ModuleComponentIdentifier.toDependencyCoordinates() = DependencyCoordinates(group, module, version)

private fun loadDependencyCoordinates(
	logger: Logger,
	root: ResolvedComponentResult,
	destination: MutableSet<DependencyCoordinates>,
	seen: MutableSet<ComponentIdentifier>,
	depth: Int,
) {
	val id = root.id

	var ignoreSuffix: String? = null
	when (id) {
		is ProjectComponentIdentifier -> {
			// Local dependency, do nothing.
			ignoreSuffix = " ignoring because project dependency"
		}

		is ModuleComponentIdentifier -> {
			if (id.group == "" && id.version == "") {
				// Assuming flat-dir repository dependency, do nothing.
				ignoreSuffix = " ignoring because flat-dir repository artifact has no metadata"
			} else {
				destination += id.toDependencyCoordinates()
			}
		}

		else -> error("Unknown dependency ${id::class.java}: $id")
	}

	if (logger.isInfoEnabled) {
		logger.info(
			buildString {
				repeat(depth) {
					append("  ")
				}
				append(id)
				if (ignoreSuffix != null) {
					append(ignoreSuffix)
				}
			},
		)
	}

	for (dependency in root.dependencies) {
		if (dependency is ResolvedDependencyResult) {
			val selected = dependency.selected
			if (seen.add(selected.id)) {
				loadDependencyCoordinates(
					logger,
					selected,
					destination,
					seen,
					depth + 1,
				)
			}
		}
	}
}
