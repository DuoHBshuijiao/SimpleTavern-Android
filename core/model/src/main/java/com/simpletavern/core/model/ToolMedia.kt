package com.simpletavern.core.model

/** Decoded inline output, owned by the current request until it is saved as an attachment. */
class ToolMedia(val filename: String, val mimeType: String, val bytes: ByteArray, val modelVisible: Boolean = true) {
    override fun toString() = "ToolMedia(mimeType=$mimeType, bytes=${bytes.size}, contents=<private>)"
}
