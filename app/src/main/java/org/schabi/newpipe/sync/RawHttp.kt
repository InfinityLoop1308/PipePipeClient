package org.schabi.newpipe.sync

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal HTTP/1.0 client on a plain [Socket].
 *
 * The debug sync server is usually a LAN box without TLS. Android's cleartext policy is enforced
 * by the platform HTTP stacks (OkHttp, HttpURLConnection, WebView), not by sockets, so talking
 * HTTP ourselves reaches http:// servers without touching usesCleartextTraffic or the network
 * security config. https:// is still supported, with normal certificate and hostname checks.
 *
 * HTTP/1.0 + Connection: close keeps the response framing simple: servers must not answer a 1.0
 * request with chunked encoding, so the body is Content-Length bytes or everything until EOF.
 */
object RawHttp {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_HEADER_LINE = 16 * 1024

    class Response(val code: Int, val headers: Map<String, String>)

    /**
     * Sends a request and streams the response body into [sink].
     * [body] is sent as-is with its file length as Content-Length.
     */
    @Throws(IOException::class)
    fun request(
        method: String,
        uri: URI,
        headers: Map<String, String> = emptyMap(),
        body: File? = null,
        sink: OutputStream? = null
    ): Response {
        val secure = when (uri.scheme?.lowercase()) {
            "http" -> false
            "https" -> true
            else -> throw IOException("Unsupported scheme: ${uri.scheme}")
        }
        val host = uri.host ?: throw IOException("No host in $uri")
        val port = if (uri.port != -1) uri.port else if (secure) 443 else 80

        val plain = Socket()
        val socket = try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            if (secure) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true) as SSLSocket
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    throw IOException("Certificate does not match $host")
                }
                ssl
            } else {
                plain
            }
        } catch (e: IOException) {
            plain.close()
            throw e
        }

        socket.use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            val out = s.getOutputStream().buffered()
            val path = (uri.rawPath?.ifEmpty { null } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val hostHeader = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.0\r\n")
                .append("Host: ").append(hostHeader).append("\r\n")
                .append("Connection: close\r\n")
                .append("User-Agent: PipePipe-sync\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            if (body != null) head.append("Content-Length: ").append(body.length()).append("\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            body?.inputStream()?.use { it.copyTo(out) }
            out.flush()

            val input = s.getInputStream().buffered()
            val status = readLine(input) ?: throw IOException("Empty response")
            val parts = status.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
                throw IOException("Bad status line: $status")
            }
            val code = parts[1].toIntOrNull() ?: throw IOException("Bad status line: $status")

            val respHeaders = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("Truncated headers")
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) respHeaders[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            if (respHeaders["transfer-encoding"]?.contains("chunked", true) == true) {
                throw IOException("Chunked responses are not supported")
            }

            val length = respHeaders["content-length"]?.toLongOrNull()
            val target = sink ?: NullOutputStream
            val copied = copy(input, target, length ?: Long.MAX_VALUE)
            if (length != null && copied != length) {
                throw IOException("Body truncated: $copied of $length bytes")
            }
            return Response(code, respHeaders)
        }
    }

    private fun copy(input: InputStream, out: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.flush()
        return total
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_HEADER_LINE) throw IOException("Header line too long")
        }
        return buf.toString("ISO-8859-1")
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
