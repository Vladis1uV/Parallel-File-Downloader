package org.example

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.headers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DownloadConfig(
    val chunkSize: Long = 4L * 1024 * 1024,
    val maxParallelism: Int = 8,
    val retries: Int = 3,
    val timeoutPerChunk: Duration = 30.seconds,
)

data class FileMetadata(
    val contentLength: Long,
    val supportsRanges: Boolean,
)

class FileDownloader(private val client: HttpClient, private val config: DownloadConfig = DownloadConfig()) {

    private suspend fun fetchMetadata(url: String): FileMetadata {
        val response: HttpResponse = client.head(url)
        val contentLength = response.contentLength() ?: error("Server did not return Content-Length for $url")
        val supportsRanges = response.headers[HttpHeaders.AcceptRanges] ?.contains("bytes") == true
        return FileMetadata(contentLength, supportsRanges)
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Throwable? = null
        var delayMs = 100L
        repeat(config.retries + 1) { attempt ->
            if (attempt > 0) {
                delay(delayMs)
                delayMs *= 4
            }
            try {
                return block()
            } catch (e: TimeoutCancellationException) {
                lastError = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: error("withRetry block never executed")
    }

    private suspend fun downloadChunk(url: String, chunk: Chunk, channel: FileChannel) {
        val bytes: ByteArray = withRetry {
            withTimeout(config.timeoutPerChunk) {
                val response = client.get(url) {
                    headers {
                        append(HttpHeaders.Range, "bytes=${chunk.start}-${chunk.endInclusive}")
                    }
                }
                check(response.status.value in 200..299) { "Chunk ${chunk.index} failed: HTTP ${response.status.value}" }
                response.body<ByteArray>()
            }
        }

        withContext(Dispatchers.IO) {
            channel.write(ByteBuffer.wrap(bytes), chunk.start)
        }
    }

    suspend fun download(url: String, destination: String) {
        val metadata = fetchMetadata(url)
        check(metadata.supportsRanges) { "Server does not advertise Accept-Ranges: bytes for $url" }

        val chunks = planChunks(metadata.contentLength, config.chunkSize)
        val parallelism = Semaphore(config.maxParallelism)

        RandomAccessFile(destination, "rw").use { raf ->
            raf.setLength(metadata.contentLength)
            val channel = raf.channel

            coroutineScope {
                chunks.map { chunk ->
                    async {
                        parallelism.withPermit {
                            downloadChunk(url, chunk, channel)
                        }
                    }
                }.awaitAll()
            }
        }
    }

}
