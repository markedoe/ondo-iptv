package com.markedoe.ondo.core.net

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * [HttpClient] backed by OkHttp. Cancelling the calling coroutine cancels the request.
 * `file:` URLs are read from disk. Requests without a `User-Agent` use [DEFAULT_IPTV_USER_AGENT].
 */
class OkHttpClientAdapter(
    private val client: OkHttpClient = defaultClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
        fetch(url, headers) { readLimited(it, MAX_BODY_SIZE) }

    /** Streams the answer to [file]: a large guide never sits whole in memory. */
    override suspend fun download(url: String, headers: Map<String, String>, file: File) =
        fetch(url, headers) { copyLimited(it, file, MAX_BODY_SIZE) }

    /** Opens `url` (http, https or file) and hands its body to [consume], on the I/O thread that reads it. */
    private suspend fun <T> fetch(url: String, headers: Map<String, String>, consume: (InputStream) -> T): T {
        if (url.startsWith("file:", ignoreCase = true)) {
            val file = try {
                File(URI(url))
            } catch (e: IllegalArgumentException) {
                throw HttpException.InvalidUrl(url)
            } catch (e: java.net.URISyntaxException) {
                throw HttpException.InvalidUrl(url)
            }
            return withContext(ioDispatcher) { consume(file.inputStream()) }
        }
        val httpUrl = url.toHttpUrlOrNull() ?: throw HttpException.InvalidUrl(url)
        val request = Request.Builder().url(httpUrl).headers(requestHeaders(headers)).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(HttpException.BadStatus(it.code))
                            return
                        }
                        val body = try {
                            consume(it.body.byteStream())
                        } catch (e: IOException) {
                            continuation.resumeWithException(e)
                            return
                        } catch (e: Exception) {
                            // A body OkHttp cannot read (a negative Content-Length…): on OkHttp's thread, it would stop the app.
                            continuation.resumeWithException(ProtocolException("Unreadable answer: ${e.message}").apply { initCause(e) })
                            return
                        } catch (e: OutOfMemoryError) {
                            // Thrown on OkHttp's thread, it would stop the app: the caller gets it instead.
                            continuation.resumeWithException(e)
                            return
                        }
                        continuation.resume(body)
                    }
                }
            })
        }
    }

    companion object {
        /** Largest response read into memory: far above any real playlist or guide, below what would stop the app. */
        const val MAX_BODY_SIZE = 256L * 1024 * 1024

        internal fun readLimited(input: InputStream, limit: Long): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(256 * 1024)
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > limit) throw HttpException.TooLarge(limit)
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }

        internal fun copyLimited(input: InputStream, file: File, limit: Long) {
            input.use { stream ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) throw HttpException.TooLarge(limit)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        /**
         * Request headers. Provider values with accents (`#EXTVLCOPT:http-user-agent=Télé…`) are sent as-is
         * instead of failing the request; headers with an invalid name are skipped.
         */
        internal fun requestHeaders(headers: Map<String, String>): Headers {
            val builder = Headers.Builder()
            for ((name, value) in headers) {
                try {
                    builder.set(name, value)
                } catch (_: IllegalArgumentException) {
                    try {
                        builder.addUnsafeNonAscii(name, value)
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
            if (builder["User-Agent"] == null) builder.set("User-Agent", DEFAULT_IPTV_USER_AGENT)
            return builder.build()
        }

        /**
         * 60 s per read / connect (provider lists can be slow), 10 min for a whole download. Every request
         * without a `User-Agent` (artwork too, which the image loader sends through this client) uses
         * [DEFAULT_IPTV_USER_AGENT]: providers that filter on it would otherwise refuse some of them.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(
                    if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", DEFAULT_IPTV_USER_AGENT).build() else request,
                )
            }
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .build()
    }
}
