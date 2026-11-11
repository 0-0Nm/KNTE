package com.knte.core.chat

/**
 * Validated HTTPS chat endpoint (pure syntax check; never probes the network).
 *
 * Rules (functional mirror of the KNTE shell AI candidate, tightened for the pure domain):
 *  - `https` only; plaintext `http` fails closed;
 *  - no userinfo (`user:pass@host`), no query (`?`), no fragment (`#`);
 *  - the host must be a *public candidate* in shape: a dotted DNS name or a public IPv4 literal.
 *    `localhost`, `.local`/`.internal`/`.lan`/`.home`/`.localdomain`, single-label names,
 *    private/loopback IPv4 and IPv6 literals are rejected. Reachability is NOT verified here
 *    and no DNS or network probe is ever issued from this domain;
 *  - the URL is the COMPLETE request address: the domain never joins or appends path segments
 *    (no accidental different path), and the path must end with `/chat/completions`
 *    because this subset supports nothing else.
 */
class ChatEndpoint private constructor(
    val url: String,
    val host: String,
) {
    override fun toString(): String = "ChatEndpoint(url=$url)"

    companion object {
        const val MAX_URL_CHARS = 512
        const val MAX_PATH_CHARS = 320
        const val REQUIRED_PATH_SUFFIX = "/chat/completions"
        private val HOST_LABEL = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")
        private val IPV4 = Regex("(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])")

        fun parse(rawUrl: String): ChatEndpoint {
            require(rawUrl.isNotEmpty()) { "CHAT_ENDPOINT_EMPTY" }
            require(rawUrl.length <= MAX_URL_CHARS) { "CHAT_ENDPOINT_TOO_LONG" }
            require(rawUrl.none { it.code in 0x00..0x20 || it.code == 0x7F }) { "CHAT_ENDPOINT_CONTROL_CHAR" }
            val schemeEnd = rawUrl.indexOf("://")
            require(schemeEnd >= 0) { "CHAT_ENDPOINT_SCHEME_INVALID" }
            when (rawUrl.substring(0, schemeEnd).lowercase()) {
                "https" -> {}
                "http" -> throw IllegalArgumentException("CHAT_ENDPOINT_HTTP_FORBIDDEN")
                else -> throw IllegalArgumentException("CHAT_ENDPOINT_SCHEME_INVALID")
            }
            val rest = rawUrl.substring(schemeEnd + 3)
            require(rest.isNotEmpty()) { "CHAT_ENDPOINT_HOST_MISSING" }
            val slash = rest.indexOf('/')
            val authority = if (slash < 0) rest else rest.substring(0, slash)
            val tail = if (slash < 0) "" else rest.substring(slash)
            require(authority.isNotEmpty()) { "CHAT_ENDPOINT_HOST_MISSING" }
            require('@' !in authority) { "CHAT_ENDPOINT_USERINFO_FORBIDDEN" }
            // IPv6 literals (bracketed or bare) are not public-candidate shapes in this subset.
            require('[' !in authority && ']' !in authority) { "CHAT_ENDPOINT_HOST_NOT_PUBLIC" }
            var host = authority
            var port = ""
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1)
                require(port.isNotEmpty() && port.length <= 5 && port.all { it in '0'..'9' }) { "CHAT_ENDPOINT_PORT_INVALID" }
                require(port.toInt() in 1..65535) { "CHAT_ENDPOINT_PORT_INVALID" }
            }
            require(host.isNotEmpty()) { "CHAT_ENDPOINT_HOST_MISSING" }
            val hostLower = host.lowercase()
            require(isPublicHostShape(hostLower)) { "CHAT_ENDPOINT_HOST_NOT_PUBLIC" }
            // The complete request address must be present; query and fragment fail closed.
            require(tail.isNotEmpty()) { "CHAT_ENDPOINT_PATH_REQUIRED" }
            require('?' !in tail) { "CHAT_ENDPOINT_QUERY_FORBIDDEN" }
            require('#' !in tail) { "CHAT_ENDPOINT_FRAGMENT_FORBIDDEN" }
            require(tail.length <= MAX_PATH_CHARS) { "CHAT_ENDPOINT_PATH_TOO_LONG" }
            require(isValidPath(tail)) { "CHAT_ENDPOINT_PATH_INVALID" }
            require(tail.endsWith(REQUIRED_PATH_SUFFIX)) { "CHAT_ENDPOINT_NOT_CHAT_COMPLETIONS" }
            val canonical = "https://" + hostLower + (if (port.isEmpty()) "" else ":$port") + tail
            return ChatEndpoint(canonical, hostLower)
        }

        /** Shape check only. No DNS resolution, no network probe, no reachability claim. */
        private fun isPublicHostShape(hostLower: String): Boolean {
            if (hostLower == "localhost" || hostLower.endsWith(".local") || hostLower.endsWith(".internal") ||
                hostLower.endsWith(".lan") || hostLower.endsWith(".home") || hostLower.endsWith(".localdomain")
            ) {
                return false
            }
            if (IPV4.matches(hostLower)) return !isPrivateIpv4(hostLower)
            // Never reinterpret malformed/octal-looking numeric IPv4 as a DNS name.
            if (hostLower.split('.').all { label -> label.isNotEmpty() && label.all { it in '0'..'9' } }) return false
            if ('[' in hostLower || ']' in hostLower || ':' in hostLower) return false // IPv6 literal unsupported
            if ('.' !in hostLower) return false // a public candidate must be a dotted name
            return hostLower.split('.').all { HOST_LABEL.matches(it) }
        }

        private fun isPrivateIpv4(ip: String): Boolean {
            val o = ip.split('.').map { it.toInt() }
            return o[0] == 0 || o[0] == 10 || o[0] == 127 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254) ||
                (o[0] == 100 && o[1] in 64..127)
        }

        private fun isValidPath(path: String): Boolean {
            var i = 1 // the leading '/'
            while (i < path.length) {
                val c = path[i]
                if (c == '%') {
                    if (i + 2 >= path.length || !isHexDigit(path[i + 1]) || !isHexDigit(path[i + 2])) return false
                    i += 3
                    continue
                }
                val allowed = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                    c == '/' || c == '.' || c == '-' || c == '_' || c == '~' ||
                    c == '$' || c == '&' || c == '+' || c == ',' || c == ';' || c == '=' ||
                    c == ':' || c == '@' || c == '!' || c == '\'' || c == '(' || c == ')' || c == '*'
                if (!allowed) return false
                i++
            }
            return true
        }

        private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }
}
