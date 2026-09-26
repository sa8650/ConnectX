package com.connectx.gateway.data

import java.net.URI
import java.util.Locale

/** Canonical ConnectX Control website origin shared by sign-in, the device
 * API and public update checks. ConnectX talks ONLY to this website - never
 * to EMS or any other product backend directly.
 *
 * The app ships with the official ConnectX Control address built in
 * ([BUILT_IN]); it is used automatically. The URL entry screen appears ONLY
 * when that address cannot be reached (a self-hosted ConnectX Control, for
 * example) - see LoginScreen.
 * A scheme alone ("https:/", "https://", "https") is not a website. Never
 * turn it into a bogus host named "https" by blindly prefixing https://. */
object GatewayUrl {
    /** The official ConnectX Control deployment, compiled into the app. */
    const val BUILT_IN = "https://connectxweb.pages.dev"

    const val HELP = "Enter the FULL deployed ConnectX Control website address, e.g. $BUILT_IN (not https:/ or a GitHub link)."

    fun normalize(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) throw IllegalArgumentException(HELP)
        val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(raw)
        val input = if (hasScheme) raw else "https://$raw"
        val parsed = try { URI(input) } catch (_: Exception) { throw IllegalArgumentException(HELP) }
        val scheme = parsed.scheme?.lowercase(Locale.US)
        val host = parsed.host?.lowercase(Locale.US)
        if (scheme !in listOf("https", "http") || host.isNullOrBlank() ||
            host == "https" || host == "http" || parsed.rawUserInfo != null ||
            parsed.port > 65535 || (parsed.rawPath.orEmpty().trimEnd('/').let { it.isNotEmpty() && it != "/api" })) {
            throw IllegalArgumentException(HELP)
        }
        // GitHub stores the source, not the deployed ConnectX Control API.
        if (host == "github.com" || host == "www.github.com")
            throw IllegalArgumentException("Use your deployed ConnectX Control website URL, NOT the GitHub project link.")
        if (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "[::1]")
            throw IllegalArgumentException("localhost points to this phone. Use your publicly deployed ConnectX Control website URL instead.")
        if (!host.contains('.') && !host.contains(':')) throw IllegalArgumentException(HELP)
        // Keep an explicit port (for genuine self-hosted sites); discard /api,
        // query strings and fragments copied from the public app-store page.
        return "$scheme://${parsed.rawAuthority}"
    }
}
