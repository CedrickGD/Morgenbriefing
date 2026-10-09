package com.cedrickgd.morgenbriefing.ai.compat

import com.cedrickgd.morgenbriefing.data.normalizeBaseUrl
import java.net.URI
import java.net.URISyntaxException

/** What is wrong with an address typed for the own server (live validation in the key dialog). */
enum class CustomUrlIssue {
    /** Nothing typed. */
    BLANK,

    /** No `http://` or `https://` in front. */
    NO_SCHEME,

    /** Another scheme (`ftp://`, …). */
    NOT_HTTP,

    /** Not an address at all (no host, spaces, a query). */
    INVALID,

    /** Plain `http://` to a host outside the cleartext allowlist (a raw LAN IP, a public name). */
    CLEARTEXT_HOST,
}

/**
 * The "Eigener Server" (Ollama, LM Studio, vLLM … at home, docs/AI-PROVIDERS.md §7.6): address
 * rules, the cleartext allowlist and the hints the key dialog shows.
 */
object CustomServer {
    /**
     * Hosts plain `http://` may go to — the `domain-config` of `res/xml/network_security_config.xml`
     * (D10), domain → `includeSubdomains`; NetworkSecurityConfigTest keeps both equal. `10.0.2.2` is
     * the emulator's host machine (QA mock servers).
     */
    val CLEARTEXT_HOSTS: Map<String, Boolean> = linkedMapOf(
        "localhost" to false,
        "10.0.2.2" to false,
        "local" to true,
        "lan" to true,
        "home.arpa" to true,
        "fritz.box" to true,
        "internal" to true,
    )

    /**
     * The address as stored: trimmed, without trailing slashes, and `/v1` appended when there is
     * no path at all (`http://nas.fritz.box:11434` → `http://nas.fritz.box:11434/v1`, the
     * OpenAI-compatible root of Ollama and LM Studio); any other path is kept. Null when [raw]
     * is no http(s) address.
     */
    fun normalize(raw: String?): String? {
        val clean = raw?.let(::normalizeBaseUrl) ?: return null
        val path = try {
            URI(clean).rawPath.orEmpty()
        } catch (_: URISyntaxException) {
            return null
        }
        return if (path.isEmpty() || path == "/") "$clean/v1" else clean
    }

    /** The first problem of [raw] as a server address, or null when it can be used. */
    fun issue(raw: String?): CustomUrlIssue? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return CustomUrlIssue.BLANK
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme.isEmpty()) return CustomUrlIssue.NO_SCHEME
        if (scheme != "http" && scheme != "https") return CustomUrlIssue.NOT_HTTP
        val clean = normalize(text) ?: return CustomUrlIssue.INVALID
        if (scheme == "http" && !allowsCleartext(hostOf(clean))) return CustomUrlIssue.CLEARTEXT_HOST
        return null
    }

    /** Plain http to [host] is allowed by the network security config. */
    fun allowsCleartext(host: String?): Boolean {
        val h = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return CLEARTEXT_HOSTS.any { (domain, subdomains) -> h == domain || (subdomains && h.endsWith(".$domain")) }
    }

    /** German hint under the address field for [issue]. */
    fun hint(issue: CustomUrlIssue): String = when (issue) {
        CustomUrlIssue.BLANK -> "Trag die Adresse deines Servers ein, z. B. http://nas.fritz.box:11434/v1."
        CustomUrlIssue.NO_SCHEME -> "Die Adresse beginnt mit http:// oder https://."
        CustomUrlIssue.NOT_HTTP -> "Nur http:// und https:// gehen."
        CustomUrlIssue.INVALID -> "Das sieht nicht nach einer Server-Adresse aus."
        CustomUrlIssue.CLEARTEXT_HOST ->
            "http:// geht nur mit Namen wie nas.fritz.box oder nas.local – nicht mit einer IP-Adresse. Nimm den Namen oder https://."
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: URISyntaxException) {
        null
    }
}
