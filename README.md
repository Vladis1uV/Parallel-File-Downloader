# Parallel File Downloader

A small Kotlin/JVM tool that downloads a file from an HTTP URL by fetching
multiple byte ranges in parallel and assembling them into a single file on disk.
Built around `kotlinx.coroutines` and `Ktor` client, with a hand-rolled retry
and per-chunk timeout.

## How it works

1. Send `HEAD` to the URL.
   - Read `Content-Length` to learn the file size.
   - Verify `Accept-Ranges: bytes` is present, otherwise abort.
2. Split the file size into byte ranges of `chunkSize` bytes each.
3. Open the destination file with `RandomAccessFile`, pre-size it with
   `setLength`, and obtain a `FileChannel` for offset-based writes.
4. Launch one coroutine per chunk. A `Semaphore` caps how many run at once.
   Each coroutine sends `GET` with `Range: bytes=<start>-<end>` and writes the
   response bytes at the chunk's offset in the file.
5. `coroutineScope` waits for every chunk; if any chunk fails after retries,
   the rest are cancelled and the error propagates.

Per-chunk reliability:
- `withTimeout(timeoutPerChunk)` cancels a stuck request.
- A chunk response must be `206 Partial Content`. `5xx` is treated as a
  transient server error and retried. Anything else (including a `200 OK`
  that means the server ignored the `Range` header, or any `4xx`) is a
  `NonRetryableHttpException` — failed once, no retries, fail fast.
- `withRetry { ... }` retries timeouts and IO/network errors with
  exponential backoff (100 ms × 4ⁿ). `CancellationException` from outside
  is always re-thrown, never retried.

## Project layout

```
src/main/kotlin/
    ChunkPlanner.kt    pure function: file size → list of byte ranges
    FileDownloader.kt  HEAD + parallel ranged GETs + offset writes + retry
    Main.kt            CLI entry point and HttpClient construction

src/test/kotlin/
    ChunkPlannerTest.kt    11 tests covering off-by-ones, edge sizes, validation
    FileDownloaderTest.kt  8 tests using Ktor MockEngine: assembly,
                           Range headers, Accept-Ranges check, parallelism cap,
                           retry success, retry exhaustion, reject 200-on-range,
                           no-retry on 4xx
```

## Build

Requires JDK 21+ and the bundled Gradle wrapper.

```
./gradlew build
```

## Configuration

There are no CLI flags by design. Defaults live in `DownloadConfig`
(`FileDownloader.kt`):

| Field             | Default              | Meaning                       |
|-------------------|----------------------|-------------------------------|
| `chunkSize`       | 4 MB                 | Bytes requested per chunk     |
| `maxParallelism`  | 8                    | Concurrent in-flight chunks   |
| `retries`         | 3                    | Retries per chunk on failure  |
| `timeoutPerChunk` | 30 s                 | Deadline for a single chunk   |

Edit the defaults and rebuild if you want different values.

## Manual testing with the Docker httpd server

This is the workflow described in the task. Serves a local directory over HTTP
with full ranged-request support.

1. Make a directory and put a sample file in it. Use a non-trivial size so
   parallelism is observable:

   ```
   mkdir -p ~/downloader-files
   dd if=/dev/urandom of=~/downloader-files/sample.bin bs=1M count=100
   ```

   **Want to test with your own file (a PDF, ZIP, ISO, etc.) instead?**
   Copy it into the served directory:

   ```
   cp ~/Documents/report.pdf ~/downloader-files/
   ```

   Then in every command below, replace `sample.bin` with your filename
   (`report.pdf`) and you can rename `~/downloaded.bin` to match
   (`~/downloaded.pdf`). Files smaller than 4 MB download as one chunk —
   pick something ≥ 50 MB to actually exercise the parallel path.

2. Run the Apache httpd container:

   ```
   docker run --rm -p 8080:80 -v ~/downloader-files:/usr/local/apache2/htdocs/ httpd:latest
   ```

   Leave this terminal running.

3. In a second terminal, sanity-check the server's behaviour:

   ```
   curl -I http://localhost:8080/sample.bin
   ```

   The response should include both:
   ```
   Accept-Ranges: bytes
   Content-Length: 104857600
   ```

4. Run the downloader:

   ```
   ./gradlew run --args="http://localhost:8080/sample.bin ~/downloaded.bin"
   ```

   Expected output (your home path will differ):

   ```
   Downloading http://localhost:8080/sample.bin -> /home/<you>/downloaded.bin
   Done: 104857600 bytes in 1.42s (70.31 MB/s)
   ```

5. Verify the result is byte-identical to the source:

   ```
   sha256sum ~/downloader-files/sample.bin ~/downloaded.bin
   ```

   The two hashes must match.

6. Stop the server and delete the test files:

   - Press `Ctrl+C` in the terminal running `docker run`.
   - Then run:

     ```
     rm -rf ~/downloader-files ~/downloaded.bin ~/dummy.bin
     ```

## Unit tests

```
./gradlew test
```

`FileDownloaderTest` does not touch the network: every test plugs a
`MockEngine` into the `HttpClient` and asserts on the requests the downloader
sends and the file it produces.

Notable cases:
- `download assembles the file byte-for-byte` — end-to-end correctness against
  a known byte pattern.
- `each chunk requests the planned Range header` — verifies HTTP `Range` values
  exactly match the planner's output.
- `concurrent chunks never exceed maxParallelism` — observes a high-water mark
  of in-flight requests; asserts both an upper bound (semaphore works) and a
  lower bound (parallelism is real, not serial).
- `retries a transient chunk failure and still assembles correctly` — first
  attempt at chunk 0 returns 500, second succeeds; final file is correct.
- `gives up after exhausting retries` — every GET returns 500; downloader
  fails after exactly `retries + 1` attempts.
