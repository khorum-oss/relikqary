package org.khorum.oss.relikquary.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/**
 * A deterministic, in-process upstream Maven repository for proxy tests (feature 006), built on the
 * JDK HTTP server (no dependency). It serves seeded artifact bytes, can simulate a not-found
 * (removed) path, and can simulate an upstream failure (500) — all without real network access, so
 * proxy round-trips run offline and CI-safe.
 *
 * For streaming tests (feature 015) it can also serve a body in two halves released by a latch (to
 * prove the client receives bytes before the upstream completes) and a truncated body that declares a
 * Content-Length but closes early (to prove a partial transfer is never cached).
 */
class StubUpstream {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val files = HashMap<String, ByteArray>()
    private val failing = HashSet<String>()
    private val gated = HashMap<String, Gated>()
    private val truncated = HashMap<String, Truncated>()

    private class Gated(val first: ByteArray, val rest: ByteArray, val release: CountDownLatch)
    private class Truncated(val declaredLength: Long, val partial: ByteArray)

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
        gated.remove(key)
        truncated.remove(key)
        files[key] = bytes
        files["$key.sha1"] = sha1Hex(bytes).toByteArray()
    }

    /** Removes a path (and its `.sha1`) so the upstream answers 404 for it. */
    fun remove(path: String) {
        val key = path.trimStart('/')
        files.remove(key)
        files.remove("$key.sha1")
        gated.remove(key)
        truncated.remove(key)
    }

    /** Makes the upstream answer 500 for [path] (simulating an outage/error). */
    fun fail(path: String) {
        failing += path.trimStart('/')
    }

    /**
     * Serves [path] as `[first] + [rest]`: writes and flushes [first], waits on [release], then writes
     * [rest]. The full body equals `first + rest`; reading the client side before counting down
     * [release] proves the client got bytes before the upstream finished (feature 015 overlap).
     */
    fun seedGated(path: String, first: ByteArray, rest: ByteArray, release: CountDownLatch) {
        gated[path.trimStart('/')] = Gated(first, rest, release)
    }

    /**
     * Serves [path] declaring a Content-Length of [declaredLength] but writing only [partial] then
     * closing — a truncated transfer the proxy must never cache (feature 015).
     */
    fun seedTruncated(path: String, declaredLength: Long, partial: ByteArray) {
        truncated[path.trimStart('/')] = Truncated(declaredLength, partial)
    }

    private fun handle(exchange: HttpExchange) {
        val key = exchange.requestURI.path.trimStart('/')
        when {
            key in failing -> exchange.sendResponseHeaders(HTTP_ERROR, -1)
            gated.containsKey(key) -> serveGated(exchange, gated.getValue(key))
            truncated.containsKey(key) -> serveTruncated(exchange, truncated.getValue(key))
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

    private fun serveGated(exchange: HttpExchange, g: Gated) {
        exchange.sendResponseHeaders(HTTP_OK, (g.first.size + g.rest.size).toLong())
        exchange.responseBody.let { out ->
            out.write(g.first)
            out.flush()
            g.release.await()
            out.write(g.rest)
            out.close()
        }
    }

    private fun serveTruncated(exchange: HttpExchange, t: Truncated) {
        // Declare more than we send, then close: the client sees a premature end of a fixed-length body.
        exchange.sendResponseHeaders(HTTP_OK, t.declaredLength)
        exchange.responseBody.let { out ->
            out.write(t.partial)
            out.flush()
            // Deliberately do not write the remaining declared bytes; exchange.close() truncates.
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_ERROR = 500

        fun sha1Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
