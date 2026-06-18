package com.example.familysafety.avatar

import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/**
 * Embedded HTTP server that serves this device's avatar over LAN.
 *
 * Endpoints:
 *   GET /avatar?token=<tok>  → 200 + JPEG bytes (or 403/404)
 *
 * A random 16-byte token is generated at construction and published in the
 * NSD TXT record so that only group members who received the record can fetch.
 * Port 0 tells the OS to pick a free ephemeral port; read back via [listeningPort].
 */
class AvatarHttpServer(
    private val store: AvatarStore,
    private val myMemberId: String
) : NanoHTTPD(0) {

    val token: String = run {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/avatar" && session.method == Method.GET) {
            val queryToken = session.parameters["token"]?.firstOrNull()
            if (queryToken != token) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
            }
            val bytes = store.load(myMemberId)
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No avatar"
                )
            return newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                bytes.inputStream(),
                bytes.size.toLong()
            )
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }
}
