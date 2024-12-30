package com.jakewharton.kmpmt

import java.io.Serializable
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.logging.Logger

internal data class DependencyCoordinate(
	val group: String,
	val artifact: String,
) : Serializable,
	Comparable<DependencyCoordinate> {
	override fun toString() = "$group:$artifact"
	override fun compareTo(other: DependencyCoordinate) = comparator.compare(this, other)

	private companion object {
		val comparator = compareBy(
			DependencyCoordinate::group,
			DependencyCoordinate::artifact,
		)
	}
}

internal data class VersionedDependency(
	val coordinate: DependencyCoordinate,
	val version: String,
) : Serializable,
	Comparable<VersionedDependency> {
	override fun toString() = "$coordinate:$version"
	fun moduleCoordinate() = "$this@module"
	override fun compareTo(other: VersionedDependency) = comparator.compare(this, other)

	private companion object {
		val comparator = compareBy(
			VersionedDependency::coordinate,
			VersionedDependency::version,
		)
	}
}

internal fun loadVersionedDependencies(
	logger: Logger,
	root: ResolvedComponentResult,
): DependencyResolutionResult {
	val warnings = mutableListOf<String>()

	val coordinates = mutableSetOf<VersionedDependency>()
	loadVersionedDependencies(logger, root, coordinates, mutableSetOf(), depth = 1)

	return DependencyResolutionResult(coordinates, warnings)
}

internal data class DependencyResolutionResult(
	val versionedCoordinates: Set<VersionedDependency>,
	val configWarnings: List<String>,
)

internal fun ModuleComponentIdentifier.toVersionedDependency() = VersionedDependency(
	coordinate = DependencyCoordinate(group, module),
	version = version,
)

private fun loadVersionedDependencies(
	logger: Logger,
	root: ResolvedComponentResult,
	destination: MutableSet<VersionedDependency>,
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
				destination += id.toVersionedDependency()
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
				loadVersionedDependencies(
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
