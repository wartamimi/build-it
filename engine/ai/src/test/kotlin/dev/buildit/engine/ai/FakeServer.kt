package dev.buildit.engine.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** A local HTTP server that records requests and answers with a canned status and body. */
class FakeServer(private val status: Int, private val contentType: String, private val body: String) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    val requests = mutableListOf<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            synchronized(requests) { requests += Request(ex.requestMethod, ex.requestURI.path, headers, ex.requestBody.readBytes().decodeToString()) }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
