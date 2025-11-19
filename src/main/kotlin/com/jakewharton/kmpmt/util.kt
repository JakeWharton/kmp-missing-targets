package com.jakewharton.kmpmt

import java.io.File
import org.jetbrains.kotlin.konan.file.use

internal fun File.useBufferedWriter(block: Appendable.() -> Unit) {
  bufferedWriter().use(block)
}

internal fun Appendable.append(any: Any) {
  append(any.toString())
}

internal fun String.nativeTargetNameToCamelCase(): String {
  return split('_').withIndex().joinToString("") { (index, value) ->
    if (index == 0) {
      if (value == "android") {
        "androidNative"
      } else {
        value
      }
    } else {
      value.replaceFirstChar(Char::uppercase)
    }
  }
}
