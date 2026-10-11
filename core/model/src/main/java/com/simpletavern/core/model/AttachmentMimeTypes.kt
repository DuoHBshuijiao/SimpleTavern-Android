package com.simpletavern.core.model

/** Common container aliases; no sniffing, transcoding or model selection. */
object AttachmentMimeTypes {
    val images = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    val audio = setOf("audio/wav", "audio/mpeg", "audio/aiff", "audio/aac", "audio/ogg", "audio/flac",
        "audio/m4a", "audio/l16", "audio/opus", "audio/alaw", "audio/mulaw", "audio/webm")
    val video = setOf("video/mp4", "video/mpeg", "video/quicktime", "video/avi", "video/x-flv",
        "video/mpg", "video/webm", "video/wmv", "video/3gpp")
    val documents = setOf("application/pdf", "application/msword", "application/rtf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint",
        "application/vnd.oasis.opendocument.text", "application/csv")
    private val aliases = mapOf("image/jpg" to "image/jpeg", "audio/mp3" to "audio/mpeg",
        "audio/x-mp3" to "audio/mpeg", "audio/x-wav" to "audio/wav", "audio/wave" to "audio/wav",
        "audio/x-wave" to "audio/wav", "audio/x-flac" to "audio/flac", "audio/x-aiff" to "audio/aiff",
        "audio/basic" to "audio/mulaw", "audio/pcm" to "audio/l16",
        "audio/mp4" to "audio/m4a", "audio/x-m4a" to "audio/m4a", "video/mov" to "video/quicktime",
        "video/x-msvideo" to "video/avi", "video/x-ms-wmv" to "video/wmv")
    fun normalize(value: String): String {
        val mime = value.substringBefore(';').trim().lowercase()
        return aliases[mime] ?: mime
    }
    fun isText(mime: String) = mime.startsWith("text/") || mime in setOf("application/json", "application/xml",
        "application/javascript", "application/yaml", "application/x-yaml", "application/toml")
    fun isBinary(mime: String) = mime in images || mime in audio || mime in video || mime in documents
    fun fromFilename(filename: String): String? = when (filename.substringAfterLast('.', "").lowercase()) {
        "md", "log", "yaml", "yml", "toml", "kt", "py", "js", "ts" -> "text/plain"
        "pdf" -> "application/pdf"; "doc" -> "application/msword"; "rtf" -> "application/rtf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "ppt" -> "application/vnd.ms-powerpoint"; "odt" -> "application/vnd.oasis.opendocument.text"
        "csv" -> "text/csv"; "tsv" -> "text/tab-separated-values"
        "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "aif", "aiff" -> "audio/aiff"
        "aac" -> "audio/aac"; "ogg", "oga" -> "audio/ogg"; "flac" -> "audio/flac"
        "m4a" -> "audio/m4a"; "opus" -> "audio/opus"
        "pcm" -> "audio/l16"
        "mp4", "m4v" -> "video/mp4"; "mpeg" -> "video/mpeg"; "mpg" -> "video/mpg"
        "mov" -> "video/quicktime"; "avi" -> "video/avi"; "flv" -> "video/x-flv"
        "webm" -> "video/webm"; "wmv" -> "video/wmv"; "3gp" -> "video/3gpp"
        else -> null
    }
}
