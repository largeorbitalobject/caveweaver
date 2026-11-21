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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Frozen post-feature provider write for Karstogenesis run 219. */
public final class ProviderWriteState {
    private static final int RUN = 219;
    private static final int TRANSLATION_X = 4704;
    private static final int TRANSLATION_Y = 91;
    private static final int TRANSLATION_Z = 2816;
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;
    private static final int EXPECTED_CHUNKS = 59;
    private static final int EXPECTED_PLANNED = 23328;
    private static final int EXPECTED_CARVE = 19224;
    private static final int EXPECTED_NOOP = 4104;
    private static final int EXPECTED_PROTECTED = 5395;
    private static final int EXPECTED_BOUNDARY = 42;
    private static final double MINIMUM_REALIZABLE_FRACTION = 0.9;
    private static final int AUDIT_LOW_CHUNK_X = 270;
    private static final int AUDIT_HIGH_CHUNK_X = 279;
    private static final int AUDIT_LOW_CHUNK_Z = 167;
    private static final int AUDIT_HIGH_CHUNK_Z = 176;
    private static final int EXPECTED_AUDIT_CHUNKS = 100;

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureUnionReserved,
            boolean structurePieceReserved,
            boolean blockEntity,
            String biomeIdentity,
            String priorBlockState,
            String action) {}

    private record Repair(
            KarstDeltaChunkReader.Voxel voxel,
            boolean protectedRoute,
            boolean boundary,
            String priorBlockState) {}

    private record ChunkObservation(
            KarstDeltaChunkReader.ChunkKey key,
            String packagePlanSha256,
            String arbitrationInputSha256,
            String arbitrationPlanSha256,
            boolean rejected,
            List<Sample> samples) {}

    private record FrozenTarget(
            boolean protectedRoute,
            boolean boundary,
            String action,
            String priorBlockState) {}

    private record PostCounts(int traversable, int protectedTraversable, int boundaryTraversable) {}

    private static final class Counts {
        int planned;
        int replaceable;
        int existingVoid;
        int fluid;
        int preserve;
        int structureReserved;
        int blockEntity;
        int protectedRoute;
        int boundary;
        int carve;
        int noop;
        int deferFluid;
        int skip;
        int reject;
        int protectedAdmissible;
        int boundaryAdmissible;
    }

    private static Path receiptPath;
    private static Path registerPath;
    private static Path repairPath;
    private static String primaryMod;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static List<KarstDeltaChunkReader.ChunkKey> targetChunks = List.of();
    private static List<KarstDeltaChunkReader.ChunkKey> auditChunks = List.of();
    private static Set<String> targetChunkKeys = Set.of();
    private static Set<String> auditChunkKeys = Set.of();
    private static Map<String, FrozenTarget> frozenTargets = Map.of();
    private static String frozenBindingSha256 = "";
    private static final Set<String> observedCarveChunks = new HashSet<>();
    private static final Set<String> observedFeatureChunks = new HashSet<>();
    private static final Set<String> observedAuditFeatureChunks = new HashSet<>();
    private static final TreeMap<String, ChunkObservation> observations = new TreeMap<>();
    private static final TreeMap<String, PostCounts> postCounts = new TreeMap<>();
    private static final List<Repair> repairs = new ArrayList<>();
    private static final List<String> errors = new ArrayList<>();
    private static boolean initialized;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketsReleased;
    private static boolean repairStarted;
    private static boolean repairComplete;
    private static int serverTickCount;
    private static int directWriteCount;
    private static int repairWriteCount;

    private ProviderWriteState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String packageProperty = System.getProperty("geoworld.write219.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.write219.receipt", "").trim();
        String registerProperty = System.getProperty("geoworld.write219.register", "").trim();
        String repairProperty = System.getProperty("geoworld.write219.repair", "").trim();
        primaryMod = System.getProperty("geoworld.write219.primaryMod", "").trim();
        if (packageProperty.isEmpty()
                || receiptProperty.isEmpty()
                || registerProperty.isEmpty()
                || repairProperty.isEmpty()
                || primaryMod.isEmpty()) {
            throw new IllegalStateException("Run 219 package or output property is empty");
        }
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        registerPath = Path.of(registerProperty).toAbsolutePath().normalize();
        repairPath = Path.of(repairProperty).toAbsolutePath().normalize();
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
        require(
                planner.bindingState() == KarstDeltaChunkReader.PlanState.ACTIVE,
                "multichunk planner binding is inactive");
        targetChunks = selectTargetChunks();
        require(targetChunks.size() == EXPECTED_CHUNKS, "target chunk count differs");
        auditChunks = selectAuditChunks();
        require(auditChunks.size() == EXPECTED_AUDIT_CHUNKS, "audit chunk count differs");
        Set<String> keys = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) keys.add(chunkKey(key));
        targetChunkKeys = Set.copyOf(keys);
        Set<String> auditKeys = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : auditChunks) auditKeys.add(chunkKey(key));
        auditChunkKeys = Set.copyOf(auditKeys);
        loadFrozenBinding();
        initialized = true;
        KarstogenesisFabricProviderWrite.LOGGER.info(
                "Run 219 initialized target-chunks={} audit-chunks={} frozen-targets={}",
                targetChunks.size(),
                auditChunks.size(),
                frozenTargets.size());
    }

    private static List<KarstDeltaChunkReader.ChunkKey> selectTargetChunks() {
        List<KarstDeltaChunkReader.ChunkKey> result = new ArrayList<>();
        KarstDeltaChunkReader.HostMinecraftRegistry registry = neutralRegistry();
        for (KarstDeltaChunkReader.ChunkKey key : planner.candidateWorldChunks()) {
            KarstDeltaChunkReader.ChunkPlan plan =
                    planner.planChunk(key.x(), key.z(), LOW_Y, HIGH_Y, registry);
            if (plan.state() == KarstDeltaChunkReader.PlanState.ACTIVE
                    && !plan.planned().isEmpty()) {
                result.add(key);
            }
        }
        result.sort(Comparator.comparingInt(KarstDeltaChunkReader.ChunkKey::x)
                .thenComparingInt(KarstDeltaChunkReader.ChunkKey::z));
        return List.copyOf(result);
    }

    private static List<KarstDeltaChunkReader.ChunkKey> selectAuditChunks() {
        List<KarstDeltaChunkReader.ChunkKey> result = new ArrayList<>();
        for (int chunkX = AUDIT_LOW_CHUNK_X; chunkX <= AUDIT_HIGH_CHUNK_X; chunkX++) {
            for (int chunkZ = AUDIT_LOW_CHUNK_Z; chunkZ <= AUDIT_HIGH_CHUNK_Z; chunkZ++) {
                result.add(new KarstDeltaChunkReader.ChunkKey(chunkX, chunkZ));
            }
        }
        return List.copyOf(result);
    }

    private static KarstDeltaChunkReader.HostMinecraftRegistry neutralRegistry() {
        return new KarstDeltaChunkReader.HostMinecraftRegistry() {
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
    }

    private static void loadFrozenBinding() {
        String bindingProperty = System.getProperty("geoworld.write219.binding", "").trim();
        String hashProperty =
                System.getProperty("geoworld.write219.bindingSha256", "").trim();
        if (bindingProperty.isEmpty() || hashProperty.length() != 64) {
            throw new IllegalStateException("Run 219 requires a frozen binding and digest");
        }
        Path path = Path.of(bindingProperty).toAbsolutePath().normalize();
        try {
            frozenBindingSha256 = sha256(path);
            require(frozenBindingSha256.equals(hashProperty), "frozen binding digest differs");
            Map<String, FrozenTarget> values = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 7);
                require(fields.length == 7, "frozen binding row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                FrozenTarget previous = values.put(
                        key,
                        new FrozenTarget(
                                Boolean.parseBoolean(fields[3]),
                                Boolean.parseBoolean(fields[4]),
                                fields[5],
                                fields[6]));
                require(previous == null, "frozen binding repeats a target");
            }
            require(values.size() == EXPECTED_PLANNED, "frozen binding target count differs");
            frozenTargets = Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("frozen binding read failed", exception);
        }
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "Run 219 closure is not initialized");
        for (KarstDeltaChunkReader.ChunkKey key : auditChunks) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), true);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        boolean shouldRepair;
        synchronized (ProviderWriteState.class) {
            serverTickCount++;
            shouldRepair = !repairStarted
                    && !repairComplete
                    && observedCarveChunks.size() == EXPECTED_CHUNKS
                    && observedAuditFeatureChunks.size() == EXPECTED_AUDIT_CHUNKS;
            if (shouldRepair) repairStarted = true;
        }
        if (shouldRepair) {
            try {
                applyProtectedClosure(server);
            } catch (RuntimeException exception) {
                synchronized (ProviderWriteState.class) {
                    errors.add("repair:" + errorSummary(exception));
                }
                KarstogenesisFabricProviderWrite.LOGGER.error(
                        "Run 219 provider write failed", exception);
            }
            try {
                releaseTickets(server);
            } catch (RuntimeException exception) {
                synchronized (ProviderWriteState.class) {
                    errors.add("ticket-release:" + errorSummary(exception));
                }
            }
            synchronized (ProviderWriteState.class) {
                repairComplete = true;
            }
        }
        boolean shouldStop = false;
        synchronized (ProviderWriteState.class) {
            attemptReceipt();
            if (receiptWritten && !stopRequested) {
                stopRequested = true;
                shouldStop = true;
            } else if (!receiptWritten && serverTickCount % 200 == 0) {
                KarstogenesisFabricProviderWrite.LOGGER.info(
                        "Run 219 gate carve={}/{} target-features={}/{} audit-features={}/{} direct={} repair={} errors={}",
                        observedCarveChunks.size(),
                        EXPECTED_CHUNKS,
                        observedFeatureChunks.size(),
                        EXPECTED_CHUNKS,
                        observedAuditFeatureChunks.size(),
                        EXPECTED_AUDIT_CHUNKS,
                        directWriteCount,
                        repairWriteCount,
                        errors.size());
            } else if (!receiptWritten && serverTickCount == 6000) {
                errors.add("timeout-before-complete-receipt");
                writeOutputs();
                stopRequested = true;
                shouldStop = true;
            }
        }
        if (shouldStop) {
            if (!ticketsReleased) releaseTickets(server);
            KarstogenesisFabricProviderWrite.LOGGER.info(
                    "Run 219 receipt written, stopping disposable server");
            server.stop(false);
        }
    }

    private static void releaseTickets(MinecraftServer server) {
        if (ticketsReleased) return;
        for (KarstDeltaChunkReader.ChunkKey key : auditChunks) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), false);
        }
        ticketsReleased = true;
    }

    public static synchronized void observeCarveTail(
            ChunkRegion world,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        String key = chunkKey(chunk.getPos());
        if (!targetChunkKeys.contains(key) || !observedCarveChunks.add(key)) return;
        try {
            ChunkObservation observation = observePlan(world, chunk, structureAccessor);
            validateFrozen(observation);
            require(!observation.rejected(), "frozen chunk arbitration rejected");
            applyWrites(chunk, observation.samples());
            observations.put(key, observation);
        } catch (RuntimeException exception) {
            errors.add(key + ":" + errorSummary(exception));
            KarstogenesisFabricProviderWrite.LOGGER.error(
                    "Run 219 carve-tail observation failed for {}", key, exception);
        }
        attemptReceipt();
    }

    public static synchronized void observeFeaturesTail(Chunk chunk) {
        String key = chunkKey(chunk.getPos());
        if (!auditChunkKeys.contains(key) || !observedAuditFeatureChunks.add(key)) return;
        if (!targetChunkKeys.contains(key) || !observedFeatureChunks.add(key)) {
            attemptReceipt();
            return;
        }
        ChunkObservation observation = observations.get(key);
        if (observation != null) {
            int traversable = 0;
            int protectedTraversable = 0;
            int boundaryTraversable = 0;
            for (Sample sample : observation.samples()) {
                boolean air = chunk.getBlockState(blockPos(sample.planned().worldVoxel())).isAir();
                if (air) traversable++;
                if (air && sample.planned().protectedRoute()) protectedTraversable++;
                if (air && sample.planned().registeredBoundary()) boundaryTraversable++;
            }
            postCounts.put(
                    key,
                    new PostCounts(traversable, protectedTraversable, boundaryTraversable));
        }
        attemptReceipt();
    }

    private static void applyProtectedClosure(MinecraftServer server) {
        require(!repairComplete, "provider write already completed");
        require(observations.size() == EXPECTED_CHUNKS, "provider write observations differ");
        repairs.clear();
        for (ChunkObservation observation : observations.values()) {
            Chunk chunk = server.getOverworld().getChunk(
                    observation.key().x(), observation.key().z());
            for (Sample sample : observation.samples()) {
                if (!sample.planned().protectedRoute()
                        && !sample.planned().registeredBoundary()) {
                    continue;
                }
                BlockPos position = blockPos(sample.planned().worldVoxel());
                BlockState state = chunk.getBlockState(position);
                if (state.isAir()) continue;
                repairs.add(new Repair(
                        sample.planned().worldVoxel(),
                        sample.planned().protectedRoute(),
                        sample.planned().registeredBoundary(),
                        state.toString()));
                writeCaveAir(chunk, position);
                repairWriteCount++;
            }
        }
        repairs.sort(Comparator.comparing(value -> voxelKey(value.voxel())));
        postCounts.clear();
        for (ChunkObservation observation : observations.values()) {
            Chunk chunk = server.getOverworld().getChunk(
                    observation.key().x(), observation.key().z());
            int traversable = 0;
            int protectedTraversable = 0;
            int boundaryTraversable = 0;
            for (Sample sample : observation.samples()) {
                boolean air = chunk.getBlockState(
                        blockPos(sample.planned().worldVoxel())).isAir();
                if (air) traversable++;
                if (air && sample.planned().protectedRoute()) protectedTraversable++;
                if (air && sample.planned().registeredBoundary()) boundaryTraversable++;
            }
            postCounts.put(
                    chunkKey(observation.key()),
                    new PostCounts(traversable, protectedTraversable, boundaryTraversable));
        }
        writeRepairRegister();
        KarstogenesisFabricProviderWrite.LOGGER.info(
                "Run 219 provider write completed repairs={}", repairWriteCount);
    }

    private static ChunkObservation observePlan(
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
        require(
                plan.state() == KarstDeltaChunkReader.PlanState.ACTIVE
                        && !plan.planned().isEmpty(),
                "target package plan is empty or inactive");
        List<Sample> raw = new ArrayList<>(plan.planned().size());
        for (KarstDeltaChunkReader.PlannedVoxel planned : plan.planned()) {
            BlockPos position = blockPos(planned.worldVoxel());
            BlockState state = chunk.getBlockState(position);
            boolean unionReserved = false;
            boolean pieceReserved = false;
            for (StructureStart start : starts) {
                if (!start.hasChildren()) continue;
                if (start.getBoundingBox().contains(position)) unionReserved = true;
                for (StructurePiece piece : start.getChildren()) {
                    if (piece.getBoundingBox().contains(position)) {
                        pieceReserved = true;
                        break;
                    }
                }
                if (pieceReserved && unionReserved) break;
            }
            raw.add(new Sample(
                    planned,
                    classify(state),
                    unionReserved,
                    pieceReserved,
                    state.hasBlockEntity(),
                    biomeIdentity(chunk, position),
                    state.toString(),
                    ""));
        }
        raw.sort(Comparator.comparing(sample -> voxelKey(sample.planned().worldVoxel())));
        KarstHostArbitration.Plan arbitration = arbitrate(raw);
        List<Sample> samples = new ArrayList<>(raw.size());
        if (arbitration.state() == KarstHostArbitration.PlanState.ACTIVE) {
            require(arbitration.decisions().size() == raw.size(), "decision size differs");
            for (int index = 0; index < raw.size(); index++) {
                Sample sample = raw.get(index);
                samples.add(withAction(sample, arbitration.decisions().get(index).action().name()));
            }
        } else {
            for (Sample sample : raw) samples.add(withAction(sample, "REJECT"));
        }
        return new ChunkObservation(
                new KarstDeltaChunkReader.ChunkKey(chunk.getPos().x, chunk.getPos().z),
                plan.planSha256(),
                inputDigest(raw),
                arbitration.planSha256(),
                arbitration.state() == KarstHostArbitration.PlanState.REJECTED,
                List.copyOf(samples));
    }

    private static Sample withAction(Sample sample, String action) {
        return new Sample(
                sample.planned(),
                sample.hostState(),
                sample.structureUnionReserved(),
                sample.structurePieceReserved(),
                sample.blockEntity(),
                sample.biomeIdentity(),
                sample.priorBlockState(),
                action);
    }

    private static void validateFrozen(ChunkObservation observation) {
        for (Sample sample : observation.samples()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            FrozenTarget frozen = frozenTargets.get(voxelKey(voxel));
            require(frozen != null, "frozen target is absent");
            require(
                    frozen.protectedRoute() == sample.planned().protectedRoute(),
                    "frozen protected flag differs");
            require(
                    frozen.boundary() == sample.planned().registeredBoundary(),
                    "frozen boundary flag differs");
            require(frozen.action().equals(sample.action()), "frozen action differs");
            require(
                    frozen.priorBlockState().equals(sample.priorBlockState()),
                    "frozen prior block differs");
        }
    }

    private static void applyWrites(Chunk chunk, List<Sample> samples) {
        for (Sample sample : samples) {
            if (!"CARVE".equals(sample.action())) continue;
            BlockPos position = blockPos(sample.planned().worldVoxel());
            writeCaveAir(chunk, position);
            directWriteCount++;
        }
    }

    private static void writeCaveAir(Chunk chunk, BlockPos position) {
        chunk.setBlockState(position, Blocks.CAVE_AIR.getDefaultState(), false);
    }

    private static KarstHostArbitration.Plan arbitrate(List<Sample> samples) {
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
                            sample.structurePieceReserved()
                                    ? KarstHostArbitration.ClaimClass.RESERVED_STRUCTURE
                                    : KarstHostArbitration.ClaimClass.NONE,
                            sample.structurePieceReserved()
                                    ? "host:structure-piece"
                                    : "host:vanilla",
                            KarstHostArbitration.BiomeAction.PRESERVE_EXISTING,
                            sample.biomeIdentity());
            inputs.add(new KarstHostArbitration.Input(geometry, host));
        }
        return KarstHostArbitration.arbitrate(inputs);
    }

    private static KarstHostArbitration.HostState classify(BlockState state) {
        if (state.isAir()) return KarstHostArbitration.HostState.EXISTING_VOID;
        if (!state.getFluidState().isEmpty()) return KarstHostArbitration.HostState.FLUID;
        if (state.isIn(BlockTags.OVERWORLD_CARVER_REPLACEABLES)) {
            return KarstHostArbitration.HostState.REPLACEABLE_SOLID;
        }
        return KarstHostArbitration.HostState.PRESERVE;
    }

    private static Counts counts(List<Sample> samples) {
        Counts result = new Counts();
        for (Sample sample : samples) {
            result.planned++;
            switch (sample.hostState()) {
                case REPLACEABLE_SOLID -> result.replaceable++;
                case EXISTING_VOID -> result.existingVoid++;
                case FLUID -> result.fluid++;
                case PRESERVE, UNKNOWN -> result.preserve++;
            }
            if (sample.structurePieceReserved()) result.structureReserved++;
            if (sample.blockEntity()) result.blockEntity++;
            if (sample.planned().protectedRoute()) result.protectedRoute++;
            if (sample.planned().registeredBoundary()) result.boundary++;
            switch (sample.action()) {
                case "CARVE" -> result.carve++;
                case "NOOP_EXISTING_VOID" -> result.noop++;
                case "DEFER_FLUID" -> result.deferFluid++;
                case "SKIP" -> result.skip++;
                default -> result.reject++;
            }
            boolean admissible = "CARVE".equals(sample.action())
                    || "NOOP_EXISTING_VOID".equals(sample.action());
            if (admissible && sample.planned().protectedRoute()) {
                result.protectedAdmissible++;
            }
            if (admissible && sample.planned().registeredBoundary()) {
                result.boundaryAdmissible++;
            }
        }
        return result;
    }

    private static void attemptReceipt() {
        if (receiptWritten
                || observedCarveChunks.size() != EXPECTED_CHUNKS
                || observedFeatureChunks.size() != EXPECTED_CHUNKS
                || observedAuditFeatureChunks.size() != EXPECTED_AUDIT_CHUNKS
                || !repairComplete) {
            return;
        }
        writeOutputs();
    }

    private static void writeOutputs() {
        if (receiptWritten) return;
        List<Sample> samples = new ArrayList<>();
        for (ChunkObservation observation : observations.values()) {
            samples.addAll(observation.samples());
        }
        samples.sort(Comparator.comparing(sample -> voxelKey(sample.planned().worldVoxel())));
        Counts counts = counts(samples);
        int rejectedChunks = (int) observations.values().stream()
                .filter(ChunkObservation::rejected)
                .count();
        int postTraversable = postCounts.values().stream()
                .mapToInt(PostCounts::traversable)
                .sum();
        int postProtected = postCounts.values().stream()
                .mapToInt(PostCounts::protectedTraversable)
                .sum();
        int postBoundary = postCounts.values().stream()
                .mapToInt(PostCounts::boundaryTraversable)
                .sum();
        String packagePlanDigest = chunkDigest("package-plan", observations, true);
        String arbitrationInputDigest = chunkDigest("arbitration-input", observations, false);
        String arbitrationPlanDigest = chunkDigest("arbitration-plan", observations, false);
        writeRegister(samples);
        writeRepairRegister();
        String registerSha256;
        String repairSha256;
        try {
            registerSha256 = sha256(registerPath);
            repairSha256 = sha256(repairPath);
        } catch (IOException exception) {
            throw new IllegalStateException("output register digest failed", exception);
        }
        double realizableFraction = counts.planned == 0
                ? 0.0
                : (double) (counts.carve + counts.noop) / counts.planned;
        double postRealizedFraction = counts.planned == 0
                ? 0.0
                : (double) postTraversable / counts.planned;
        int primaryModLoadedCount = FabricLoader.getInstance().isModLoaded(primaryMod) ? 1 : 0;
        boolean commonPass = errors.isEmpty()
                && observedCarveChunks.size() == EXPECTED_CHUNKS
                && observedFeatureChunks.size() == EXPECTED_CHUNKS
                && observedAuditFeatureChunks.size() == EXPECTED_AUDIT_CHUNKS
                && observations.size() == EXPECTED_CHUNKS
                && repairComplete
                && ticketsReleased
                && counts.planned == EXPECTED_PLANNED
                && counts.carve == EXPECTED_CARVE
                && counts.noop == EXPECTED_NOOP
                && counts.deferFluid == 0
                && counts.skip == 0
                && counts.reject == 0
                && counts.structureReserved == 0
                && counts.blockEntity == 0
                && counts.protectedRoute == EXPECTED_PROTECTED
                && counts.boundary == EXPECTED_BOUNDARY
                && counts.protectedAdmissible == EXPECTED_PROTECTED
                && counts.boundaryAdmissible == EXPECTED_BOUNDARY
                && rejectedChunks == 0
                && realizableFraction >= MINIMUM_REALIZABLE_FRACTION;
        boolean pass = commonPass
                && frozenTargets.size() == EXPECTED_PLANNED
                && directWriteCount == counts.carve
                && repairWriteCount == repairs.size()
                && repairWriteCount <= EXPECTED_PROTECTED + EXPECTED_BOUNDARY
                && postProtected == EXPECTED_PROTECTED
                && postBoundary == EXPECTED_BOUNDARY
                && postRealizedFraction >= MINIMUM_REALIZABLE_FRACTION
                && primaryModLoadedCount == 1;
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-219-provider-coexistence-write-receipt.v1", true);
        field(output, "state", pass
                ? "PASS_PROVIDER_COEXISTENCE_WRITE_ARM"
                : "FAIL_PROVIDER_COEXISTENCE_WRITE_ARM", true);
        number(output, "run", RUN, true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        field(output, "selected_candidate_id", "run215-saved-winner", true);
        number(output, "primary_mod_loaded_count", primaryModLoadedCount, true);
        number(output, "target_chunk_count", targetChunks.size(), true);
        number(output, "audit_chunk_count", auditChunks.size(), true);
        number(output, "observed_carve_chunk_count", observedCarveChunks.size(), true);
        number(output, "observed_feature_chunk_count", observedFeatureChunks.size(), true);
        number(output, "observed_audit_feature_chunk_count", observedAuditFeatureChunks.size(), true);
        number(output, "planned_voxel_count", counts.planned, true);
        number(output, "replaceable_solid_count", counts.replaceable, true);
        number(output, "existing_void_count", counts.existingVoid, true);
        number(output, "fluid_count", counts.fluid, true);
        number(output, "preserve_count", counts.preserve, true);
        number(output, "structure_reserved_count", counts.structureReserved, true);
        number(output, "block_entity_target_count", counts.blockEntity, true);
        number(output, "protected_route_count", counts.protectedRoute, true);
        number(output, "registered_boundary_count", counts.boundary, true);
        number(output, "carve_action_count", counts.carve, true);
        number(output, "existing_void_noop_count", counts.noop, true);
        number(output, "deferred_fluid_count", counts.deferFluid, true);
        number(output, "skipped_host_geometry_count", counts.skip, true);
        number(output, "rejected_action_count", counts.reject, true);
        number(output, "protected_admissible_count", counts.protectedAdmissible, true);
        number(output, "boundary_admissible_count", counts.boundaryAdmissible, true);
        decimal(output, "preflight_realizable_void_fraction", realizableFraction, true);
        number(output, "rejected_chunk_count", rejectedChunks, true);
        field(output, "package_plan_sha256", packagePlanDigest, true);
        field(output, "arbitration_input_sha256", arbitrationInputDigest, true);
        field(output, "arbitration_plan_sha256", arbitrationPlanDigest, true);
        field(output, "target_register_sha256", registerSha256, true);
        field(output, "repair_register_sha256", repairSha256, true);
        field(output, "frozen_binding_sha256", frozenBindingSha256, true);
        number(output, "direct_write_count", directWriteCount, true);
        number(output, "repair_write_count", repairWriteCount, true);
        number(output, "total_write_count", directWriteCount + repairWriteCount, true);
        number(output, "outside_target_write_count", 0, true);
        number(output, "post_closure_traversable_count", postTraversable, true);
        number(output, "post_closure_protected_traversable_count", postProtected, true);
        number(output, "post_closure_boundary_traversable_count", postBoundary, true);
        decimal(output, "post_closure_realized_void_fraction", postRealizedFraction, true);
        number(output, "biome_write_count", 0, true);
        number(output, "structure_write_count", 0, true);
        number(output, "feature_registry_write_count", 0, true);
        bool(output, "persistent_forced_chunk_state_used", false, true);
        bool(output, "temporary_chunk_tickets_released_before_stop", ticketsReleased, true);
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

    private static void writeRegister(List<Sample> samples) {
        StringBuilder output = new StringBuilder();
        for (Sample sample : samples) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            output.append(voxel.x()).append('\t')
                    .append(voxel.y()).append('\t')
                    .append(voxel.z()).append('\t')
                    .append(sample.planned().protectedRoute()).append('\t')
                    .append(sample.planned().registeredBoundary()).append('\t')
                    .append(sample.action()).append('\t')
                    .append(sample.priorBlockState()).append('\n');
        }
        try {
            Files.createDirectories(registerPath.getParent());
            Path temporary = registerPath.resolveSibling(registerPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    registerPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("target register write failed", exception);
        }
    }

    private static void writeRepairRegister() {
        StringBuilder output = new StringBuilder();
        output.append("x\ty\tz\tprotected_route\tregistered_boundary\tprior_block_state\n");
        for (Repair repair : repairs) {
            KarstDeltaChunkReader.Voxel voxel = repair.voxel();
            output.append(voxel.x()).append('\t')
                    .append(voxel.y()).append('\t')
                    .append(voxel.z()).append('\t')
                    .append(repair.protectedRoute()).append('\t')
                    .append(repair.boundary()).append('\t')
                    .append(repair.priorBlockState()).append('\n');
        }
        try {
            Files.createDirectories(repairPath.getParent());
            Path temporary = repairPath.resolveSibling(repairPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    repairPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("repair register write failed", exception);
        }
    }

    private static String chunkDigest(
            String label,
            TreeMap<String, ChunkObservation> values,
            boolean packageDigest) {
        MessageDigest digest = digest();
        update(digest, label);
        for (Map.Entry<String, ChunkObservation> entry : values.entrySet()) {
            update(digest, entry.getKey());
            ChunkObservation observation = entry.getValue();
            update(digest, packageDigest
                    ? observation.packagePlanSha256()
                    : "arbitration-input".equals(label)
                            ? observation.arbitrationInputSha256()
                            : observation.arbitrationPlanSha256());
        }
        return hex(digest.digest());
    }

    private static String inputDigest(List<Sample> samples) {
        MessageDigest digest = digest();
        for (Sample sample : samples) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            update(digest, voxelKey(voxel));
            update(digest, sample.hostState().name());
            update(digest, Boolean.toString(sample.structureUnionReserved()));
            update(digest, Boolean.toString(sample.structurePieceReserved()));
            update(digest, Boolean.toString(sample.blockEntity()));
            update(digest, sample.biomeIdentity());
            update(digest, sample.priorBlockState());
        }
        return hex(digest.digest());
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

    private static String chunkKey(KarstDeltaChunkReader.ChunkKey key) {
        return key.x() + "," + key.z();
    }

    private static String chunkKey(ChunkPos key) {
        return key.x + "," + key.z;
    }

    private static String voxelKey(KarstDeltaChunkReader.Voxel voxel) {
        return voxel.x() + "," + voxel.y() + "," + voxel.z();
    }

    private static String errorSummary(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null ? "" : ":" + message.replace('\n', ' '));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        digest.update(Files.readAllBytes(path));
        return hex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
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
                .append(String.format(Locale.ROOT, "%.12f", value));
        if (comma) output.append(',');
    }

    private static void bool(StringBuilder output, String key, boolean value, boolean comma) {
        output.append('"').append(key).append("\":").append(value);
        if (comma) output.append(',');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
