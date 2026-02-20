package com.example.familysafety.avatar

import fi.iki.elonen.NanoHTTPD

/**
 * Embedded HTTP server that serves this device's avatar over LAN.
 *
 * Endpoints:
 *   GET /avatar  → 200 + JPEG bytes (or 404 if no avatar set)
 *
 * Port 0 tells the OS to pick a free ephemeral port; read back via [listeningPort].
 */
class AvatarHttpServer(
    private val store: AvatarStore,
    private val myMemberId: String
) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/avatar" && session.method == Method.GET) {
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
