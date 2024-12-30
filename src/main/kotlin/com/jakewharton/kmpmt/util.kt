package com.jakewharton.kmpmt

import java.io.File
import org.jetbrains.kotlin.konan.file.use

internal fun File.useBufferedWriter(block: Appendable.() -> Unit) {
	bufferedWriter().use(block)
}

internal fun Appendable.append(any: Any) {
	append(any.toString())
}
