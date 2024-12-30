# KMP Missing Targets

A Gradle plugin which finds missing Kotlin multiplatform targets. **Not ready for use.**

```
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':kmpMissingTargets'.
> Missing targets found!
   - linuxArm64
   - wasmJs
```

Jump to:
[Introduction](#Introduction) |
[Usage](#Usage) |
[License](#License)

## Introduction

Your multiplatform project depends on [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines/), and
[Renovate](https://www.mend.io/renovate/) just helpfully submitted a PR to bump to a newly-released version.

```diff
 [versions]
-kotlinx-coroutines = "1.7.2"
+kotlinx-coroutines = "1.8.0"
```

Did you remember to add `wasmJs` support now that coroutines added it?
[I forgot](https://github.com/cashapp/turbine/pull/303/files).
At least until [someone reminded me](https://github.com/cashapp/turbine/pull/290#issuecomment-1948935392).
And it's not the first time.

With this plugin applied, the Renovate PR would have failed on CI.

```
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':kmpMissingTargets'.
> Missing targets found!
   - wasmJs
```

Can you even support a new target, or are other dependencies blocking you? Turns out [that](https://github.com/JakeWharton/mosaic/issues/319) [also](https://github.com/ajalt/mordant/issues/155) [happens](https://github.com/JetBrains/markdown/issues/146). And it won't be the last time.

If another dependency lacked support for `wasmJs`, the plugin would say silent and allow the new coroutines version build to pass.

Want to know which dependencies are blocking you from supporting a particular target? Check the generated build report.

> ### `wasmJs` unsupported by:
> - `org.jetbrains.compose.runtime:runtime:1.5.12`

Now you know which dependencies to chase when your users come asking.

[Full example report](src/test/fixtures/first/expected/build/reports/kmp-missing-targets/commonMain.md).

If you can't or won't support a target, tell the plugin to ignore it.

```kotlin
kotlinMissingTargets {
  ignore("wasmJs", because = "Wasm sandbox prevents … etc.")
}
```

## Usage

Add the dependency and apply the plugin:

```groovy
buildscript {
  repositories {
    mavenCentral()
  }
  dependencies {
    classpath 'com.jakewharton.kmpmt:kmp-missing-targets:0.1.0'
  }
}

apply plugin: 'com.jakewharton.kmp-missing-targets'
```

<details>
<summary>Snapshots of the development version are available in Sonatype's snapshots repository.</summary>
<p>

```groovy
buildscript {
  repositories {
    mavenCentral()
    maven {
      url 'https://oss.sonatype.org/content/repositories/snapshots/'
    }
  }
  dependencies {
    classpath 'com.jakewharton.kmpmt:kmp-missing-targets:0.2.0-SNAPSHOT'
  }
}

apply plugin: 'com.jakewharton.kmp-missing-targets'
```

</p>
</details>


## License

    Copyright 2024 Jake Wharton

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
