package com.knte.core.chat

/**
 * Secure injection interface, held only by the transport implementation. It is invoked at the
 * moment of the one real send and the returned key goes straight into the HTTP header.
 * The domain never calls it, never caches it and never prints its return value;
 * [ChatTransportRequest.credentialRef] is an opaque reference, not a secret.
 */
fun interface ChatSecretSource {
    fun secretFor(credentialRef: String): String
}

/**
 * Transport contract for EXACTLY ONE request:
 *  - synchronous, one HTTP request, no internal retry, no redirects, no re-authentication;
 *  - the response body read must be bounded (<= [ChatResponseCodec.MAX_RESPONSE_CHARS]);
 *  - a definite provider refusal returns [ChatTransportResult.Refused]; a result that cannot be
 *    known (timeout, broken IO) returns [ChatTransportResult.Indeterminate]; do not throw;
 *  - code/safeSummary carry public information only - never response bodies, URL parameters or keys.
 *
 * Lifecycle, threads and IO belong to the app; this pure domain ships no real network code
 * and no test ever touches the network (fake interface only).
 */
interface ChatTransport {
    fun send(request: ChatTransportRequest): ChatTransportResult
}

/**
 * One prepared send. `endpointUrl` is the validated COMPLETE address and is used verbatim;
 * the domain never joins or appends path segments. `body` is the full JSON request payload.
 * `toString` stays compact and never contains the body.
 */
data class ChatTransportRequest(
    val endpointUrl: String,
    val method: String,
    val contentType: String,
    val accept: String,
    val body: String,
    val credentialRef: String,
) {
    override fun toString(): String =
        "ChatTransportRequest(url=$endpointUrl, method=$method, contentType=$contentType, " +
            "accept=$accept, bodyChars=${body.length}, credentialRef=$credentialRef)"
}

sealed class ChatTransportResult {
    /** HTTP produced a definite response (2xx or not). The body must be bounded by the transport. */
    data class Delivered(val httpStatus: Int, val body: String) : ChatTransportResult()

    /** The request was definitively refused (auth/quota/4xx/5xx). Never auto-retried by the domain. */
    data class Refused(val code: String, val safeSummary: String) : ChatTransportResult()

    /** The outcome is unknown (timeout/interrupted IO). The provider may already have billed. */
    data class Indeterminate(val code: String, val safeSummary: String) : ChatTransportResult()
}
