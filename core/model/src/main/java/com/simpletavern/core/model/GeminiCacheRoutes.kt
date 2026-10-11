package com.simpletavern.core.model

import java.net.URI

/** Shared wire addressing for cache HTTP and persisted operation metadata. No discovery or network. */
object GeminiCacheRoutes {
    class Route internal constructor(val collectionRoot: String, val projectId: String?, val location: String?) {
        val isVertex: Boolean get() = projectId != null

        fun requestModel(raw: String): String {
            val id = raw.removePrefix("models/")
            if (!MODEL_ID.matches(id)) throw StError.Validation("invalid Gemini cache model")
            return if (isVertex) "projects/$projectId/locations/$location/publishers/google/models/$id" else "models/$id"
        }

        fun validResource(name: String): Boolean = if (!isVertex) DEVELOPER_RESOURCE.matches(name) else {
            val match = VERTEX_RESOURCE.matchEntire(name)
            match != null && projectMatches(match.groupValues[1]) && match.groupValues[2] == location
        }

        fun validModel(name: String): Boolean = if (!isVertex) DEVELOPER_MODEL.matches(name) else {
            val match = VERTEX_MODEL.matchEntire(name)
            match != null && projectMatches(match.groupValues[1]) && match.groupValues[2] == location
        }

        fun matchesModel(name: String, rawModel: String): Boolean = validModel(name) &&
            name.substringAfterLast('/') == requestModel(rawModel).substringAfterLast('/')

        // Vertex returns project numbers for names, even when the request used a project ID.
        // Operations still use collectionRoot, never a project/origin taken from a resource name.
        private fun projectMatches(value: String): Boolean = value == projectId ||
            projectId?.all(Char::isDigit) == false && PROJECT_NUMBER.matches(value)
    }

    fun select(baseUrl: String): Route {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
            ?: throw StError.Validation("invalid Gemini cache origin")
        if (uri.scheme != "https" || uri.port !in setOf(-1, 443) || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) unsupported()
        if (uri.host == "generativelanguage.googleapis.com" && uri.path.orEmpty().trimEnd('/') in setOf("", "/v1beta"))
            return Route("https://${uri.rawAuthority}/v1beta", null, null)
        val path = VERTEX_ROOT.matchEntire(uri.rawPath.orEmpty().trimEnd('/')) ?: unsupported()
        val version = path.groupValues[1]
        val project = path.groupValues[2]
        val location = path.groupValues[3]
        val host = if (location == "global") "aiplatform.googleapis.com" else "$location-aiplatform.googleapis.com"
        if (uri.host != host) unsupported()
        return Route("https://${uri.rawAuthority}/$version/projects/$project/locations/$location", project, location)
    }

    private const val PROJECT = "(?:[a-z][a-z0-9-]{4,28}[a-z0-9]|[0-9]{6,30})"
    private const val LOCATION = "[a-z][a-z0-9-]{0,62}"
    private const val MODEL = "[A-Za-z0-9][A-Za-z0-9._-]{0,255}"
    private const val RESOURCE = "[A-Za-z0-9_-]{1,256}"
    private val MODEL_ID = Regex(MODEL)
    private val PROJECT_NUMBER = Regex("[0-9]{6,30}")
    private val DEVELOPER_RESOURCE = Regex("cachedContents/$RESOURCE")
    private val DEVELOPER_MODEL = Regex("models/$MODEL")
    private val VERTEX_ROOT = Regex("/(v1|v1beta1)/projects/($PROJECT)/locations/($LOCATION)/publishers/google")
    private val VERTEX_RESOURCE = Regex("projects/($PROJECT)/locations/($LOCATION)/cachedContents/$RESOURCE")
    private val VERTEX_MODEL = Regex("projects/($PROJECT)/locations/($LOCATION)/publishers/google/models/$MODEL")
    private fun unsupported(): Nothing = throw StError.Unsupported("cachedContents requires official Gemini v1beta or Vertex Google native routes")
}
