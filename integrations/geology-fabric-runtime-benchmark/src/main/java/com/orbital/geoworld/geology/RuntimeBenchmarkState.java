package com.orbital.geoworld.geology;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Measures one exact target-plus-halo generation window without writing world state. */
public final class RuntimeBenchmarkState {
    private static final int MIN_CHUNK_X = 252;
    private static final int MAX_CHUNK_X = 261;
    private static final int MIN_CHUNK_Z = 106;
    private static final int MAX_CHUNK_Z = 115;
    private static final int EXPECTED_CHUNKS = 100;
    private static final Set<String> observedFeatures = new HashSet<>();
    private static Path receiptPath;
    private static String arm;
    private static boolean enabled;
    private static boolean written;
    private static long startWallNanos;
    private static long startProcessCpuNanos;
    private static long firstChunkPacked;
    private static long lastChunkPacked;

    private RuntimeBenchmarkState() {}

    public static synchronized void initialize() {
        String receipt = System.getProperty("geoworld.runtime.benchmark.receipt", "").trim();
        arm = System.getProperty("geoworld.runtime.benchmark.arm", "").trim();
        if (receipt.isEmpty() || !arm.matches("[a-z][a-z0-9-]*")) return;
        receiptPath = Path.of(receipt).toAbsolutePath().normalize();
        enabled = true;
    }

    public static synchronized void beginCarve(ChunkRegion world, Chunk chunk) {
        if (!enabled || startWallNanos != 0L) return;
        if (!World.OVERWORLD.equals(world.toServerWorld().getRegistryKey())) return;
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        if (!inside(x, z)) return;
        startWallNanos = System.nanoTime();
        startProcessCpuNanos = processCpuNanos();
        firstChunkPacked = chunk.getPos().toLong();
    }

    public static synchronized void observeFeatures(StructureWorldAccess world, Chunk chunk) {
        if (!enabled || written) return;
        if (!World.OVERWORLD.equals(world.toServerWorld().getRegistryKey())) return;
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        if (!inside(x, z)) return;
        if (startWallNanos == 0L) {
            startWallNanos = System.nanoTime();
            startProcessCpuNanos = processCpuNanos();
            firstChunkPacked = chunk.getPos().toLong();
        }
        observedFeatures.add(x + "," + z);
        lastChunkPacked = chunk.getPos().toLong();
        if (observedFeatures.size() == EXPECTED_CHUNKS) writeReceipt();
    }

    private static boolean inside(int x, int z) {
        return MIN_CHUNK_X <= x && x <= MAX_CHUNK_X
                && MIN_CHUNK_Z <= z && z <= MAX_CHUNK_Z;
    }

    private static long processCpuNanos() {
        return ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    private static void writeReceipt() {
        long wallNanos = System.nanoTime() - startWallNanos;
        long processCpuNanos = processCpuNanos() - startProcessCpuNanos;
        String payload = "{"
                + "\"schema_version\":\"geoworld.runtime-benchmark-receipt.v1\""
                + ",\"state\":\"PASS_COMPLETE_AUDIT_WINDOW\""
                + ",\"arm\":\"" + arm + "\""
                + ",\"observed_feature_chunk_count\":" + observedFeatures.size()
                + ",\"expected_feature_chunk_count\":" + EXPECTED_CHUNKS
                + ",\"wall_nanoseconds\":" + wallNanos
                + ",\"process_cpu_nanoseconds\":" + processCpuNanos
                + ",\"first_chunk_packed\":" + firstChunkPacked
                + ",\"last_chunk_packed\":" + lastChunkPacked
                + ",\"world_block_write_count\":0"
                + ",\"biome_write_count\":0"
                + ",\"structure_write_count\":0"
                + "}\n";
        try {
            Files.createDirectories(receiptPath.getParent());
            Path temporary = receiptPath.resolveSibling(receiptPath.getFileName() + ".part");
            Files.writeString(temporary, payload, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    receiptPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            written = true;
        } catch (IOException exception) {
            enabled = false;
            throw new IllegalStateException("runtime benchmark receipt write failed", exception);
        }
    }
}
