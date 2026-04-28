package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkPlannerTest {

    @Test
    fun `empty file returns no chunks`() {
        assertEquals(emptyList(), planChunks(totalSize = 0, chunkSize = 100))
    }

    @Test
    fun `single byte file produces one one-byte chunk`() {
        val chunks = planChunks(totalSize = 1, chunkSize = 100)
        assertEquals(listOf(Chunk(0, 0, 0)), chunks)
        assertEquals(1L, chunks.single().size)
    }

    @Test
    fun `file smaller than chunk size produces one short chunk`() {
        val chunks = planChunks(totalSize = 50, chunkSize = 100)
        assertEquals(listOf(Chunk(0, 0, 49)), chunks)
        assertEquals(50L, chunks.single().size)
    }

    @Test
    fun `file equal to chunk size produces one full chunk`() {
        val chunks = planChunks(totalSize = 100, chunkSize = 100)
        assertEquals(listOf(Chunk(0, 0, 99)), chunks)
    }

    @Test
    fun `file is exact multiple of chunk size`() {
        val chunks = planChunks(totalSize = 300, chunkSize = 100)
        assertEquals(
            listOf(
                Chunk(0, 0, 99),
                Chunk(1, 100, 199),
                Chunk(2, 200, 299),
            ),
            chunks,
        )
    }

    @Test
    fun `last chunk is shorter when size is not a multiple`() {
        val chunks = planChunks(totalSize = 250, chunkSize = 100)
        assertEquals(
            listOf(
                Chunk(0, 0, 99),
                Chunk(1, 100, 199),
                Chunk(2, 200, 249),
            ),
            chunks,
        )
        assertEquals(50L, chunks.last().size)
    }

    @Test
    fun `chunks are contiguous and cover the entire file`() {
        // Prime number so the last chunk is awkwardly sized — good stress for off-by-ones.
        val totalSize = 1_000_003L
        val chunkSize = 4_096L
        val chunks = planChunks(totalSize, chunkSize)

        assertEquals(0L, chunks.first().start)
        assertEquals(totalSize - 1, chunks.last().endInclusive)
        for (i in 1 until chunks.size) {
            assertEquals(
                chunks[i - 1].endInclusive + 1, chunks[i].start,
                "gap between chunk ${i - 1} and $i",
            )
        }
        assertEquals(totalSize, chunks.sumOf { it.size })
    }

    @Test
    fun `chunk indices are monotonic from zero`() {
        val chunks = planChunks(totalSize = 1_000_000, chunkSize = 4_096)
        chunks.forEachIndexed { i, chunk ->
            assertEquals(i, chunk.index)
        }
    }

    @Test
    fun `negative totalSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            planChunks(totalSize = -1, chunkSize = 100)
        }
    }

    @Test
    fun `zero chunkSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            planChunks(totalSize = 100, chunkSize = 0)
        }
    }

    @Test
    fun `negative chunkSize throws`() {
        assertFailsWith<IllegalArgumentException> {
            planChunks(totalSize = 100, chunkSize = -1)
        }
    }
}
