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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class MissingTargetsPluginFixtureTest(
  @param:TestParameter(LATEST_GRADLE_VERSION, MINIMUM_GRADLE_VERSION)
  private val gradleVersion: String
) {
  @Test
  fun success(@TestParameter("android-local-target", "available-targets-all") fixtureName: String) {
    val fixtureDir = File(fixturesDir, fixtureName)
    createRunner(fixtureDir).build()
    assertExpectedFiles(fixtureDir)
  }

  @Test
  fun failure(
    @TestParameter(
      "available-targets-some",
      "custom-target-name",
      "ignored-target-available",
      "ignored-target-unavailable",
    )
    fixtureName: String
  ) {
    val fixtureDir = File(fixturesDir, fixtureName)
    createRunner(fixtureDir).buildAndFail()
    assertExpectedFiles(fixtureDir)
  }

  @Test
  fun noStdlibFails() {
    val fixtureDir = File(fixturesDir, "no-stdlib")
    val result = createRunner(fixtureDir).buildAndFail()
    assertThat(result.output).contains("Project has zero dependencies (not even the stdlib)")
  }

  @Test
  fun ignoredTargetFailsIfUnknown() {
    val fixtureDir = File(fixturesDir, "ignored-target-fails-if-unknown")
    val result = createRunner(fixtureDir).buildAndFail()
    assertThat(result.output)
      .contains(
        """
        |Unknown ignored targets specified!
        |
        |- dart
        """
          .trimMargin()
      )
  }

  @Test
  fun ignoredTargetFailsIfUsed() {
    val fixtureDir = File(fixturesDir, "ignored-target-fails-if-used")
    val result = createRunner(fixtureDir).buildAndFail()
    assertThat(result.output)
      .contains(
        """
        |Ignored targets are used! Either remove the ignore or the declaration.
        |
        |- jvm
        """
          .trimMargin()
      )
  }

  private fun createRunner(fixtureDir: File): GradleRunner {
    val gradleRoot = File(fixtureDir, "gradle").also { it.mkdir() }
    File("gradle/wrapper").copyRecursively(File(gradleRoot, "wrapper"), true)
    val androidSdkFile = File("local.properties")
    if (androidSdkFile.exists()) {
      androidSdkFile.copyTo(File(fixtureDir, "local.properties"), overwrite = true)
    }
    return GradleRunner.create()
      .apply {
        if (gradleVersion != LATEST_GRADLE_VERSION) {
          withGradleVersion(gradleVersion)
        }
      }
      .withProjectDir(fixtureDir)
      .withDebug(true) // Run in-process
      .withArguments(
        "clean",
        "check",
        "--stacktrace",
        "--continue",
        "--configuration-cache",
        "--no-build-cache",
        VERSION_PROPERTY,
        VALIDATE_KOTLIN_METADATA,
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
private const val VERSION_PROPERTY = "-PkmpmtVersion=$KMPMT_VERSION"
private const val LATEST_GRADLE_VERSION = "latest"
private const val VALIDATE_KOTLIN_METADATA =
  "-Porg.gradle.kotlin.dsl.skipMetadataVersionCheck=false"
