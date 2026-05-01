package org.example

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

suspend fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("Usage: downloader <url> <destination>")
        exitProcess(2)
    }
    val url = args[0]
    val destination = args[1].expandTilde()

    val config = DownloadConfig()
    buildHttpClient(config).use { client ->
        val downloader = FileDownloader(client, config)
        println("Downloading $url -> $destination")
        try {
            val elapsedMs = measureTimeMillis {
                downloader.download(url, destination)
            }
            val bytes = Files.size(Path.of(destination))
            val seconds = elapsedMs / 1000.0
            val mbPerSec = if (seconds > 0) bytes / seconds / (1024 * 1024) else 0.0
            println("Done: $bytes bytes in ${"%.2f".format(seconds)}s (${"%.2f".format(mbPerSec)} MB/s)")
        } catch (e: Exception) {
            System.err.println("error: ${e.message}")
            exitProcess(1)
        }
    }
}

private fun String.expandTilde(): String = when {
    this == "~" -> System.getProperty("user.home")
    startsWith("~/") -> System.getProperty("user.home") + substring(1)
    else -> this
}

private fun buildHttpClient(config: DownloadConfig): HttpClient = HttpClient(CIO) {
    expectSuccess = false

    engine {
        requestTimeout = 0
        maxConnectionsCount = config.maxParallelism * 2
    }

    defaultRequest {
        header(HttpHeaders.UserAgent, "parallel-file-downloader/1.0")
        header(HttpHeaders.Accept, "*/*")
    }
}
