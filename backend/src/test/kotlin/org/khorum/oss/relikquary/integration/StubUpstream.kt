package org.khorum.oss.relikquary.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * A deterministic, in-process upstream Maven repository for proxy tests (feature 006), built on the
 * JDK HTTP server (no dependency). It serves seeded artifact bytes, can simulate a not-found
 * (removed) path, and can simulate an upstream failure (500) — all without real network access, so
 * proxy round-trips run offline and CI-safe.
 */
class StubUpstream {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val files = HashMap<String, ByteArray>()
    private val failing = HashSet<String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start(): StubUpstream {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        return this
    }

    fun stop() = server.stop(0)

    /** Seeds [bytes] at [path] (Maven layout, no leading slash) plus its `.sha1` sibling. */
    fun seed(path: String, bytes: ByteArray) {
        val key = path.trimStart('/')
        files[key] = bytes
        files["$key.sha1"] = sha1Hex(bytes).toByteArray()
    }

    /** Removes a path (and its `.sha1`) so the upstream answers 404 for it. */
    fun remove(path: String) {
        val key = path.trimStart('/')
        files.remove(key)
        files.remove("$key.sha1")
    }

    /** Makes the upstream answer 500 for [path] (simulating an outage/error). */
    fun fail(path: String) {
        failing += path.trimStart('/')
    }

    private fun handle(exchange: HttpExchange) {
        val key = exchange.requestURI.path.trimStart('/')
        when {
            key in failing -> exchange.sendResponseHeaders(HTTP_ERROR, -1)
            else -> {
                val body = files[key]
                if (body == null) {
                    exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                } else {
                    exchange.sendResponseHeaders(HTTP_OK, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
        }
        exchange.close()
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_ERROR = 500

        fun sha1Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
