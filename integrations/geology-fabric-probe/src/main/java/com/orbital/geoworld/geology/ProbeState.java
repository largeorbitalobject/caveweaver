package com.orbital.geoworld.geology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Thread-safe observation state and deterministic receipt for Karstogenesis run 200. */
public final class ProbeState {
    private static final int MINIMUM_CALLBACK_CHUNKS = 4;
    private static final int TARGET_CHUNK_COUNT = 12;
    private static final int MINIMUM_TARGET_PLAN_SIZE = 64;
    private static final int MAXIMUM_ARBITRATION_SAMPLES = 4096;
    private static final int STATE_SAMPLE_QUOTA = 1024;
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;

    private static final Set<String> populateNoiseReturnChunks = new HashSet<>();
    private static final Set<String> carveHeadChunks = new HashSet<>();
    private static final Set<String> carveTailChunks = new HashSet<>();
    private static final Set<String> featuresHeadChunks = new HashSet<>();
    private static final Set<String> featuresTailChunks = new HashSet<>();
    private static final Map<String, Integer> phaseProgress = new HashMap<>();
    private static final Set<String> completeOrderChunks = new HashSet<>();
    private static final Set<String> evaluatedTargetChunks = new HashSet<>();
    private static final TreeMap<String, String> packagePlanHashes = new TreeMap<>();
    private static final EnumMap<KarstHostArbitration.HostState, TreeMap<String, Sample>>
            samplesByState = new EnumMap<>(KarstHostArbitration.HostState.class);
    private static final List<String> errors = new ArrayList<>();

