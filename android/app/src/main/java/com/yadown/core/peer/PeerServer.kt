package com.yadown.core.peer

import com.yadown.core.transfer.XxHash64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.serialization.encodeToString

/** 本机愿意共享给已配对设备的内容。只有已完成且长度与记录一致的文件才会被服务。 */
data class SharedContent(
    val contentId: String,
    val contentKey: String,
    val size: Long,
    val etag: String?,
    val lastModified: String?,
    val fileName: String,
    val mimeType: String?,
    val file: File
) {
    fun servable(): Boolean = size > 0 && file.isFile && file.length() == size
}

/**
 * TLS Range 服务端，见 docs/ARCHITECTURE.md §5.2 的 peer 信任模型。
 *
 * 只实现 GET（客户端只会发 GET + Range）；每个请求的区间上限受 [maxRequestBytes] 约束，
 * 防止一次请求把大文件读进内存。所有响应都在 TLS 连接内完成，不存在明文回退路径。
 */
class PeerServer(
    private val identity: PeerIdentity,
    private val contents: () -> List<SharedContent>,
    private val maxConnections: Int = 8,
    private val maxRequestBytes: Long = 2L * 1024 * 1024,
    private val socketTimeoutMs: Int = 15_000
) : Closeable {

    private var serverSocket: SSLServerSocket? = null

    @Volatile
    private var workers: ThreadPoolExecutor? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start(port: Int = 0): Int {
        serverSocket?.let { return it.localPort }
        val factory = PeerTls.serverContext(identity).serverSocketFactory
        val socket = factory.createServerSocket(
            port,
            MAX_BACKLOG,
            InetAddress.getByName("0.0.0.0")
        ) as SSLServerSocket
        socket.reuseAddress = true
        // AndroidKeyStore 的 RSA 密钥只授权 PKCS1 签名，TLS 1.3 需要 RSA-PSS；
        // 固定 TLS 1.2 可避免握手在部分设备上失败，安全强度由 ECDHE_RSA + AEAD 保证。
        socket.enabledProtocols = arrayOf("TLSv1.2")
        serverSocket = socket
        workers = ThreadPoolExecutor(
            0,
            maxConnections,
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "yadown-peer").apply { isDaemon = true } }
        )
        thread(start = true, isDaemon = true, name = "yadown-peer-accept") {
            acceptLoop(socket)
        }
        return socket.localPort
    }

    override fun close() {
        val socket = synchronized(this) {
            val current = serverSocket
            serverSocket = null
            current
        }
        runCatching { socket?.close() }
        workers?.shutdownNow()
        workers = null
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() as SSLSocket }.getOrNull() ?: break
            val pool = workers
            if (pool == null) {
                runCatching { client.close() }
                break
            }
            if (pool.activeCount >= maxConnections) {
                runCatching { client.close() }
                continue
            }
            val submitted = runCatching {
                pool.execute { serve(client) }
            }
            if (submitted.isFailure) runCatching { client.close() }
        }
    }

    private fun serve(socket: SSLSocket) {
        runCatching {
            socket.use { connection ->
                connection.soTimeout = socketTimeoutMs
                connection.startHandshake()
                val input = BufferedInputStream(connection.inputStream)
                val output = BufferedOutputStream(connection.outputStream)
                var served = 0
                while (served < MAX_KEEP_ALIVE_REQUESTS) {
                    val request = readRequest(input) ?: break
                    val response = route(request)
                    val keepAlive = !request.closeAfterResponse &&
                        !response.closeAfter &&
                        served + 1 < MAX_KEEP_ALIVE_REQUESTS
                    writeResponse(output, response, keepAlive)
                    served++
                    if (!keepAlive) break
                }
            }
        }
    }

    private fun route(request: RawRequest): Response {
        if (request.method != "GET") {
            return Response(405, listOf("Allow" to "GET"), closeAfter = true)
        }
        val path = request.path.substringBefore('?')
        val target = PeerPaths.parse(path)
            ?: return Response(400, emptyList(), closeAfter = true)
        return when (target) {
            is PeerRequest.Lookup -> lookup(target.contentKey)
            is PeerRequest.Content -> content(target.contentId, request.headers)
        }
    }

    private fun lookup(contentKey: String): Response {
        val content = contents().firstOrNull {
            it.contentKey.equals(contentKey, ignoreCase = true) && it.servable()
        } ?: return Response(404, emptyList())
        val manifest = PeerManifest(
            contentId = content.contentId,
            contentKey = content.contentKey,
            size = content.size,
            etag = content.etag,
            lastModified = content.lastModified,
            fileName = content.fileName,
            mimeType = content.mimeType
        )
        val body = PeerProtocol.json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        return Response(
            200,
            listOf("Content-Type" to PeerHeaders.CONTENT_TYPE_JSON, "Accept-Ranges" to "bytes"),
            body = body
        )
    }

    private fun content(contentId: String, headers: Map<String, String>): Response {
        val content = contents().firstOrNull { it.contentId == contentId && it.servable() }
            ?: return Response(404, emptyList())
        val range = parseRange(headers["range"], content.size)
            ?: return rangeNotSatisfiable(content.size)
        if (range.length > maxRequestBytes) return rangeNotSatisfiable(content.size)

        val bytes = runCatching {
            ByteArray(range.length.toInt()).also { buffer ->
                RandomAccessFile(content.file, "r").use { file ->
                    file.seek(range.start)
                    file.readFully(buffer)
                }
            }
        }.getOrNull() ?: return Response(500, emptyList(), closeAfter = true)

        val hash = XxHash64.hash(bytes)
        val hashHex = java.lang.Long.toHexString(hash).padStart(16, '0')
        val responseHeaders = buildList {
            add("Content-Type" to (content.mimeType ?: PeerHeaders.CONTENT_TYPE_BYTES))
            add("Content-Range" to "bytes ${range.start}-${range.endInclusive}/${content.size}")
            add("Accept-Ranges" to "bytes")
            add(PeerHeaders.XX_HASH to hashHex)
            content.etag?.let { add("ETag" to it) }
            content.lastModified?.let { add("Last-Modified" to it) }
        }
        return Response(206, responseHeaders, bytes)
    }

    private fun rangeNotSatisfiable(size: Long): Response =
        Response(
            416,
            listOf("Content-Range" to "bytes */$size", "Accept-Ranges" to "bytes")
        )

    private fun parseRange(header: String?, size: Long): ByteRange? {
        val value = header?.trim() ?: return null
        val match = SINGLE_RANGE.matchEntire(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val rawEnd = match.groupValues[2]
        val endInclusive = if (rawEnd.isEmpty()) size - 1 else rawEnd.toLongOrNull() ?: return null
        if (size <= 0 || start < 0 || start >= size || endInclusive < start || endInclusive >= size) {
            return null
        }
        return ByteRange(start, endInclusive)
    }

    private fun readRequest(input: InputStream): RawRequest? {
        val requestLine = readLine(input, MAX_HEADER_BYTES) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        var consumed = requestLine.length
        while (true) {
            val line = readLine(input, MAX_HEADER_BYTES) ?: return null
            if (line.isEmpty()) break
            consumed += line.length
            if (consumed > MAX_HEADER_BYTES) return null
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        val connection = headers["connection"]?.lowercase()
        return RawRequest(
            method = parts[0].uppercase(),
            path = parts[1],
            headers = headers,
            closeAfterResponse = connection == "close" || parts.getOrNull(2) == "HTTP/1.0"
        )
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) {
                if (buffer.endsWith('\r')) buffer.setLength(buffer.length - 1)
                return buffer.toString()
            }
            if (buffer.length >= limit) return null
            buffer.append(byte.toChar())
        }
    }

    private fun writeResponse(output: OutputStream, response: Response, keepAlive: Boolean) {
        val body = response.body ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Internal Server Error"
    }

    private data class RawRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val closeAfterResponse: Boolean
    )

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    private data class Response(
        val code: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray? = null,
        val closeAfter: Boolean = false
    )

    private companion object {
        const val MAX_BACKLOG = 8
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_KEEP_ALIVE_REQUESTS = 32
        val SINGLE_RANGE = Regex("""^bytes=(\d+)-(\d*)$""")
    }
}
