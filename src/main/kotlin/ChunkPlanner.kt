package org.example

data class Chunk(
    val index: Int,
    val start: Long,
    val endInclusive: Long,
) {
    val size: Long get() = endInclusive - start + 1
}

fun planChunks(totalSize: Long, chunkSize: Long): List<Chunk> {
    require(totalSize >= 0) { "totalSize must be non-negative, was $totalSize" }
    require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
    if (totalSize == 0L) return emptyList()

    val chunks = mutableListOf<Chunk>()
    var start = 0L
    var index = 0
    while (start < totalSize) {
        val endInclusive = minOf(start + chunkSize - 1, totalSize - 1)
        chunks.add(Chunk(index, start, endInclusive))
        start = endInclusive + 1
        index++
    }
    return chunks
}