    private static Path receiptPath;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static List<KarstDeltaChunkReader.ChunkKey> targetChunks = List.of();
    private static boolean initialized;
    private static boolean serverStarted;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketsReleased;
    private static int blockRegistryEntryCount;
    private static int biomeRegistryEntryCount;
    private static int structureRegistryEntryCount;
    private static int baseStoneTagEntryCount;
    private static int structureAccessorObservationCount;
    private static int serverTickCount;

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureReserved,
            String biomeIdentity) {}

    private ProbeState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        for (KarstHostArbitration.HostState state : KarstHostArbitration.HostState.values()) {
            samplesByState.put(state, new TreeMap<>());
        }
        String packageProperty = System.getProperty("geoworld.probe.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.probe.receipt", "").trim();
        if (packageProperty.isEmpty() || receiptProperty.isEmpty()) {
            throw new IllegalStateException("probe package or receipt property is empty");
        }
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        try {
            reader = KarstDeltaChunkReader.open(Path.of(packageProperty), true);
        } catch (IOException exception) {
            throw new IllegalStateException("package open failed", exception);
        }
        planner = KarstDeltaChunkReader.MinecraftIntentPlanner.bind(
                reader,
                reader.cacheIdentitySha256(),
                new KarstDeltaChunkReader.Translation(604, 91, 120),
                new KarstDeltaChunkReader.WorldBounds(
                        new KarstDeltaChunkReader.Voxel(-30_000_000, LOW_Y, -30_000_000),
                        new KarstDeltaChunkReader.Voxel(29_999_999, HIGH_Y, 29_999_999)));
        targetChunks = selectTargetChunks();
        initialized = true;
        KarstogenesisFabricProbe.LOGGER.info(
                "Run 288 probe initialized with {} deterministic target chunks: {}",
                targetChunks.size(),
                targetChunks);
    }

    private static List<KarstDeltaChunkReader.ChunkKey> selectTargetChunks() {
        List<KarstDeltaChunkReader.ChunkKey> eligible = new ArrayList<>();
        KarstDeltaChunkReader.HostMinecraftRegistry neutralRegistry =
                new KarstDeltaChunkReader.HostMinecraftRegistry() {
                    @Override
                    public KarstDeltaChunkReader.Replaceability replaceabilityFor(
                            KarstDeltaChunkReader.Voxel voxel,
                            KarstDeltaChunkReader.VoxelIntent intent) {
                        return KarstDeltaChunkReader.Replaceability.REPLACE;
                    }

                    @Override
                    public String materialFor(
                            KarstDeltaChunkReader.Voxel voxel,
                            KarstDeltaChunkReader.VoxelIntent intent) {
                        return "host:observed-block";
                    }

                    @Override
                    public String biomeFor(
                            KarstDeltaChunkReader.Voxel voxel,
                            KarstDeltaChunkReader.VoxelIntent intent) {
                        return "host:preserve-existing";
                    }
                };
        for (KarstDeltaChunkReader.ChunkKey key : planner.candidateWorldChunks()) {
            KarstDeltaChunkReader.ChunkPlan plan =
                    planner.planChunk(key.x(), key.z(), LOW_Y, HIGH_Y, neutralRegistry);
            if (plan.state() == KarstDeltaChunkReader.PlanState.ACTIVE
                    && plan.planned().size() >= MINIMUM_TARGET_PLAN_SIZE) {
                eligible.add(key);
            }
        }
        if (eligible.size() < TARGET_CHUNK_COUNT) {
            throw new IllegalStateException("deterministic target chunk selection is incomplete");
        }
        List<KarstDeltaChunkReader.ChunkKey> selected = new ArrayList<>();
        for (int index = 0; index < TARGET_CHUNK_COUNT; index++) {
            int eligibleIndex = Math.toIntExact(Math.round(
                    index * (eligible.size() - 1.0) / (TARGET_CHUNK_COUNT - 1.0)));
            selected.add(eligible.get(eligibleIndex));
        }
        return List.copyOf(selected);
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        if (!initialized) throw new IllegalStateException("probe is not initialized");
        blockRegistryEntryCount = Registries.BLOCK.size();
        biomeRegistryEntryCount = server.getRegistryManager().get(RegistryKeys.BIOME).size();
        structureRegistryEntryCount =
                server.getRegistryManager().get(RegistryKeys.STRUCTURE).size();
        baseStoneTagEntryCount = (int) Registries.BLOCK.streamEntries()
                .filter(entry -> entry.isIn(BlockTags.BASE_STONE_OVERWORLD))
                .count();
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
            ChunkPos position = new ChunkPos(key.x(), key.z());
            server.getOverworld().getChunkManager().setChunkForced(position, true);
        }
        serverStarted = true;
        attemptReceipt();
    }

    public static synchronized void onServerTick(MinecraftServer server) {
        serverTickCount++;
        attemptReceipt();
        if (receiptWritten && !stopRequested) {
            releaseTickets(server);
            stopRequested = true;
            KarstogenesisFabricProbe.LOGGER.info("Run 288 receipt written, stopping disposable server");
            server.stop(false);
        } else if (!receiptWritten && serverTickCount == 100) {
            logGateStatus();
        } else if (!receiptWritten && serverTickCount == 200) {
            logGateStatus();
            releaseTickets(server);
            stopRequested = true;
            KarstogenesisFabricProbe.LOGGER.error(
                    "Run 288 diagnostic gate remained incomplete, stopping disposable server");
            server.stop(false);
        }
    }

    private static void releaseTickets(MinecraftServer server) {
        if (ticketsReleased) return;
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
            ChunkPos position = new ChunkPos(key.x(), key.z());
            server.getOverworld().getChunkManager().setChunkForced(position, false);
        }
        ticketsReleased = true;
    }

    private static void logGateStatus() {
        KarstogenesisFabricProbe.LOGGER.info(
                "Run 288 gate status populate={} carve-head={} carve-tail={} features-head={} "
                        + "features-tail={} complete-order={} target-evaluated={} plans={} samples={} "
                        + "base-stone={} existing-void={} structures={} errors={}",
                populateNoiseReturnChunks.size(),
                carveHeadChunks.size(),
                carveTailChunks.size(),
                featuresHeadChunks.size(),
                featuresTailChunks.size(),
                completeOrderChunks.size(),
                evaluatedTargetChunks.size(),
                packagePlanHashes.size(),
                sampleCount(),
                samplesByState.get(KarstHostArbitration.HostState.REPLACEABLE_SOLID).size(),
                samplesByState.get(KarstHostArbitration.HostState.EXISTING_VOID).size(),
                structureAccessorObservationCount,
                errors);
    }

    public static synchronized void observePopulateNoiseReturn(Chunk chunk) {
        String key = chunkKey(chunk.getPos());
        populateNoiseReturnChunks.add(key);
        advance(key, 0, 1);
    }

    public static synchronized void observeCarveHead(Chunk chunk) {
        String key = chunkKey(chunk.getPos());
        carveHeadChunks.add(key);
        advance(key, 1, 2);
    }

    public static synchronized void observeCarveTail(
            ChunkRegion world,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        String key = chunkKey(chunk.getPos());
        carveTailChunks.add(key);
        advance(key, 2, 3);
        if (isTarget(chunk.getPos()) && evaluatedTargetChunks.add(key)) {
            try {
                evaluateTargetChunk(world, chunk, structureAccessor);
            } catch (RuntimeException exception) {
                String error = errorSummary(exception);
                errors.add(error);
                KarstogenesisFabricProbe.LOGGER.error(
                        "Run 288 target evaluation failed for chunk {}", key, exception);
            }
        }
    }

    public static synchronized void observeFeaturesHead(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        String key = chunkKey(chunk.getPos());
        featuresHeadChunks.add(key);
        advance(key, 3, 4);
        structureAccessorObservationCount++;
    }

    public static synchronized void observeFeaturesTail(Chunk chunk) {
        String key = chunkKey(chunk.getPos());
        featuresTailChunks.add(key);
        advance(key, 4, 5);
        if (phaseProgress.getOrDefault(key, 0) >= 5) completeOrderChunks.add(key);
    }

    private static void advance(String key, int required, int next) {
        int value = phaseProgress.getOrDefault(key, 0);
        if (value == required) phaseProgress.put(key, next);
    }

    private static boolean isTarget(ChunkPos position) {
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
            if (key.x() == position.x && key.z() == position.z) return true;
        }
        return false;
    }

    private static void evaluateTargetChunk(
            StructureWorldAccess world,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        List<StructureStart> starts =
                structureAccessor.getStructureStarts(chunk.getPos(), entry -> true);
        KarstDeltaChunkReader.HostMinecraftRegistry liveRegistry =
                new KarstDeltaChunkReader.HostMinecraftRegistry() {
                    @Override
                    public KarstDeltaChunkReader.Replaceability replaceabilityFor(
                            KarstDeltaChunkReader.Voxel voxel,
                            KarstDeltaChunkReader.VoxelIntent intent) {
                        return KarstDeltaChunkReader.Replaceability.REPLACE;
                    }

                    @Override
                    public String materialFor(
                            KarstDeltaChunkReader.Voxel voxel,
                            KarstDeltaChunkReader.VoxelIntent intent) {
                        BlockState state = chunk.getBlockState(blockPos(voxel));
                        return Registries.BLOCK.getId(state.getBlock()).toString();
                    }

                    @Override
                    public String biomeFor(
                            KarstDeltaChunkReader.Voxel voxel,
                            KarstDeltaChunkReader.VoxelIntent intent) {
                        return biomeIdentity(chunk, blockPos(voxel));
                    }
                };
        KarstDeltaChunkReader.ChunkPlan plan = planner.planChunk(
                chunk.getPos().x, chunk.getPos().z, LOW_Y, HIGH_Y, liveRegistry);
        if (plan.state() != KarstDeltaChunkReader.PlanState.ACTIVE || plan.planned().isEmpty()) {
            throw new IllegalStateException("target package plan is empty or inactive");
        }
        packagePlanHashes.put(chunkKey(chunk.getPos()), plan.planSha256());
        for (KarstDeltaChunkReader.PlannedVoxel planned : plan.planned()) {
            BlockPos position = blockPos(planned.worldVoxel());
            BlockState state = chunk.getBlockState(position);
            KarstHostArbitration.HostState hostState = classify(state);
            TreeMap<String, Sample> stateSamples = samplesByState.get(hostState);
            boolean reserved = false;
            for (StructureStart start : starts) {
                if (start.hasChildren() && start.getBoundingBox().contains(position)) {
                    reserved = true;
                    break;
                }
            }
            stateSamples.put(
                    voxelKey(planned.worldVoxel()),
                    new Sample(planned, hostState, reserved, biomeIdentity(chunk, position)));
        }
    }

    private static KarstHostArbitration.HostState classify(BlockState state) {
        if (state.isAir()) return KarstHostArbitration.HostState.EXISTING_VOID;
        if (!state.getFluidState().isEmpty()) return KarstHostArbitration.HostState.FLUID;
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
            return KarstHostArbitration.HostState.REPLACEABLE_SOLID;
        }
        return KarstHostArbitration.HostState.PRESERVE;
    }

    private static String biomeIdentity(Chunk chunk, BlockPos position) {
        return chunk.getBiomeForNoiseGen(
                        Math.floorDiv(position.getX(), 4),
                        Math.floorDiv(position.getY(), 4),
                        Math.floorDiv(position.getZ(), 4))
                .getKey()
                .map(key -> key.getValue().toString())
                .orElse("host:unregistered-biome");
    }

    private static BlockPos blockPos(KarstDeltaChunkReader.Voxel voxel) {
        return new BlockPos(voxel.x(), voxel.y(), voxel.z());
    }

    private static void attemptReceipt() {
        if (receiptWritten || !ready()) return;
        List<Sample> samples = arbitrationSamples();
        List<KarstHostArbitration.Input> inputs = new ArrayList<>(samples.size());
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            KarstDeltaChunkReader.PlannedVoxel planned = sample.planned();
            KarstHostArbitration.GeometryIntent geometry =
                    new KarstHostArbitration.GeometryIntent(
                            index,
                            planned.protectedRoute(),
                            planned.registeredBoundary(),
                            "karst:preserve-existing");
            KarstHostArbitration.HostObservation host =
                    new KarstHostArbitration.HostObservation(
                            sample.hostState(),
                            sample.structureReserved()
                                    ? KarstHostArbitration.ClaimClass.RESERVED_STRUCTURE
                                    : KarstHostArbitration.ClaimClass.NONE,
                            sample.structureReserved() ? "host:structure" : "host:vanilla",
                            KarstHostArbitration.BiomeAction.PRESERVE_EXISTING,
                            sample.biomeIdentity());
            inputs.add(new KarstHostArbitration.Input(geometry, host));
        }
        KarstHostArbitration.Plan arbitration = KarstHostArbitration.arbitrate(inputs);
        String receipt = receiptJson(samples, arbitration);
        try {
            Files.createDirectories(receiptPath.getParent());
            Path temporary = receiptPath.resolveSibling(receiptPath.getFileName() + ".part");
            Files.writeString(temporary, receipt, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    receiptPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            receiptWritten = true;
        } catch (IOException exception) {
            throw new IllegalStateException("receipt write failed", exception);
        }
    }

    private static boolean ready() {
        return initialized
                && serverStarted
                && planner.bindingState() == KarstDeltaChunkReader.PlanState.ACTIVE
                && targetChunks.size() == TARGET_CHUNK_COUNT
                && evaluatedTargetChunks.size() == TARGET_CHUNK_COUNT
                && packagePlanHashes.size() == TARGET_CHUNK_COUNT
                && populateNoiseReturnChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && carveHeadChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && carveTailChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && featuresHeadChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && featuresTailChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && completeOrderChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && structureAccessorObservationCount >= MINIMUM_CALLBACK_CHUNKS
                && blockRegistryEntryCount > 0
                && biomeRegistryEntryCount > 0
                && structureRegistryEntryCount > 0
                && baseStoneTagEntryCount > 0
                && sampleCount() >= 256
                && samplesByState.get(KarstHostArbitration.HostState.REPLACEABLE_SOLID).size() > 0
                && samplesByState.get(KarstHostArbitration.HostState.EXISTING_VOID).size() > 0
                && errors.isEmpty();
    }

    private static int sampleCount() {
        int result = 0;
        for (TreeMap<String, Sample> values : samplesByState.values()) {
            result += Math.min(values.size(), STATE_SAMPLE_QUOTA);
        }
        return Math.min(result, MAXIMUM_ARBITRATION_SAMPLES);
    }

    private static List<Sample> arbitrationSamples() {
        List<Sample> result = new ArrayList<>();
        for (TreeMap<String, Sample> values : samplesByState.values()) {
            int retained = 0;
            for (Sample sample : values.values()) {
                result.add(sample);
                if (++retained == STATE_SAMPLE_QUOTA) break;
            }
        }
        result.sort(Comparator.comparing(value -> voxelKey(value.planned().worldVoxel())));
        if (result.size() > MAXIMUM_ARBITRATION_SAMPLES) {
            return List.copyOf(result.subList(0, MAXIMUM_ARBITRATION_SAMPLES));
        }
        return List.copyOf(result);
    }

    private static String receiptJson(
            List<Sample> samples,
            KarstHostArbitration.Plan arbitration) {
        EnumMap<KarstHostArbitration.HostState, Integer> counts =
                new EnumMap<>(KarstHostArbitration.HostState.class);
        for (KarstHostArbitration.HostState state : KarstHostArbitration.HostState.values()) {
            counts.put(state, 0);
        }
        int structureReserved = 0;
        for (Sample sample : samples) {
            counts.put(sample.hostState(), counts.get(sample.hostState()) + 1);
            if (sample.structureReserved()) structureReserved++;
        }
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-288-fabric-no-write-q75-callback-receipt.v1", true);
        field(output, "state", "PASS_FABRIC_NO_WRITE_Q75_CALLBACK_PROBE", true);
        number(output, "run", 288, true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        bool(output, "package_binding_active", true, true);
        number(output, "target_chunk_count", TARGET_CHUNK_COUNT, true);
        number(output, "populate_noise_return_chunk_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "carve_head_chunk_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "carve_tail_chunk_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "features_head_chunk_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "features_tail_chunk_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "complete_same_chunk_phase_order_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "block_registry_entry_count", blockRegistryEntryCount, true);
        number(output, "biome_registry_entry_count", biomeRegistryEntryCount, true);
        number(output, "structure_registry_entry_count", structureRegistryEntryCount, true);
        number(output, "base_stone_tag_entry_count", baseStoneTagEntryCount, true);
        number(output, "structure_accessor_observation_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "nonempty_package_plan_count", packagePlanHashes.size(), true);
        number(output, "arbitration_sample_count", samples.size(), true);
        number(output, "real_base_stone_classification_count",
                counts.get(KarstHostArbitration.HostState.REPLACEABLE_SOLID), true);
        number(output, "real_existing_void_classification_count",
                counts.get(KarstHostArbitration.HostState.EXISTING_VOID), true);
        number(output, "real_fluid_classification_count",
                counts.get(KarstHostArbitration.HostState.FLUID), true);
        number(output, "real_preserve_classification_count",
                counts.get(KarstHostArbitration.HostState.PRESERVE), true);
        number(output, "structure_reserved_sample_count", structureReserved, true);
        decimal(output, "preserve_existing_biome_fraction", 1.0, true);
        field(output, "arbitration_state", arbitration.state().name(), true);
        field(output, "arbitration_input_sha256", arbitrationInputDigest(samples), true);
        field(output, "arbitration_plan_sha256", arbitration.planSha256(), true);
        output.append("\"package_plan_sha256_by_chunk\":{");
        int index = 0;
        for (Map.Entry<String, String> entry : packagePlanHashes.entrySet()) {
            if (index++ > 0) output.append(',');
            output.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        output.append("},");
        number(output, "probe_block_write_count", 0, true);
        number(output, "chunk_callback_error_count", errors.size(), false);
        output.append('}').append('\n');
        return output.toString();
    }

    private static String arbitrationInputDigest(List<Sample> samples) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        for (Sample sample : samples) {
            KarstDeltaChunkReader.PlannedVoxel planned = sample.planned();
            update(digest, voxelKey(planned.worldVoxel()));
            update(digest, Boolean.toString(planned.protectedRoute()));
            update(digest, Boolean.toString(planned.registeredBoundary()));
            update(digest, sample.hostState().name());
            update(digest, Boolean.toString(sample.structureReserved()));
            update(digest, sample.biomeIdentity());
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void field(StringBuilder output, String key, String value, boolean comma) {
        output.append('"').append(key).append("\":\"").append(escape(value)).append('"');
        if (comma) output.append(',');
    }

    private static void number(StringBuilder output, String key, long value, boolean comma) {
        output.append('"').append(key).append("\":").append(value);
        if (comma) output.append(',');
    }

    private static void decimal(StringBuilder output, String key, double value, boolean comma) {
        output.append('"').append(key).append("\":")
                .append(String.format(Locale.ROOT, "%.1f", value));
        if (comma) output.append(',');
    }

    private static void bool(StringBuilder output, String key, boolean value, boolean comma) {
        output.append('"').append(key).append("\":").append(value);
        if (comma) output.append(',');
    }

    private static String chunkKey(ChunkPos position) {
        return position.x + "," + position.z;
    }

    private static String voxelKey(KarstDeltaChunkReader.Voxel voxel) {
        return String.format(Locale.ROOT, "%09d,%05d,%09d", voxel.x(), voxel.y(), voxel.z());
    }

    private static String escape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String errorSummary(Throwable throwable) {
        StringBuilder result = new StringBuilder();
        Throwable value = throwable;
        while (value != null) {
            if (!result.isEmpty()) result.append(" <- ");
            result.append(value.getClass().getName())
                    .append(':')
                    .append(safe(value.getMessage()));
            value = value.getCause();
        }
        return result.toString();
    }
}
