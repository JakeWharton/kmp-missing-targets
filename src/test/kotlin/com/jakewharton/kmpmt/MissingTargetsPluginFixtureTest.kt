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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class MissingTargetsPluginFixtureTest {
	@Test
	fun failure(
		@TestParameter(
			"first",
		) fixtureName: String,
	) {
		val fixtureDir = File(fixturesDir, fixtureName)
		createRunner(fixtureDir).buildAndFail()
		assertExpectedFiles(fixtureDir)
	}

	@Test
	fun allFixturesCovered() {
		val expectedDirs = javaClass.declaredMethods
			.filter { it.isAnnotationPresent(Test::class.java) }
			.filter { it.parameterCount == 1 } // Assume single parameter means test parameter.
			.flatMap { it.parameters[0].getAnnotation(TestParameter::class.java).value.toList() }
			.sorted()
		val actualDirs = fixturesDir.listFiles()!!
			.filter { it.isDirectory }
			.map { it.name }
			.sorted()
		assertThat(actualDirs).isEqualTo(expectedDirs)
	}

	private fun createRunner(fixtureDir: File): GradleRunner {
		val gradleRoot = File(fixtureDir, "gradle").also { it.mkdir() }
		File("gradle/wrapper").copyRecursively(File(gradleRoot, "wrapper"), true)
		val androidSdkFile = File("local.properties")
		if (androidSdkFile.exists()) {
			androidSdkFile.copyTo(File(fixtureDir, "local.properties"), overwrite = true)
		}
		return GradleRunner.create()
			.withProjectDir(fixtureDir)
			.withDebug(true) // Run in-process
			.withArguments(
                "clean",
                "assemble",
                "kmpMissingTargets",
                "--stacktrace",
                "--continue",
                "--configuration-cache",
                versionProperty,
            )
			.forwardOutput()
	}

	private fun assertExpectedFiles(fixtureDir: File) {
		val expectedDir = File(fixtureDir, "expected")
		if (!expectedDir.exists()) {
			throw AssertionError("Missing expected/ directory")
		}

		val expectedFiles = expectedDir.walk().filter { it.isFile }.toList()
		assertThat(expectedFiles).isNotEmpty()
		for (expectedFile in expectedFiles) {
			val actualFile = File(fixtureDir, expectedFile.relativeTo(expectedDir).toString())
			if (!actualFile.exists()) {
				throw AssertionError("Expected $actualFile but does not exist")
			}
			assertThat(actualFile.readText()).isEqualTo(expectedFile.readText())
		}
	}
}

private val fixturesDir = File("src/test/fixtures")
private val versionProperty = "-PkmpmtVersion=${System.getProperty("kmpmtVersion")!!}"
