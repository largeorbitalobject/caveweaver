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
import java.util.List;
import java.util.Locale;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Frozen one-chunk transaction and deterministic receipt for Karstogenesis run 202. */
public final class TransactionState {
    private static final int RUN = 202;
    private static final int TRANSLATION_X = 604;
    private static final int TRANSLATION_Y = 91;
    private static final int TRANSLATION_Z = 120;
    private static final int TARGET_CHUNK_X = 32;
    private static final int TARGET_CHUNK_Z = 0;
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;
    private static final int EXPECTED_PLANNED = 960;
    private static final int EXPECTED_CARVE = 854;
    private static final int EXPECTED_NOOP = 106;
    private static final int EXPECTED_PROTECTED = 514;
    private static final String EXPECTED_PACKAGE_PLAN =
            "8001bb1503da9c85582ee436595180b2c6fd8b0cc9c5f3b919abb157ee7fb2f2";
    private static final String EXPECTED_ARBITRATION_INPUT =
            "86e667824b1bf60267e8b6e90006e265cf1038774109565389dfc83e5852fdf4";
    private static final String EXPECTED_ARBITRATION_PLAN =
            "75909e8a7a6ef21a4b3e66cef607c317a20188c360a18357fb4347bd04620225";

    private enum Mode {
        BASELINE,
        WRITE
    }

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureReserved,
            String biomeIdentity,
            String priorBlockState) {}

    private static Path receiptPath;
    private static Mode mode;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static List<Sample> samples = List.of();
    private static KarstHostArbitration.Plan arbitration;
    private static String packagePlanSha256 = "";
    private static String arbitrationInputSha256 = "";
    private static String priorBlockStateSha256 = "";
    private static String targetRegisterSha256 = "";
    private static String postFeatureStateSha256 = "";
    private static boolean initialized;
    private static boolean serverStarted;
    private static boolean evaluationAttempted;
    private static boolean prewriteValidated;
    private static boolean featureAuditComplete;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketReleased;
    private static int serverTickCount;
    private static int plannedCount;
    private static int replaceableCount;
    private static int existingVoidCount;
    private static int protectedCount;
    private static int boundaryCount;
    private static int structureReservedCount;
    private static int fluidCount;
    private static int preserveCount;
    private static int blockEntityTargetCount;
    private static int blockWriteCount;
    private static int immediateTraversableCount;
    private static int postFeatureTraversableCount;
    private static int postFeatureProtectedTraversableCount;
    private static final List<String> errors = new ArrayList<>();

    private TransactionState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String packageProperty = System.getProperty("geoworld.transaction.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.transaction.receipt", "").trim();
        String modeProperty = System.getProperty("geoworld.transaction.mode", "").trim();
        if (packageProperty.isEmpty() || receiptProperty.isEmpty() || modeProperty.isEmpty()) {
            throw new IllegalStateException("transaction package, receipt or mode is empty");
        }
        mode = switch (modeProperty) {
            case "baseline" -> Mode.BASELINE;
            case "write" -> Mode.WRITE;
            default -> throw new IllegalArgumentException("unknown transaction mode");
        };
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        try {
            reader = KarstDeltaChunkReader.open(Path.of(packageProperty), true);
        } catch (IOException exception) {
            throw new IllegalStateException("package open failed", exception);
        }
        planner = KarstDeltaChunkReader.MinecraftIntentPlanner.bind(
                reader,
                reader.cacheIdentitySha256(),
                new KarstDeltaChunkReader.Translation(
                        TRANSLATION_X, TRANSLATION_Y, TRANSLATION_Z),
                new KarstDeltaChunkReader.WorldBounds(
                        new KarstDeltaChunkReader.Voxel(-30_000_000, LOW_Y, -30_000_000),
                        new KarstDeltaChunkReader.Voxel(29_999_999, HIGH_Y, 29_999_999)));
        if (planner.bindingState() != KarstDeltaChunkReader.PlanState.ACTIVE) {
            throw new IllegalStateException("transaction planner binding is inactive");
        }
        initialized = true;
        KarstogenesisFabricTransaction.LOGGER.info(
                "Run 202 transaction initialized mode={} chunk={},{}",
                mode.name(),
                TARGET_CHUNK_X,
                TARGET_CHUNK_Z);
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        if (!initialized) throw new IllegalStateException("transaction is not initialized");
        server.getOverworld().getChunkManager().setChunkForced(
                new ChunkPos(TARGET_CHUNK_X, TARGET_CHUNK_Z), true);
        serverStarted = true;
    }

    public static synchronized void onServerTick(MinecraftServer server) {
        serverTickCount++;
        if (receiptWritten && !stopRequested) {
            releaseTicket(server);
            stopRequested = true;
            KarstogenesisFabricTransaction.LOGGER.info(
                    "Run 202 receipt written, stopping disposable server");
            server.stop(false);
        } else if (!receiptWritten && serverTickCount % 200 == 0) {
            KarstogenesisFabricTransaction.LOGGER.info(
                    "Run 202 gate mode={} attempted={} validated={} features={} writes={} errors={}",
                    mode.name(),
                    evaluationAttempted,
                    prewriteValidated,
                    featureAuditComplete,
                    blockWriteCount,
                    errors);
        } else if (!receiptWritten && serverTickCount == 800) {
            releaseTicket(server);
            stopRequested = true;
            KarstogenesisFabricTransaction.LOGGER.error(
                    "Run 202 gate remained incomplete, stopping disposable server");
            server.stop(false);
        }
    }

    private static void releaseTicket(MinecraftServer server) {
        if (ticketReleased) return;
        server.getOverworld().getChunkManager().setChunkForced(
                new ChunkPos(TARGET_CHUNK_X, TARGET_CHUNK_Z), false);
        ticketReleased = true;
    }

    public static synchronized void applyCarveTail(
            ChunkRegion world,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        if (!isTarget(chunk.getPos()) || evaluationAttempted) return;
        evaluationAttempted = true;
        try {
            observeAndValidate(world, chunk, structureAccessor);
            prewriteValidated = true;
            if (mode == Mode.WRITE) applyWrites(chunk);
            immediateTraversableCount = countTraversable(chunk, false);
        } catch (RuntimeException exception) {
            errors.add(errorSummary(exception));
            KarstogenesisFabricTransaction.LOGGER.error(
                    "Run 202 transaction failed before feature audit", exception);
        }
    }

    private static void observeAndValidate(
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
                TARGET_CHUNK_X, TARGET_CHUNK_Z, LOW_Y, HIGH_Y, liveRegistry);
        if (plan.state() != KarstDeltaChunkReader.PlanState.ACTIVE || plan.planned().isEmpty()) {
            throw new IllegalStateException("selected package plan is empty or inactive");
        }
        packagePlanSha256 = plan.planSha256();
        List<Sample> observed = new ArrayList<>(plan.planned().size());
        for (KarstDeltaChunkReader.PlannedVoxel planned : plan.planned()) {
            BlockPos position = blockPos(planned.worldVoxel());
            BlockState state = chunk.getBlockState(position);
            boolean reserved = false;
            for (StructureStart start : starts) {
                if (start.hasChildren() && start.getBoundingBox().contains(position)) {
                    reserved = true;
                    break;
                }
            }
            observed.add(new Sample(
                    planned,
                    classify(state),
                    reserved,
                    biomeIdentity(chunk, position),
                    state.toString()));
        }
        observed.sort(Comparator.comparing(value -> voxelKey(value.planned().worldVoxel())));
        samples = List.copyOf(observed);
        plannedCount = samples.size();
        for (Sample sample : samples) {
            switch (sample.hostState()) {
                case REPLACEABLE_SOLID -> replaceableCount++;
                case EXISTING_VOID -> existingVoidCount++;
                case FLUID -> fluidCount++;
                case PRESERVE, UNKNOWN -> preserveCount++;
            }
            if (sample.structureReserved()) structureReservedCount++;
            if (sample.planned().protectedRoute()) protectedCount++;
            if (sample.planned().registeredBoundary()) boundaryCount++;
            BlockState state = chunk.getBlockState(blockPos(sample.planned().worldVoxel()));
            if (state.hasBlockEntity()) blockEntityTargetCount++;
        }
        arbitration = arbitrate(samples);
        arbitrationInputSha256 = inputDigest(samples);
        priorBlockStateSha256 = priorStateDigest(samples);
        targetRegisterSha256 = targetRegisterDigest(samples, arbitration);
        require(plannedCount == EXPECTED_PLANNED, "planned voxel count differs");
        require(replaceableCount == EXPECTED_CARVE, "replaceable count differs");
        require(existingVoidCount == EXPECTED_NOOP, "existing void count differs");
        require(protectedCount == EXPECTED_PROTECTED, "protected count differs");
        require(boundaryCount == 0, "boundary count differs");
        require(structureReservedCount == 0, "structure reservation differs");
        require(fluidCount == 0, "fluid count differs");
        require(preserveCount == 0, "preserve count differs");
        require(blockEntityTargetCount == 0, "block entity target differs");
        require(packagePlanSha256.equals(EXPECTED_PACKAGE_PLAN), "package plan digest differs");
        require(
                arbitrationInputSha256.equals(EXPECTED_ARBITRATION_INPUT),
                "arbitration input digest differs");
        require(
                arbitration.planSha256().equals(EXPECTED_ARBITRATION_PLAN),
                "arbitration plan digest differs");
        require(
                arbitration.state() == KarstHostArbitration.PlanState.ACTIVE,
                "arbitration is not active");
        int carveDecisions = 0;
        int noopDecisions = 0;
        for (KarstHostArbitration.Decision decision : arbitration.decisions()) {
            if (decision.action() == KarstHostArbitration.Action.CARVE) carveDecisions++;
            if (decision.action() == KarstHostArbitration.Action.NOOP_EXISTING_VOID) {
                noopDecisions++;
            }
        }
        require(carveDecisions == EXPECTED_CARVE, "carve decision count differs");
        require(noopDecisions == EXPECTED_NOOP, "no-op decision count differs");
    }

    private static void applyWrites(Chunk chunk) {
        List<KarstHostArbitration.Decision> decisions = arbitration.decisions();
        require(decisions.size() == samples.size(), "decision size differs");
        for (int index = 0; index < samples.size(); index++) {
            if (decisions.get(index).action() != KarstHostArbitration.Action.CARVE) continue;
            BlockPos position = blockPos(samples.get(index).planned().worldVoxel());
            chunk.setBlockState(position, Blocks.CAVE_AIR.getDefaultState(), false);
            blockWriteCount++;
        }
        require(blockWriteCount == EXPECTED_CARVE, "write count differs");
    }

    public static synchronized void observeFeaturesTail(Chunk chunk) {
        if (!isTarget(chunk.getPos()) || featureAuditComplete || !evaluationAttempted) return;
        featureAuditComplete = true;
        postFeatureTraversableCount = countTraversable(chunk, false);
        postFeatureProtectedTraversableCount = countTraversable(chunk, true);
        postFeatureStateSha256 = postFeatureDigest(chunk);
        writeReceipt();
    }

    private static int countTraversable(Chunk chunk, boolean protectedOnly) {
        int result = 0;
        for (Sample sample : samples) {
            if (protectedOnly && !sample.planned().protectedRoute()) continue;
            if (chunk.getBlockState(blockPos(sample.planned().worldVoxel())).isAir()) result++;
        }
        return result;
    }

    private static void writeReceipt() {
        boolean pass = prewriteValidated
                && featureAuditComplete
                && errors.isEmpty()
                && (mode == Mode.BASELINE
                        ? blockWriteCount == 0
                        : blockWriteCount == EXPECTED_CARVE
                                && immediateTraversableCount == EXPECTED_PLANNED);
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-202-fabric-one-chunk-transaction-receipt.v1", true);
        field(output, "state", pass
                ? "PASS_FABRIC_ONE_CHUNK_TRANSACTION_ARM"
                : "FAIL_FABRIC_ONE_CHUNK_TRANSACTION_ARM", true);
        number(output, "run", RUN, true);
        field(output, "mode", mode.name(), true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        field(output, "world_chunk", TARGET_CHUNK_X + "," + TARGET_CHUNK_Z, true);
        number(output, "planned_voxel_count", plannedCount, true);
        number(output, "replaceable_solid_count", replaceableCount, true);
        number(output, "existing_void_count", existingVoidCount, true);
        number(output, "protected_route_count", protectedCount, true);
        number(output, "registered_boundary_count", boundaryCount, true);
        number(output, "structure_reserved_count", structureReservedCount, true);
        number(output, "fluid_count", fluidCount, true);
        number(output, "preserve_count", preserveCount, true);
        number(output, "block_entity_target_count", blockEntityTargetCount, true);
        field(output, "package_plan_sha256", packagePlanSha256, true);
        field(output, "arbitration_input_sha256", arbitrationInputSha256, true);
        field(output, "arbitration_plan_sha256",
                arbitration == null ? "" : arbitration.planSha256(), true);
        field(output, "prior_block_state_sha256", priorBlockStateSha256, true);
        field(output, "target_register_sha256", targetRegisterSha256, true);
        field(output, "post_feature_state_sha256", postFeatureStateSha256, true);
        number(output, "block_write_count", blockWriteCount, true);
        number(output, "immediate_traversable_count", immediateTraversableCount, true);
        number(output, "post_feature_traversable_count", postFeatureTraversableCount, true);
        number(output, "post_feature_protected_traversable_count",
                postFeatureProtectedTraversableCount, true);
        number(output, "biome_write_count", 0, true);
        number(output, "structure_write_count", 0, true);
        number(output, "feature_registry_write_count", 0, true);
        bool(output, "persistent_forced_chunk_state_used", false, true);
        output.append("\"target_register\":[");
        List<KarstHostArbitration.Decision> decisions =
                arbitration == null ? List.of() : arbitration.decisions();
        for (int index = 0; index < samples.size(); index++) {
            if (index > 0) output.append(',');
            Sample sample = samples.get(index);
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            output.append('{');
            number(output, "x", voxel.x(), true);
            number(output, "y", voxel.y(), true);
            number(output, "z", voxel.z(), true);
            bool(output, "protected", sample.planned().protectedRoute(), true);
            bool(output, "boundary", sample.planned().registeredBoundary(), true);
            field(output, "action", decisions.get(index).action().name(), true);
            field(output, "prior_block", sample.priorBlockState(), false);
            output.append('}');
        }
        output.append("],");
        number(output, "chunk_callback_error_count", errors.size(), false);
        output.append('}').append('\n');
        try {
            Files.createDirectories(receiptPath.getParent());
            Path temporary = receiptPath.resolveSibling(receiptPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
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

    private static KarstHostArbitration.Plan arbitrate(List<Sample> values) {
        List<KarstHostArbitration.Input> inputs = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Sample sample = values.get(index);
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
        return KarstHostArbitration.arbitrate(inputs);
    }

    private static KarstHostArbitration.HostState classify(BlockState state) {
        if (state.isAir()) return KarstHostArbitration.HostState.EXISTING_VOID;
        if (!state.getFluidState().isEmpty()) return KarstHostArbitration.HostState.FLUID;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD)) {
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

    private static String inputDigest(List<Sample> values) {
        MessageDigest digest = digest();
        for (Sample sample : values) {
            KarstDeltaChunkReader.PlannedVoxel planned = sample.planned();
            update(digest, voxelKey(planned.worldVoxel()));
            update(digest, Boolean.toString(planned.protectedRoute()));
            update(digest, Boolean.toString(planned.registeredBoundary()));
            update(digest, sample.hostState().name());
            update(digest, Boolean.toString(sample.structureReserved()));
            update(digest, sample.biomeIdentity());
        }
        return hex(digest.digest());
    }

    private static String priorStateDigest(List<Sample> values) {
        MessageDigest digest = digest();
        for (Sample sample : values) {
            update(digest, voxelKey(sample.planned().worldVoxel()));
            update(digest, sample.priorBlockState());
        }
        return hex(digest.digest());
    }

    private static String targetRegisterDigest(
            List<Sample> values,
            KarstHostArbitration.Plan plan) {
        MessageDigest digest = digest();
        for (int index = 0; index < values.size(); index++) {
            Sample sample = values.get(index);
            update(digest, voxelKey(sample.planned().worldVoxel()));
            update(digest, Boolean.toString(sample.planned().protectedRoute()));
            update(digest, Boolean.toString(sample.planned().registeredBoundary()));
            update(digest, plan.decisions().get(index).action().name());
        }
        return hex(digest.digest());
    }

    private static String postFeatureDigest(Chunk chunk) {
        MessageDigest digest = digest();
        for (Sample sample : samples) {
            BlockPos position = blockPos(sample.planned().worldVoxel());
            update(digest, voxelKey(sample.planned().worldVoxel()));
            update(digest, chunk.getBlockState(position).toString());
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hex(byte[] values) {
        StringBuilder result = new StringBuilder();
        for (byte value : values) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static boolean isTarget(ChunkPos position) {
        return position.x == TARGET_CHUNK_X && position.z == TARGET_CHUNK_Z;
    }

    private static BlockPos blockPos(KarstDeltaChunkReader.Voxel voxel) {
        return new BlockPos(voxel.x(), voxel.y(), voxel.z());
    }

    private static String voxelKey(KarstDeltaChunkReader.Voxel voxel) {
        return String.format(Locale.ROOT, "%09d,%05d,%09d", voxel.x(), voxel.y(), voxel.z());
    }

    private static void field(StringBuilder output, String key, String value, boolean comma) {
        output.append('"').append(key).append("\":\"").append(escape(value)).append('"');
        if (comma) output.append(',');
    }

    private static void number(StringBuilder output, String key, long value, boolean comma) {
        output.append('"').append(key).append("\":").append(value);
        if (comma) output.append(',');
    }

    private static void bool(StringBuilder output, String key, boolean value, boolean comma) {
        output.append('"').append(key).append("\":").append(value);
        if (comma) output.append(',');
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
