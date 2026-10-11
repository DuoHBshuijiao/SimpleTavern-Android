package com.simpletavern.core.api

import com.simpletavern.core.model.CharacterCardImportPreview

/** UI-owned draft; frozen image bytes stay private to Core and are not persisted or logged. */
class CharacterCardImportDraft internal constructor(
    val preview: CharacterCardImportPreview,
    internal val pngBytes: ByteArray?,
) {
    override fun toString() = "CharacterCardImportDraft(format=${preview.sourceFormat}, hasAvatar=${pngBytes != null})"
}
