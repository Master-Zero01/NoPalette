package org.anarchadia.noPalette;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class PaletteCompactionTest {

    private void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            } else {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    private void writeLong(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 56));
        out.write((int) (value >>> 48));
        out.write((int) (value >>> 40));
        out.write((int) (value >>> 32));
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }

    private int readVarInt(ByteBuffer buffer) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte current = buffer.get();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) return value;
            shift += 7;
        }
    }

    private byte[] createTestChunkSection(
            short nonEmptyBlocks,
            byte blockBits,
            int[] blockPalette,
            long[] blockData,
            byte biomeBits,
            int[] biomePalette,
            long[] biomeData
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((nonEmptyBlocks >> 8) & 0xFF);
        out.write(nonEmptyBlocks & 0xFF);

        // Block container
        out.write(blockBits);
        if (blockBits == 0) {
            writeVarInt(out, blockPalette[0]);
        } else if (blockBits <= 8) {
            writeVarInt(out, blockPalette.length);
            for (int entry : blockPalette) {
                writeVarInt(out, entry);
            }
        }
        for (long l : blockData) {
            writeLong(out, l);
        }

        // Biome container
        out.write(biomeBits);
        if (biomeBits == 0) {
            writeVarInt(out, biomePalette[0]);
        } else if (biomeBits <= 3) {
            writeVarInt(out, biomePalette.length);
            for (int entry : biomePalette) {
                writeVarInt(out, entry);
            }
        }
        for (long l : biomeData) {
            writeLong(out, l);
        }

        return out.toByteArray();
    }

    @Test
    void testGhostAirPruned() throws IOException {
        // Block palette has [0 (AIR), 10 (STONE), 20 (DIRT)]
        // All 4096 blocks are populated with index 1 (STONE), none with 0 (AIR) or 2 (DIRT)
        // 4 bits per entry = 16 blocks per long, 256 longs
        // Pack all blocks with index 1 (0x1111111111111111L)
        int blockBits = 4;
        int[] blockPalette = new int[]{0, 10, 20};
        long[] blockData = new long[256];
        long allStone = 0x1111111111111111L;
        for (int i = 0; i < 256; i++) {
            blockData[i] = allStone;
        }

        // Biome: single value (Plains = 1)
        byte[] original = createTestChunkSection(
                (short) 4096,
                (byte) blockBits,
                blockPalette,
                blockData,
                (byte) 0,
                new int[]{1},
                new long[0]
        );

        byte[] patched = PaletteExploitPatcher.processChunkData(original);
        assertNotSame(original, patched);

        // Parse patched
        ByteBuffer buf = ByteBuffer.wrap(patched);
        assertEquals(4096, buf.getShort());
        byte bitsPerEntry = buf.get();
        // Since only 1 unique block exists (STONE), it should compact to single-value container (bits = 0)
        assertEquals(0, bitsPerEntry);
        int singleState = readVarInt(buf);
        assertEquals(10, singleState);
    }

    @Test
    void testOrderNormalizationAndGhostPruning() throws IOException {
        // Block palette has [0 (AIR - unused), 10 (STONE), 20 (DIRT)]
        // First block is DIRT (palette index 2), then second block is STONE (palette index 1)
        // Rest are STONE (index 1)
        int blockBits = 4;
        int[] blockPalette = new int[]{0, 10, 20};
        long[] blockData = new long[256];
        for (int i = 0; i < 256; i++) {
            blockData[i] = 0x1111111111111111L; // all index 1 (STONE)
        }
        // Set first block (bits 0-3) to index 2 (DIRT): 0x1111111111111112L
        blockData[0] = 0x1111111111111112L;

        byte[] original = createTestChunkSection(
                (short) 4096,
                (byte) blockBits,
                blockPalette,
                blockData,
                (byte) 0,
                new int[]{1},
                new long[0]
        );

        byte[] patched = PaletteExploitPatcher.processChunkData(original);
        assertNotSame(original, patched);

        ByteBuffer buf = ByteBuffer.wrap(patched);
        buf.getShort(); // nonEmpty
        byte bitsPerEntry = buf.get();
        assertEquals(4, bitsPerEntry);
        int paletteSize = readVarInt(buf);
        // Air should be pruned, new palette size should be 2: [20 (DIRT), 10 (STONE)]
        assertEquals(2, paletteSize);
        assertEquals(20, readVarInt(buf)); // First block was DIRT -> index 0 in new palette
        assertEquals(10, readVarInt(buf)); // Second block was STONE -> index 1 in new palette

        // And verify repacked data: first block is now index 0, others are index 1
        long firstLong = buf.getLong();
        // First 4 bits: 0, next 60 bits: 1 -> 0x1111111111111110L
        assertEquals(0x1111111111111110L, firstLong);
    }

    @Test
    void testAlreadyCompactedSectionReturnsSameReference() throws IOException {
        // A section that is already in appearance order with no unused entries
        int blockBits = 4;
        int[] blockPalette = new int[]{10, 20};
        long[] blockData = new long[256];
        // First block is index 0 (10), rest are index 1 (20)
        for (int i = 0; i < 256; i++) {
            blockData[i] = 0x1111111111111111L;
        }
        blockData[0] = 0x1111111111111110L;

        byte[] original = createTestChunkSection(
                (short) 4096,
                (byte) blockBits,
                blockPalette,
                blockData,
                (byte) 0,
                new int[]{1},
                new long[0]
        );

        byte[] patched = PaletteExploitPatcher.processChunkData(original);
        // Should NOT be modified!
        assertSame(original, patched);
    }

    @Test
    void testDirectBiomePaletteNotCorrupted() throws IOException {
        // Biome container with direct palette (bits = 6, size = 64 biomes)
        // 64 / 6 = 10 values per long -> 7 longs
        byte[] original = createTestChunkSection(
                (short) 0,
                (byte) 0,
                new int[]{0},
                new long[0],
                (byte) 6, // direct biome palette
                new int[0],
                new long[]{1L, 2L, 3L, 4L, 5L, 6L, 7L}
        );

        byte[] patched = PaletteExploitPatcher.processChunkData(original);
        // Already valid direct palette, should be untouched
        assertSame(original, patched);
    }

    @Test
    void testXaeroPlusNewChunkDetectionPrevented() throws IOException {
        // Build an uncompacted newly generated section:
        // Palette: [0 (AIR - ghost), 10 (STONE), 20 (GRANITE), 30 (DIORITE)]
        // Order in bitstorage: DIORITE (3) at index 0, GRANITE (2) at index 1, STONE (1) everywhere else.
        int blockBits = 4;
        int[] blockPalette = new int[]{0, 10, 20, 30};
        long[] blockData = new long[256];
        for (int i = 0; i < 256; i++) {
            blockData[i] = 0x1111111111111111L; // all index 1 (STONE)
        }
        // index 0 -> DIORITE (palette idx 3), index 1 -> GRANITE (palette idx 2)
        // 0x1111111111111123L
        blockData[0] = 0x1111111111111123L;

        byte[] unpatched = createTestChunkSection(
                (short) 4096,
                (byte) blockBits,
                blockPalette,
                blockData,
                (byte) 0,
                new int[]{1},
                new long[0]
        );

        // 1. Verify that XaeroPlus's logic detects this unpatched chunk as a new chunk:
        // XaeroPlus check 1: checkForExtraPaletteEntries (palette size 4 > present size 3)
        // Unpatched has 4 palette entries, but only 3 are present -> isNewChunk = true!
        assertTrue(blockPalette.length > 3);

        // 2. Patch the chunk:
        byte[] patched = PaletteExploitPatcher.processChunkData(unpatched);
        assertNotSame(unpatched, patched);

        // 3. Run XaeroPlus exact checks on the patched chunk:
        ByteBuffer buf = ByteBuffer.wrap(patched);
        buf.getShort(); // nonEmpty
        byte bits = buf.get();
        assertEquals(4, bits);
        int patchedPaletteSize = readVarInt(buf);
        int[] patchedPalette = new int[patchedPaletteSize];
        for (int i = 0; i < patchedPaletteSize; i++) {
            patchedPalette[i] = readVarInt(buf);
        }

        long[] patchedData = new long[256];
        for (int i = 0; i < 256; i++) {
            patchedData[i] = buf.getLong();
        }

        // Check A: checkForExtraPaletteEntries
        java.util.Set<Integer> presentIndices = new java.util.HashSet<>();
        long mask = (1L << bits) - 1;
        int valuesPerLong = 64 / bits;
        for (int i = 0; i < 4096; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bits;
            int val = (int) ((patchedData[longIndex] >>> bitOffset) & mask);
            presentIndices.add(val);
        }
        // Patched palette must have EXACTLY the number of unique present blocks (no extra ghost entries)
        assertEquals(presentIndices.size(), patchedPaletteSize);

        // Check B: scanLinearPaletteOrder (order of appearance in 0..4095 iteration)
        java.util.List<Integer> expectedOrder = new java.util.ArrayList<>();
        for (int entry : patchedPalette) {
            expectedOrder.add(entry);
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int searchIndex = 0;
        boolean outOfOrder = false;

        for (int i = 0; i < 4096; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bits;
            int paletteIdx = (int) ((patchedData[longIndex] >>> bitOffset) & mask);
            int blockStateId = patchedPalette[paletteIdx];

            if (seen.contains(blockStateId)) continue;

            int nextExpectedId = expectedOrder.get(searchIndex);
            if (blockStateId == nextExpectedId) {
                seen.add(blockStateId);
                searchIndex++;
            } else {
                outOfOrder = true;
                break;
            }
        }

        // XaeroPlus's linear palette order check MUST NOT trigger!
        assertFalse(outOfOrder, "Palette entries should be in exact physical appearance order");
        // And verify the order matches first appearance: DIORITE (30), GRANITE (20), STONE (10)
        assertArrayEquals(new int[]{30, 20, 10}, patchedPalette);
    }

    private byte[] createRealistic24SectionChunk(boolean compacted) throws IOException {
        ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();

        for (int sectionIndex = 0; sectionIndex < 24; sectionIndex++) {
            byte[] sectionBytes;
            if (sectionIndex >= 16) {
                // Sky sections (y = 192 to 320): all air, 0 bits per entry
                sectionBytes = createTestChunkSection(
                        (short) 0,
                        (byte) 0,
                        new int[]{0},
                        new long[0],
                        (byte) 0,
                        new int[]{1},
                        new long[0]
                );
            } else if (sectionIndex < 4) {
                // Deep underground (-64 to 0): bedrock, deepslate, tuff, lava
                int blockBits = 4;
                long[] blockData = new long[256];
                if (compacted) {
                    int[] blockPalette = new int[]{100, 101, 102, 103};
                    // Fill in appearance order: Item 0 = 0 (bits 0..3), Item 1 = 1 (bits 4..7), etc.
                    for (int i = 0; i < 256; i++) {
                        blockData[i] = 0x3210321032103210L;
                    }
                    sectionBytes = createTestChunkSection(
                            (short) 4096,
                            (byte) blockBits,
                            blockPalette,
                            blockData,
                            (byte) 0,
                            new int[]{50},
                            new long[0]
                    );
                } else {
                    // Uncompacted: extra ghost air (0) and out-of-order palette
                    int[] blockPalette = new int[]{0, 103, 102, 101, 100};
                    for (int i = 0; i < 256; i++) {
                        blockData[i] = 0x1234123412341234L;
                    }
                    sectionBytes = createTestChunkSection(
                            (short) 4096,
                            (byte) blockBits,
                            blockPalette,
                            blockData,
                            (byte) 0,
                            new int[]{50},
                            new long[0]
                    );
                }
            } else {
                // Caves and surface (0 to 192): stone, dirt, grass, coal, iron, copper
                int blockBits = 4;
                long[] blockData = new long[256];
                if (compacted) {
                    int[] blockPalette = new int[]{10, 11, 12, 13, 14, 15};
                    // In appearance order: 0, 1, 2, 3, 4, 5
                    for (int i = 0; i < 256; i++) {
                        blockData[i] = 0x3210543210543210L;
                    }
                    sectionBytes = createTestChunkSection(
                            (short) 4096,
                            (byte) blockBits,
                            blockPalette,
                            blockData,
                            (byte) 0,
                            new int[]{1},
                            new long[0]
                    );
                } else {
                    // Uncompacted: ghost air, out-of-order appearance
                    int[] blockPalette = new int[]{0, 15, 14, 13, 12, 11, 10};
                    for (int i = 0; i < 256; i++) {
                        blockData[i] = 0x1234561234561234L;
                    }
                    sectionBytes = createTestChunkSection(
                            (short) 4096,
                            (byte) blockBits,
                            blockPalette,
                            blockData,
                            (byte) 0,
                            new int[]{1},
                            new long[0]
                    );
                }
            }
            chunkBuffer.write(sectionBytes);
        }

        return chunkBuffer.toByteArray();
    }

    @Test
    void testHighSpeedPerformanceSavedChunks() throws Exception {
        byte[] chunkData = createRealistic24SectionChunk(true);

        // Warm up JIT compiler
        for (int i = 0; i < 5000; i++) {
            PaletteExploitPatcher.processChunkData(chunkData);
        }

        int iterations = 50_000;
        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            byte[] result = PaletteExploitPatcher.processChunkData(chunkData);
            if (result != chunkData) {
                fail("Compacted chunk should not be modified");
            }
        }
        long totalNanos = System.nanoTime() - startNanos;

        double totalMs = totalNanos / 1_000_000.0;
        double nanosPerChunk = (double) totalNanos / iterations;
        double microsPerChunk = nanosPerChunk / 1000.0;
        double nanosPerSection = nanosPerChunk / 24.0;
        double chunksPerSec = (iterations / (totalNanos / 1_000_000_000.0));

        // Player traveling at 85 blocks/sec: generates ~111 chunk packets/sec (~2,664 sections/sec)
        double cpuPercentAt85bps = (111.0 * microsPerChunk / 10_000.0);

        System.out.println("=========================================================");
        System.out.println(" HIGH-SPEED BENCHMARK: SAVED CHUNKS (85+ BLOCKS/SEC)     ");
        System.out.println("=========================================================");
        System.out.printf("Total 24-Section Chunks Processed: %,d%n", iterations);
        System.out.printf("Total Sections Processed:          %,d%n", iterations * 24L);
        System.out.printf("Total Execution Time:              %.2f ms%n", totalMs);
        System.out.printf("Latency per 24-Section Chunk:      %.3f µs%n", microsPerChunk);
        System.out.printf("Latency per Section:               %.1f ns%n", nanosPerSection);
        System.out.printf("Throughput:                        %,.0f chunks/sec%n", chunksPerSec);
        System.out.printf("CPU Impact @ 85 bps (111 pkts/s):  %.4f%% of 1 core%n", cpuPercentAt85bps);
        System.out.println("=========================================================");

        assertTrue(microsPerChunk < 25.0, "Average latency per 24-section chunk must be under 25 microseconds");
        assertTrue(chunksPerSec > 40_000, "Throughput must exceed 40,000 chunks/second");
    }

    @Test
    void testHighSpeedPerformanceUncompactedChunks() throws Exception {
        byte[] uncompactedChunk = createRealistic24SectionChunk(false);

        // Warm up JIT compiler
        for (int i = 0; i < 1000; i++) {
            PaletteExploitPatcher.processChunkData(uncompactedChunk);
        }

        int iterations = 5000;
        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            byte[] result = PaletteExploitPatcher.processChunkData(uncompactedChunk);
            if (result == uncompactedChunk) {
                fail("Uncompacted chunk must be rewritten");
            }
        }
        long totalNanos = System.nanoTime() - startNanos;

        double totalMs = totalNanos / 1_000_000.0;
        double nanosPerChunk = (double) totalNanos / iterations;
        double microsPerChunk = nanosPerChunk / 1000.0;
        double chunksPerSec = (iterations / (totalNanos / 1_000_000_000.0));
        double cpuPercentAt85bps = (111.0 * microsPerChunk / 10_000.0);

        System.out.println("=========================================================");
        System.out.println(" HIGH-SPEED BENCHMARK: UNCOMPACTED CHUNKS COMPACTION     ");
        System.out.println("=========================================================");
        System.out.printf("Total 24-Section Chunks Compacted: %,d%n", iterations);
        System.out.printf("Total Execution Time:              %.2f ms%n", totalMs);
        System.out.printf("Compaction Latency per Chunk:      %.3f µs%n", microsPerChunk);
        System.out.printf("Throughput:                        %,.0f chunks/sec%n", chunksPerSec);
        System.out.printf("CPU Impact @ 85 bps (111 pkts/s):  %.4f%% of 1 core%n", cpuPercentAt85bps);
        System.out.println("=========================================================");

        assertTrue(chunksPerSec > 2_000, "Compaction throughput must exceed 2,000 chunks/second");
    }

    @Test
    void testZeroHeapAllocationOnCompactedChunks() throws Exception {
        byte[] chunkData = createRealistic24SectionChunk(true);

        java.lang.management.ThreadMXBean threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(threadBean instanceof com.sun.management.ThreadMXBean sunBean) || !sunBean.isThreadAllocatedMemorySupported()) {
            System.out.println("Thread allocated memory measurement not supported on this JVM; skipping allocation assertion.");
            return;
        }

        // Warm up JIT
        for (int i = 0; i < 2000; i++) {
            PaletteExploitPatcher.processChunkData(chunkData);
        }

        long beforeAlloc = sunBean.getThreadAllocatedBytes(Thread.currentThread().getId());
        int runs = 1000;
        for (int i = 0; i < runs; i++) {
            PaletteExploitPatcher.processChunkData(chunkData);
        }
        long afterAlloc = sunBean.getThreadAllocatedBytes(Thread.currentThread().getId());
        long allocatedBytes = afterAlloc - beforeAlloc;

        System.out.println("=========================================================");
        System.out.printf("Zero-Allocation Test: %d chunks allocated %d bytes total%n", runs, allocatedBytes);
        System.out.println("=========================================================");

        assertEquals(0, allocatedBytes, "Compacted chunk scanning must allocate exactly 0 heap bytes!");
    }
}
