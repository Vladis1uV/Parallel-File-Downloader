package org.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FileDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    private val testConfig = DownloadConfig(
        chunkSize = 100,
        maxParallelism = 4,
        retries = 2,
        timeoutPerChunk = 500.milliseconds,
    )

    private fun destination(name: String = "out.bin"): String =
        tempDir.resolve(name).toString()

    private fun headHeaders(contentLength: Int, acceptRanges: Boolean = true) = Headers.build {
        append(HttpHeaders.ContentLength, contentLength.toString())
        if (acceptRanges) append(HttpHeaders.AcceptRanges, "bytes")
    }

    private fun parseRange(header: String): Pair<Int, Int> {
        val match = Regex("""bytes=(\d+)-(\d+)""").matchEntire(header)
            ?: error("Bad Range header: $header")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun rangeServingClient(
        body: ByteArray,
        acceptRanges: Boolean = true,
        onChunkRequest: suspend MockRequestHandleScope.(HttpRequestData) -> Unit = {},
    ): HttpClient = HttpClient(MockEngine { request ->
        when (request.method) {
            HttpMethod.Head -> respond(
                content = byteArrayOf(),
                status = HttpStatusCode.OK,
                headers = headHeaders(body.size, acceptRanges),
            )
            HttpMethod.Get -> {
                onChunkRequest(request)
                val (start, end) = parseRange(request.headers[HttpHeaders.Range]!!)
                respond(
                    content = body.copyOfRange(start, end + 1),
                    status = HttpStatusCode.PartialContent,
                )
            }
            else -> error("Unexpected method ${request.method}")
        }
    })

    @Test
    fun `download assembles the file byte-for-byte`() = runBlocking {
        val body = ByteArray(350) { (it % 251).toByte() }
        val dest = destination()

        FileDownloader(rangeServingClient(body), testConfig).download("http://test/file", dest)

        assertContentEquals(body, Files.readAllBytes(Path.of(dest)))
    }

    @Test
    fun `each chunk requests the planned Range header`() = runBlocking {
        val body = ByteArray(350) { it.toByte() }
        val seenRanges = Collections.synchronizedSet(mutableSetOf<String>())
        val client = rangeServingClient(body) { req ->
            seenRanges += req.headers[HttpHeaders.Range]!!
        }

        FileDownloader(client, testConfig).download("http://test/file", destination())

        assertEquals(
            setOf("bytes=0-99", "bytes=100-199", "bytes=200-299", "bytes=300-349"),
            seenRanges.toSet(),
        )
    }

    @Test
    fun `fails when server does not advertise Accept-Ranges`() = runBlocking {
        val client = rangeServingClient(ByteArray(100), acceptRanges = false)

        assertFailsWith<IllegalStateException> {
            FileDownloader(client, testConfig).download("http://test/file", destination())
        }
    }

    @Test
    fun `concurrent chunks never exceed maxParallelism`() = runBlocking {
        val body = ByteArray(1000)
        val inFlight = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val client = rangeServingClient(body) {
            val current = inFlight.incrementAndGet()
            maxObserved.updateAndGet { maxOf(it, current) }
            try {
                delay(50)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        FileDownloader(client, testConfig.copy(chunkSize = 100, maxParallelism = 3))
            .download("http://test/file", destination())

        assertTrue(maxObserved.get() <= 3, "saw ${maxObserved.get()} concurrent chunks, cap was 3")
        assertTrue(maxObserved.get() >= 2, "expected real parallelism, only saw ${maxObserved.get()}")
    }

    @Test
    fun `retries a transient chunk failure and still assembles correctly`() = runBlocking {
        val body = ByteArray(200) { it.toByte() }
        val firstChunkAttempts = AtomicInteger(0)
        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = byteArrayOf(),
                    status = HttpStatusCode.OK,
                    headers = headHeaders(body.size),
                )
                HttpMethod.Get -> {
                    val (start, end) = parseRange(request.headers[HttpHeaders.Range]!!)
                    if (start == 0 && firstChunkAttempts.getAndIncrement() == 0) {
                        respond(byteArrayOf(), HttpStatusCode.InternalServerError)
                    } else {
                        respond(body.copyOfRange(start, end + 1), HttpStatusCode.PartialContent)
                    }
                }
                else -> error("Unexpected method ${request.method}")
            }
        })

        FileDownloader(client, testConfig).download("http://test/file", destination())

        assertEquals(2, firstChunkAttempts.get(), "first chunk should have been retried exactly once")
        assertContentEquals(body, Files.readAllBytes(Path.of(destination())))
    }

    @Test
    fun `gives up after exhausting retries`() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = byteArrayOf(),
                    status = HttpStatusCode.OK,
                    headers = headHeaders(100),
                )
                HttpMethod.Get -> {
                    attempts.incrementAndGet()
                    respond(byteArrayOf(), HttpStatusCode.InternalServerError)
                }
                else -> error("Unexpected method ${request.method}")
            }
        })

        assertFailsWith<IllegalStateException> {
            FileDownloader(client, testConfig).download("http://test/file", destination())
        }
        assertEquals(testConfig.retries + 1, attempts.get(), "should attempt initial + retries")
    }

    @Test
    fun `rejects 200 OK on a ranged GET (server ignored Range header)`() = runBlocking {
        val body = ByteArray(100) { it.toByte() }
        val attempts = AtomicInteger(0)
        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = byteArrayOf(),
                    status = HttpStatusCode.OK,
                    headers = headHeaders(body.size),
                )
                HttpMethod.Get -> {
                    attempts.incrementAndGet()
                    respond(body, HttpStatusCode.OK)
                }
                else -> error("Unexpected method ${request.method}")
            }
        })

        assertFailsWith<NonRetryableHttpException> {
            FileDownloader(client, testConfig).download("http://test/file", destination())
        }
        assertEquals(1, attempts.get(), "200 OK on a ranged GET must not be retried")
    }

    @Test
    fun `does not retry on 4xx response`() = runBlocking {
        val attempts = AtomicInteger(0)
        val client = HttpClient(MockEngine { request ->
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = byteArrayOf(),
                    status = HttpStatusCode.OK,
                    headers = headHeaders(100),
                )
                HttpMethod.Get -> {
                    attempts.incrementAndGet()
                    respond(byteArrayOf(), HttpStatusCode.NotFound)
                }
                else -> error("Unexpected method ${request.method}")
            }
        })

        assertFailsWith<NonRetryableHttpException> {
            FileDownloader(client, testConfig).download("http://test/file", destination())
        }
        assertEquals(1, attempts.get(), "404 must not be retried")
    }
}
