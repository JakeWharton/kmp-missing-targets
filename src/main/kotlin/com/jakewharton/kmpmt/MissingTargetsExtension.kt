package com.jakewharton.kmpmt

public interface MissingTargetsExtension {
	public fun ignore(name: String, because: String)
}

internal class MissingTargetsExtensionImpl : MissingTargetsExtension {
	private val _ignoredTargets = mutableMapOf<String, List<String>>()
	val ignoredTargets: Map<String, List<String>> get() = _ignoredTargets

	override fun ignore(name: String, because: String) {
		_ignoredTargets.compute(name) { _, reasons ->
			reasons.orEmpty() + because
		}
	}
}
