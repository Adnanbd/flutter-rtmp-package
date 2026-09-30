package com.flutterrtmp.broadcaster.diag

/**
 * Makes an RTMP endpoint safe to log: keeps scheme, host and the app segment, drops the stream key
 * and any query: `rtmp://host/live2/KEY` becomes `rtmp://host/live2/` followed by three asterisks.
 * Never log a raw endpoint (CLAUDE.md, Definition of done).
 */
object EndpointRedactor {
    fun redact(endpoint: String): String {
        if (endpoint.isBlank()) return "(empty)"
        val schemeEnd = endpoint.indexOf("://")
        if (schemeEnd < 0) return "***"
        val rest = endpoint.substring(schemeEnd + 3).substringBefore('?')
        val parts = rest.split('/')
        val host = parts[0].substringAfter('@') // drop user:password@ if present
        val prefix = endpoint.substring(0, schemeEnd + 3) + host
        return when {
            parts.size <= 1 -> prefix
            parts.size == 2 -> "$prefix/***"
            else -> "$prefix/${parts[1]}/***"
        }
    }
}
