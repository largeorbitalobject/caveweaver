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
import net.minecraft.block.FallingBlock;
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

/** Parameter-bound post-feature protected closure for a Karstogenesis package. */
public final class ProtectedClosureState {
    private static final int RUN = integerProperty("geoworld.closure208.run", 208);
    private static final String SITE_ID =
            stringProperty("geoworld.closure208.siteId", "c02");
    private static final int TRANSLATION_X =
            integerProperty("geoworld.closure208.translationX", 608);
    private static final int TRANSLATION_Y =
            integerProperty("geoworld.closure208.translationY", 91);
    private static final int TRANSLATION_Z =
            integerProperty("geoworld.closure208.translationZ", -128);
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;
    private static final int EXPECTED_CHUNKS =
            integerProperty("geoworld.closure208.expectedChunks", 59);
    private static final int EXPECTED_PLANNED =
            integerProperty("geoworld.closure208.expectedPlanned", 23328);
    private static final int EXPECTED_CARVE =
            integerProperty("geoworld.closure208.expectedCarve", 21936);
    private static final int EXPECTED_NOOP =
            integerProperty("geoworld.closure208.expectedNoop", 1392);
    private static final int EXPECTED_PROTECTED =
            integerProperty("geoworld.closure208.expectedProtected", 5395);
    private static final int EXPECTED_BOUNDARY =
            integerProperty("geoworld.closure208.expectedBoundary", 42);
    private static final double MINIMUM_REALIZABLE_FRACTION =
            doubleProperty("geoworld.closure208.minimumRealizableFraction", 0.999);
    private static final String POST_FEATURE_ACCEPTANCE_MODE =
            stringProperty("geoworld.closure208.postFeatureAcceptanceMode", "GLOBAL_VOID_FRACTION");
    private static final double MINIMUM_POST_CLOSURE_SANITY_FRACTION =
            doubleProperty("geoworld.closure208.minimumPostClosureSanityFraction", 0.90);
    private static final String STABILITY_MODE =
            stringProperty("geoworld.closure208.stabilityMode", "DISABLED");
    private static final int EXPECTED_STABILITY_MEMBRANE =
            integerProperty("geoworld.closure208.expectedStabilityMembrane", 0);
    private static final int LIFECYCLE_QUIESCENCE_TICKS =
            integerProperty("geoworld.closure208.lifecycleQuiescenceTicks", 0);
    private static final String POST_FEATURE_FLUID_POLICY =
            stringProperty("geoworld.closure208.postFeatureFluidPolicy", "DISABLED");
    private static final String POST_FEATURE_GRAVITY_POLICY =
            stringProperty("geoworld.closure208.postFeatureGravityPolicy", "DISABLED");
    private static final int AUDIT_LOW_CHUNK_X =
            integerProperty("geoworld.closure208.auditLowChunkX", 14);
    private static final int AUDIT_HIGH_CHUNK_X =
            integerProperty("geoworld.closure208.auditHighChunkX", 23);
    private static final int AUDIT_LOW_CHUNK_Z =
            integerProperty("geoworld.closure208.auditLowChunkZ", -17);
    private static final int AUDIT_HIGH_CHUNK_Z =
            integerProperty("geoworld.closure208.auditHighChunkZ", -8);
    private static final int EXPECTED_AUDIT_CHUNKS =
            integerProperty("geoworld.closure208.expectedAuditChunks", 100);

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureUnionReserved,
            boolean structurePieceReserved,
            boolean blockEntity,
            String biomeIdentity,
            String priorBlockState,
            BlockState priorState,
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

    private record StabilityTarget(String mechanism, String priorBlockState) {}

    private record LiveFluidIngress(
            KarstDeltaChunkReader.Voxel target,
            boolean protectedRoute,
            boolean boundary,
            KarstDeltaChunkReader.Voxel source,
            String sourceState,
            String priorBlockState) {}

    private record LiveGravityOrigin(
            KarstDeltaChunkReader.Voxel support,
            boolean protectedRoute,
            boolean boundary,
            KarstDeltaChunkReader.Voxel origin,
            String originState,
            String priorBlockState,
            boolean queued) {}

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
    private static Path fluidRegisterPath;
    private static Path gravityRegisterPath;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static List<KarstDeltaChunkReader.ChunkKey> targetChunks = List.of();
    private static List<KarstDeltaChunkReader.ChunkKey> auditChunks = List.of();
    private static Set<String> targetChunkKeys = Set.of();
    private static Set<String> auditChunkKeys = Set.of();
    private static Map<String, FrozenTarget> frozenTargets = Map.of();
    private static String frozenBindingSha256 = "";
    private static Map<String, StabilityTarget> stabilityTargets = Map.of();
    private static String stabilityBindingSha256 = "";
    private static final Set<String> observedCarveChunks = new HashSet<>();
    private static final Set<String> observedFeatureChunks = new HashSet<>();
    private static final Set<String> observedAuditFeatureChunks = new HashSet<>();
    private static final TreeMap<String, ChunkObservation> observations = new TreeMap<>();
    private static final TreeMap<String, PostCounts> postCounts = new TreeMap<>();
    private static final List<Repair> repairs = new ArrayList<>();
    private static final List<LiveFluidIngress> liveFluidIngresses = new ArrayList<>();
    private static final Map<String, Sample> liveFluidMembraneTargets = new TreeMap<>();
    private static final List<LiveGravityOrigin> liveGravityOrigins = new ArrayList<>();
    private static final Map<String, Sample> liveGravityMembraneTargets = new TreeMap<>();
    private static final List<String> errors = new ArrayList<>();
    private static boolean initialized;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketsReleased;
    private static boolean repairStarted;
    private static boolean repairComplete;
    private static boolean quiescenceComplete;
    private static int serverTickCount;
    private static int repairCompletedTick = -1;
    private static int directWriteCount;
    private static int repairWriteCount;
    private static int stabilityPreserveCount;
    private static int stabilityRestoreWriteCount;
    private static int stabilityExactCount;
    private static boolean postFeatureFluidRollback;
    private static boolean postFeatureGravityRollback;
    private static boolean postFeatureTopologyRollback;
    private static int liveFluidMembraneWriteCount;
    private static int liveFluidMembraneExactCount;
    private static int liveGravityMembraneWriteCount;
    private static int liveGravityMembraneExactCount;
    private static int rollbackTargetCount;
    private static int rollbackWriteCount;
    private static int rollbackExactCount;

    private ProtectedClosureState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        require(
                POST_FEATURE_ACCEPTANCE_MODE.equals("GLOBAL_VOID_FRACTION")
                        || POST_FEATURE_ACCEPTANCE_MODE.equals(
                                "PROTECTED_TOPOLOGY_WITH_OFFLINE_MORPHOLOGY_AUDIT"),
                "post-feature acceptance mode differs");
        require(
                MINIMUM_POST_CLOSURE_SANITY_FRACTION >= 0.0
                        && MINIMUM_POST_CLOSURE_SANITY_FRACTION <= 1.0,
                "post-closure sanity fraction differs");
        require(
                STABILITY_MODE.equals("DISABLED")
                        || STABILITY_MODE.equals("TARGET_OWNED_STABILITY_MEMBRANE"),
                "stability mode differs");
        require(EXPECTED_STABILITY_MEMBRANE >= 0, "stability membrane count differs");
        require(LIFECYCLE_QUIESCENCE_TICKS >= 0, "lifecycle quiescence ticks differ");
        require(
                POST_FEATURE_FLUID_POLICY.equals("DISABLED")
                        || POST_FEATURE_FLUID_POLICY.equals("LIVE_TARGET_OWNED_ROLLBACK"),
                "post-feature fluid policy differs");
        require(
                POST_FEATURE_GRAVITY_POLICY.equals("DISABLED")
                        || POST_FEATURE_GRAVITY_POLICY.equals("QUEUED_TARGET_OWNED_ROLLBACK"),
                "post-feature gravity policy differs");
        if (STABILITY_MODE.equals("TARGET_OWNED_STABILITY_MEMBRANE")) {
            require(EXPECTED_STABILITY_MEMBRANE > 0, "stability membrane is empty");
            require(
                    LIFECYCLE_QUIESCENCE_TICKS >= 200,
                    "stability lifecycle quiescence is shorter than 200 ticks");
        } else {
            require(EXPECTED_STABILITY_MEMBRANE == 0, "disabled stability has targets");
            if (POST_FEATURE_FLUID_POLICY.equals("DISABLED")
                    && POST_FEATURE_GRAVITY_POLICY.equals("DISABLED")) {
                require(LIFECYCLE_QUIESCENCE_TICKS == 0, "disabled lifecycle policies have quiescence");
            } else {
                require(
                        LIFECYCLE_QUIESCENCE_TICKS >= 200,
                        "live fluid lifecycle quiescence is shorter than 200 ticks");
            }
        }
        String packageProperty = System.getProperty("geoworld.closure208.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.closure208.receipt", "").trim();
        String registerProperty = System.getProperty("geoworld.closure208.register", "").trim();
        String repairProperty = System.getProperty("geoworld.closure208.repair", "").trim();
        String fluidRegisterProperty =
                System.getProperty("geoworld.closure208.fluidRegister", "").trim();
        String gravityRegisterProperty =
                System.getProperty("geoworld.closure208.gravityRegister", "").trim();
        if (packageProperty.isEmpty()
                || receiptProperty.isEmpty()
                || registerProperty.isEmpty()
                || repairProperty.isEmpty()
                || fluidRegisterProperty.isEmpty()
                || gravityRegisterProperty.isEmpty()) {
            throw new IllegalStateException("Run 208 package or output property is empty");
        }
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        registerPath = Path.of(registerProperty).toAbsolutePath().normalize();
        repairPath = Path.of(repairProperty).toAbsolutePath().normalize();
        fluidRegisterPath = Path.of(fluidRegisterProperty).toAbsolutePath().normalize();
        gravityRegisterPath = Path.of(gravityRegisterProperty).toAbsolutePath().normalize();
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
        loadStabilityBinding();
        initialized = true;
        KarstogenesisFabricProtectedClosure.LOGGER.info(
                "Run 208 initialized target-chunks={} audit-chunks={} frozen-targets={} stability-targets={}",
                targetChunks.size(),
                auditChunks.size(),
                frozenTargets.size(),
                stabilityTargets.size());
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
        String bindingProperty = System.getProperty("geoworld.closure208.binding", "").trim();
        String hashProperty =
                System.getProperty("geoworld.closure208.bindingSha256", "").trim();
        if (bindingProperty.isEmpty() || hashProperty.length() != 64) {
            throw new IllegalStateException("Run 208 requires a frozen binding and digest");
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

    private static void loadStabilityBinding() {
        if (STABILITY_MODE.equals("DISABLED")) {
            stabilityTargets = Map.of();
            stabilityBindingSha256 = "";
            return;
        }
        String bindingProperty =
                System.getProperty("geoworld.closure208.stabilityBinding", "").trim();
        String hashProperty =
                System.getProperty("geoworld.closure208.stabilityBindingSha256", "").trim();
        require(!bindingProperty.isEmpty(), "stability binding is empty");
        require(hashProperty.length() == 64, "stability binding digest width differs");
        Path path = Path.of(bindingProperty).toAbsolutePath().normalize();
        try {
            stabilityBindingSha256 = sha256(path);
            require(stabilityBindingSha256.equals(hashProperty), "stability binding digest differs");
            Map<String, StabilityTarget> values = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 5);
                require(fields.length == 5, "stability binding row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                FrozenTarget frozen = frozenTargets.get(key);
                require(frozen != null, "stability target is outside frozen target register");
                require(!frozen.protectedRoute(), "stability target intersects protected route");
                require(!frozen.boundary(), "stability target intersects registered boundary");
                require(frozen.action().equals("CARVE"), "stability target is not a carve action");
                require(
                        frozen.priorBlockState().equals(fields[4]),
                        "stability prior block differs from frozen target");
                StabilityTarget previous =
                        values.put(key, new StabilityTarget(fields[3], fields[4]));
                require(previous == null, "stability binding repeats a target");
            }
            require(
                    values.size() == EXPECTED_STABILITY_MEMBRANE,
                    "stability membrane count differs");
            stabilityTargets = Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("stability binding read failed", exception);
        }
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "Run 208 closure is not initialized");
        for (KarstDeltaChunkReader.ChunkKey key : auditChunks) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), true);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        boolean shouldRepair;
        synchronized (ProtectedClosureState.class) {
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
                synchronized (ProtectedClosureState.class) {
                    errors.add("repair:" + errorSummary(exception));
                }
                KarstogenesisFabricProtectedClosure.LOGGER.error(
                        "Run 208 protected closure failed", exception);
            }
            synchronized (ProtectedClosureState.class) {
                repairComplete = true;
                repairCompletedTick = serverTickCount;
                if (LIFECYCLE_QUIESCENCE_TICKS == 0) quiescenceComplete = true;
            }
        }
        boolean shouldFinishQuiescence;
        synchronized (ProtectedClosureState.class) {
            shouldFinishQuiescence = repairComplete
                    && !quiescenceComplete
                    && serverTickCount - repairCompletedTick >= LIFECYCLE_QUIESCENCE_TICKS;
        }
        if (shouldFinishQuiescence) {
            try {
                auditAfterQuiescence(server);
            } catch (RuntimeException exception) {
                synchronized (ProtectedClosureState.class) {
                    errors.add("quiescence-audit:" + errorSummary(exception));
                }
                KarstogenesisFabricProtectedClosure.LOGGER.error(
                        "Run 208 lifecycle quiescence audit failed", exception);
            }
            synchronized (ProtectedClosureState.class) {
                quiescenceComplete = true;
            }
        }
        boolean shouldReleaseTickets;
        synchronized (ProtectedClosureState.class) {
            shouldReleaseTickets = repairComplete && quiescenceComplete && !ticketsReleased;
        }
        if (shouldReleaseTickets) {
            try {
                releaseTickets(server);
            } catch (RuntimeException exception) {
                synchronized (ProtectedClosureState.class) {
                    errors.add("ticket-release:" + errorSummary(exception));
                }
            }
        }
        boolean shouldStop = false;
        synchronized (ProtectedClosureState.class) {
            attemptReceipt();
            if (receiptWritten && !stopRequested) {
                stopRequested = true;
                shouldStop = true;
            } else if (!receiptWritten && serverTickCount % 200 == 0) {
                KarstogenesisFabricProtectedClosure.LOGGER.info(
                        "Run 208 gate carve={}/{} target-features={}/{} audit-features={}/{} direct={} repair={} errors={}",
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
            KarstogenesisFabricProtectedClosure.LOGGER.info(
                    "Run 208 receipt written, stopping disposable server");
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
            KarstogenesisFabricProtectedClosure.LOGGER.error(
                    "Run 208 carve-tail observation failed for {}", key, exception);
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
        require(!repairComplete, "protected closure already completed");
        require(observations.size() == EXPECTED_CHUNKS, "protected closure observations differ");
        repairs.clear();
        auditLiveFluidInterface(server);
        auditLiveGravityInterface(server);
        if (placementRollback()) {
            applyTargetRollback(server);
            repairs.sort(Comparator.comparing(value -> voxelKey(value.voxel())));
            observePostCounts(server);
            writeRepairRegister();
            writeFluidRegister();
            writeGravityRegister();
            KarstogenesisFabricProtectedClosure.LOGGER.info(
                    "Run 208 post-feature lifecycle rollback completed fluid-ingress={} gravity-activations={} rollback-writes={}",
                    liveFluidIngresses.size(),
                    queuedGravityActivationCount(),
                    rollbackWriteCount);
            return;
        }
        applyLiveFluidMembrane(server);
        applyLiveGravityMembrane(server);
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
        for (ChunkObservation observation : observations.values()) {
            Chunk chunk = server.getOverworld().getChunk(
                    observation.key().x(), observation.key().z());
            for (Sample sample : observation.samples()) {
                String key = voxelKey(sample.planned().worldVoxel());
                if (!stabilityTargets.containsKey(key)) continue;
                BlockPos position = blockPos(sample.planned().worldVoxel());
                BlockState state = chunk.getBlockState(position);
                if (state.equals(sample.priorState())) continue;
                chunk.setBlockState(position, sample.priorState(), false);
                stabilityRestoreWriteCount++;
            }
        }
        repairs.sort(Comparator.comparing(value -> voxelKey(value.voxel())));
        observePostCounts(server);
        writeRepairRegister();
        writeFluidRegister();
        writeGravityRegister();
        KarstogenesisFabricProtectedClosure.LOGGER.info(
                "Run 208 protected closure completed repairs={} stability-preserves={} stability-restores={} live-fluid-membranes={} live-gravity-membranes={}",
                repairWriteCount,
                stabilityPreserveCount,
                stabilityRestoreWriteCount,
                liveFluidMembraneWriteCount,
                liveGravityMembraneWriteCount);
    }

    private static void auditLiveFluidInterface(MinecraftServer server) {
        liveFluidIngresses.clear();
        liveFluidMembraneTargets.clear();
        postFeatureFluidRollback = false;
        if (POST_FEATURE_FLUID_POLICY.equals("DISABLED")) return;
        int[][] offsets = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0},
            {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        for (ChunkObservation observation : observations.values()) {
            for (Sample sample : observation.samples()) {
                if (!"CARVE".equals(sample.action())) continue;
                KarstDeltaChunkReader.Voxel target = sample.planned().worldVoxel();
                for (int[] offset : offsets) {
                    KarstDeltaChunkReader.Voxel source = new KarstDeltaChunkReader.Voxel(
                            target.x() + offset[0],
                            target.y() + offset[1],
                            target.z() + offset[2]);
                    if (frozenTargets.containsKey(voxelKey(source))) continue;
                    BlockState sourceState = server.getOverworld().getBlockState(blockPos(source));
                    if (sourceState.getFluidState().isEmpty()) continue;
                    liveFluidIngresses.add(new LiveFluidIngress(
                            target,
                            sample.planned().protectedRoute(),
                            sample.planned().registeredBoundary(),
                            source,
                            sourceState.toString(),
                            sample.priorBlockState()));
                    if (sample.planned().protectedRoute()
                            || sample.planned().registeredBoundary()) {
                        postFeatureFluidRollback = true;
                    } else {
                        liveFluidMembraneTargets.put(voxelKey(target), sample);
                    }
                }
            }
        }
        liveFluidIngresses.sort(Comparator
                .comparing((LiveFluidIngress value) -> voxelKey(value.target()))
                .thenComparing(value -> voxelKey(value.source())));
    }

    private static void auditLiveGravityInterface(MinecraftServer server) {
        liveGravityOrigins.clear();
        liveGravityMembraneTargets.clear();
        postFeatureGravityRollback = false;
        if (POST_FEATURE_GRAVITY_POLICY.equals("DISABLED")) return;
        TreeMap<String, LiveGravityOrigin> byOrigin = new TreeMap<>();
        TreeMap<String, Sample> samplesByVoxel = new TreeMap<>();
        for (ChunkObservation observation : observations.values()) {
            for (Sample sample : observation.samples()) {
                samplesByVoxel.put(voxelKey(sample.planned().worldVoxel()), sample);
            }
        }
        for (Sample sample : samplesByVoxel.values()) {
            if (!"CARVE".equals(sample.action())) continue;
            KarstDeltaChunkReader.Voxel support = sample.planned().worldVoxel();
            for (int y = support.y() + 1; y <= HIGH_Y; y++) {
                KarstDeltaChunkReader.Voxel origin =
                        new KarstDeltaChunkReader.Voxel(support.x(), y, support.z());
                BlockPos originPosition = blockPos(origin);
                BlockState state = server.getOverworld().getBlockState(originPosition);
                if (frozenTargets.containsKey(voxelKey(origin)) || state.isAir()) continue;
                if (state.getBlock() instanceof FallingBlock) {
                    boolean queued = server.getOverworld()
                            .getBlockTickScheduler()
                            .isQueued(originPosition, state.getBlock());
                    LiveGravityOrigin candidate = new LiveGravityOrigin(
                            support,
                            sample.planned().protectedRoute(),
                            sample.planned().registeredBoundary(),
                            origin,
                            state.toString(),
                            sample.priorBlockState(),
                            queued);
                    String originKey = voxelKey(origin);
                    LiveGravityOrigin previous = byOrigin.get(originKey);
                    if (previous == null || candidate.support().y() > previous.support().y()) {
                        byOrigin.put(originKey, candidate);
                    }
                }
                break;
            }
        }
        liveGravityOrigins.addAll(byOrigin.values());
        for (LiveGravityOrigin origin : liveGravityOrigins) {
            if (!origin.queued()) continue;
            if (origin.protectedRoute() || origin.boundary()) {
                postFeatureGravityRollback = true;
            } else {
                Sample support = samplesByVoxel.get(voxelKey(origin.support()));
                require(support != null, "gravity support sample is unavailable");
                liveGravityMembraneTargets.put(voxelKey(origin.support()), support);
            }
        }
    }

    private static void applyLiveFluidMembrane(MinecraftServer server) {
        for (Sample sample : liveFluidMembraneTargets.values()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            Chunk chunk = server.getOverworld().getChunk(voxel.x() >> 4, voxel.z() >> 4);
            BlockPos position = blockPos(voxel);
            if (chunk.getBlockState(position).equals(sample.priorState())) continue;
            chunk.setBlockState(position, sample.priorState(), false);
            liveFluidMembraneWriteCount++;
        }
    }

    private static void applyLiveGravityMembrane(MinecraftServer server) {
        for (Sample sample : liveGravityMembraneTargets.values()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            Chunk chunk = server.getOverworld().getChunk(voxel.x() >> 4, voxel.z() >> 4);
            BlockPos position = blockPos(voxel);
            if (chunk.getBlockState(position).equals(sample.priorState())) continue;
            chunk.setBlockState(position, sample.priorState(), false);
            liveGravityMembraneWriteCount++;
        }
    }

    private static void applyTargetRollback(MinecraftServer server) {
        rollbackTargetCount = 0;
        for (ChunkObservation observation : observations.values()) {
            Chunk chunk = server.getOverworld().getChunk(
                    observation.key().x(), observation.key().z());
            for (Sample sample : observation.samples()) {
                if (!"CARVE".equals(sample.action())) continue;
                rollbackTargetCount++;
                BlockPos position = blockPos(sample.planned().worldVoxel());
                if (chunk.getBlockState(position).equals(sample.priorState())) continue;
                chunk.setBlockState(position, sample.priorState(), false);
                rollbackWriteCount++;
            }
        }
        liveFluidMembraneTargets.clear();
        liveGravityMembraneTargets.clear();
    }

    private static void observePostCounts(MinecraftServer server) {
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
    }

    private static void auditAfterQuiescence(MinecraftServer server) {
        require(repairComplete, "quiescence audit began before protected closure");
        observePostCounts(server);
        int protectedTraversable = postCounts.values().stream()
                .mapToInt(PostCounts::protectedTraversable)
                .sum();
        int boundaryTraversable = postCounts.values().stream()
                .mapToInt(PostCounts::boundaryTraversable)
                .sum();
        if (!placementRollback()
                && (protectedTraversable != EXPECTED_PROTECTED
                        || boundaryTraversable != EXPECTED_BOUNDARY)) {
            postFeatureTopologyRollback = true;
            applyTargetRollback(server);
            observePostCounts(server);
            writeFluidRegister();
            writeGravityRegister();
            KarstogenesisFabricProtectedClosure.LOGGER.warn(
                    "Run 208 quiescence topology guard forced rollback protected={}/{} boundary={}/{} rollback-writes={}",
                    protectedTraversable,
                    EXPECTED_PROTECTED,
                    boundaryTraversable,
                    EXPECTED_BOUNDARY,
                    rollbackWriteCount);
        }
        int exact = 0;
        for (Map.Entry<String, StabilityTarget> entry : stabilityTargets.entrySet()) {
            String[] coordinates = entry.getKey().split(",", 3);
            BlockPos position = new BlockPos(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
            Chunk chunk = server.getOverworld().getChunk(position.getX() >> 4, position.getZ() >> 4);
            if (chunk.getBlockState(position).toString().equals(entry.getValue().priorBlockState())) {
                exact++;
            }
        }
        stabilityExactCount = exact;
        int fluidExact = 0;
        for (Sample sample : liveFluidMembraneTargets.values()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            if (server.getOverworld().getBlockState(blockPos(voxel)).equals(sample.priorState())) {
                fluidExact++;
            }
        }
        liveFluidMembraneExactCount = fluidExact;
        int gravityExact = 0;
        for (Sample sample : liveGravityMembraneTargets.values()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            if (server.getOverworld().getBlockState(blockPos(voxel)).equals(sample.priorState())) {
                gravityExact++;
            }
        }
        liveGravityMembraneExactCount = gravityExact;
        int rollbackExact = 0;
        if (placementRollback()) {
            for (ChunkObservation observation : observations.values()) {
                for (Sample sample : observation.samples()) {
                    if (!"CARVE".equals(sample.action())) continue;
                    if (server.getOverworld()
                            .getBlockState(blockPos(sample.planned().worldVoxel()))
                            .equals(sample.priorState())) {
                        rollbackExact++;
                    }
                }
            }
        }
        rollbackExactCount = rollbackExact;
        KarstogenesisFabricProtectedClosure.LOGGER.info(
                "Run 208 lifecycle quiescence completed ticks={} exact-stability-targets={}/{} exact-live-fluid-targets={}/{} exact-live-gravity-targets={}/{} rollback-exact={}/{}",
                LIFECYCLE_QUIESCENCE_TICKS,
                stabilityExactCount,
                stabilityTargets.size(),
                liveFluidMembraneExactCount,
                liveFluidMembraneTargets.size(),
                liveGravityMembraneExactCount,
                liveGravityMembraneTargets.size(),
                rollbackExactCount,
                rollbackTargetCount);
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
                    state,
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
                sample.priorState(),
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
            String key = voxelKey(sample.planned().worldVoxel());
            StabilityTarget stability = stabilityTargets.get(key);
            if (stability != null) {
                require(
                        stability.priorBlockState().equals(sample.priorBlockState()),
                        "live stability prior block differs");
                stabilityPreserveCount++;
                continue;
            }
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
        if (state.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
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
                || !repairComplete
                || !quiescenceComplete
                || !ticketsReleased) {
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
        writeFluidRegister();
        writeGravityRegister();
        String registerSha256;
        String repairSha256;
        String fluidRegisterSha256;
        String gravityRegisterSha256;
        try {
            registerSha256 = sha256(registerPath);
            repairSha256 = sha256(repairPath);
            fluidRegisterSha256 = sha256(fluidRegisterPath);
            gravityRegisterSha256 = sha256(gravityRegisterPath);
        } catch (IOException exception) {
            throw new IllegalStateException("output register digest failed", exception);
        }
        double realizableFraction = counts.planned == 0
                ? 0.0
                : (double) (counts.carve + counts.noop) / counts.planned;
        double postRealizedFraction = counts.planned == 0
                ? 0.0
                : (double) postTraversable / counts.planned;
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
        boolean topologyGuardPass = postProtected == EXPECTED_PROTECTED
                && postBoundary == EXPECTED_BOUNDARY;
        Set<String> fluidIngressTargets = new HashSet<>();
        Set<String> protectedFluidIngressTargets = new HashSet<>();
        Set<String> boundaryFluidIngressTargets = new HashSet<>();
        for (LiveFluidIngress ingress : liveFluidIngresses) {
            String key = voxelKey(ingress.target());
            fluidIngressTargets.add(key);
            if (ingress.protectedRoute()) protectedFluidIngressTargets.add(key);
            if (ingress.boundary()) boundaryFluidIngressTargets.add(key);
        }
        Set<String> queuedGravityOrigins = new HashSet<>();
        Set<String> protectedQueuedGravityOrigins = new HashSet<>();
        Set<String> boundaryQueuedGravityOrigins = new HashSet<>();
        for (LiveGravityOrigin origin : liveGravityOrigins) {
            if (!origin.queued()) continue;
            String key = voxelKey(origin.origin());
            queuedGravityOrigins.add(key);
            if (origin.protectedRoute()) protectedQueuedGravityOrigins.add(key);
            if (origin.boundary()) boundaryQueuedGravityOrigins.add(key);
        }
        boolean liveFluidPolicyPass = POST_FEATURE_FLUID_POLICY.equals("DISABLED")
                ? liveFluidIngresses.isEmpty()
                : placementRollback()
                        ? rollbackTargetCount == counts.carve
                                && rollbackExactCount == counts.carve
                        : protectedFluidIngressTargets.isEmpty()
                                && boundaryFluidIngressTargets.isEmpty()
                                && liveFluidMembraneExactCount
                                        == liveFluidMembraneTargets.size();
        boolean liveGravityPolicyPass = POST_FEATURE_GRAVITY_POLICY.equals("DISABLED")
                ? liveGravityOrigins.isEmpty()
                : placementRollback()
                        ? rollbackTargetCount == counts.carve
                                && rollbackExactCount == counts.carve
                        : protectedQueuedGravityOrigins.isEmpty()
                                && boundaryQueuedGravityOrigins.isEmpty()
                                && liveGravityMembraneExactCount
                                        == liveGravityMembraneTargets.size();
        boolean postFeaturePolicyPass = placementRollback()
                ? liveFluidPolicyPass && liveGravityPolicyPass
                : POST_FEATURE_ACCEPTANCE_MODE.equals("GLOBAL_VOID_FRACTION")
                        ? postRealizedFraction >= MINIMUM_REALIZABLE_FRACTION
                                && liveFluidPolicyPass
                                && liveGravityPolicyPass
                        : topologyGuardPass
                                && postRealizedFraction >= MINIMUM_POST_CLOSURE_SANITY_FRACTION
                                && liveFluidPolicyPass
                                && liveGravityPolicyPass;
        boolean stabilityPolicyPass = stabilityTargets.size() == EXPECTED_STABILITY_MEMBRANE
                && stabilityPreserveCount == EXPECTED_STABILITY_MEMBRANE
                && stabilityExactCount == EXPECTED_STABILITY_MEMBRANE
                && quiescenceComplete
                && (STABILITY_MODE.equals("DISABLED")
                        || LIFECYCLE_QUIESCENCE_TICKS >= 200);
        boolean committedPass = !placementRollback()
                && topologyGuardPass
                && postFeaturePolicyPass;
        boolean rollbackPass = placementRollback()
                && (!postFeatureFluidRollback
                        || POST_FEATURE_FLUID_POLICY.equals("LIVE_TARGET_OWNED_ROLLBACK"))
                && (!postFeatureGravityRollback
                        || POST_FEATURE_GRAVITY_POLICY.equals("QUEUED_TARGET_OWNED_ROLLBACK"))
                && liveFluidPolicyPass
                && liveGravityPolicyPass;
        boolean pass = commonPass
                && frozenTargets.size() == EXPECTED_PLANNED
                && directWriteCount + stabilityPreserveCount == counts.carve
                && repairWriteCount == repairs.size()
                && repairWriteCount <= EXPECTED_PROTECTED + EXPECTED_BOUNDARY
                && (committedPass || rollbackPass)
                && stabilityPolicyPass;
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-" + RUN
                + "-post-feature-protected-route-closure-receipt.v1", true);
        field(output, "state", pass
                ? placementRollback()
                        ? postFeatureTopologyRollback
                                ? "PASS_POST_FEATURE_TOPOLOGY_ROLLBACK_REJECTED_PLACEMENT"
                                : postFeatureGravityRollback
                                ? "PASS_POST_FEATURE_LIFECYCLE_ROLLBACK_REJECTED_PLACEMENT"
                                : "PASS_POST_FEATURE_FLUID_ROLLBACK_REJECTED_PLACEMENT"
                        : "PASS_FABRIC_POST_FEATURE_PROTECTED_ROUTE_CLOSURE_ARM"
                : "FAIL_FABRIC_POST_FEATURE_PROTECTED_ROUTE_CLOSURE_ARM", true);
        number(output, "run", RUN, true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        field(output, "selected_candidate_id", SITE_ID, true);
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
        field(output, "post_feature_fluid_register_sha256", fluidRegisterSha256, true);
        field(output, "post_feature_gravity_register_sha256", gravityRegisterSha256, true);
        field(output, "frozen_binding_sha256", frozenBindingSha256, true);
        field(output, "stability_mode", STABILITY_MODE, true);
        field(output, "stability_binding_sha256", stabilityBindingSha256, true);
        number(output, "stability_membrane_target_count", stabilityTargets.size(), true);
        number(output, "stability_membrane_preserve_count", stabilityPreserveCount, true);
        number(output, "stability_membrane_post_feature_restore_write_count", stabilityRestoreWriteCount, true);
        number(output, "stability_membrane_exact_after_quiescence_count", stabilityExactCount, true);
        number(output, "lifecycle_quiescence_ticks", LIFECYCLE_QUIESCENCE_TICKS, true);
        bool(output, "lifecycle_quiescence_complete", quiescenceComplete, true);
        bool(output, "stability_policy_pass", stabilityPolicyPass, true);
        field(output, "post_feature_fluid_policy", POST_FEATURE_FLUID_POLICY, true);
        number(output, "post_feature_fluid_ingress_edge_count", liveFluidIngresses.size(), true);
        number(output, "post_feature_fluid_ingress_target_count", fluidIngressTargets.size(), true);
        number(
                output,
                "post_feature_protected_fluid_ingress_target_count",
                protectedFluidIngressTargets.size(),
                true);
        number(
                output,
                "post_feature_boundary_fluid_ingress_target_count",
                boundaryFluidIngressTargets.size(),
                true);
        number(
                output,
                "live_fluid_membrane_target_count",
                liveFluidMembraneTargets.size(),
                true);
        number(output, "live_fluid_membrane_write_count", liveFluidMembraneWriteCount, true);
        number(output, "live_fluid_membrane_exact_after_quiescence_count", liveFluidMembraneExactCount, true);
        bool(output, "post_feature_fluid_rollback", postFeatureFluidRollback, true);
        field(output, "post_feature_gravity_policy", POST_FEATURE_GRAVITY_POLICY, true);
        number(output, "post_feature_gravity_origin_count", liveGravityOrigins.size(), true);
        number(output, "post_feature_queued_gravity_activation_count", queuedGravityOrigins.size(), true);
        number(
                output,
                "post_feature_protected_queued_gravity_activation_count",
                protectedQueuedGravityOrigins.size(),
                true);
        number(
                output,
                "post_feature_boundary_queued_gravity_activation_count",
                boundaryQueuedGravityOrigins.size(),
                true);
        number(output, "live_gravity_membrane_target_count", liveGravityMembraneTargets.size(), true);
        number(output, "live_gravity_membrane_write_count", liveGravityMembraneWriteCount, true);
        number(
                output,
                "live_gravity_membrane_exact_after_quiescence_count",
                liveGravityMembraneExactCount,
                true);
        bool(output, "post_feature_gravity_rollback", postFeatureGravityRollback, true);
        bool(output, "post_feature_topology_rollback", postFeatureTopologyRollback, true);
        number(output, "rollback_target_count", rollbackTargetCount, true);
        number(output, "rollback_write_count", rollbackWriteCount, true);
        number(output, "rollback_exact_after_quiescence_count", rollbackExactCount, true);
        bool(output, "placement_rollback", placementRollback(), true);
        bool(output, "placement_committed", !placementRollback(), true);
        bool(output, "post_feature_fluid_policy_pass", liveFluidPolicyPass, true);
        bool(output, "post_feature_gravity_policy_pass", liveGravityPolicyPass, true);
        number(output, "direct_write_count", directWriteCount, true);
        number(output, "repair_write_count", repairWriteCount, true);
        number(
                output,
                "total_write_count",
                directWriteCount
                        + repairWriteCount
                        + stabilityRestoreWriteCount
                        + liveFluidMembraneWriteCount
                        + liveGravityMembraneWriteCount
                        + rollbackWriteCount,
                true);
        number(output, "outside_target_stability_write_count", 0, true);
        number(output, "outside_target_fluid_policy_write_count", 0, true);
        number(output, "outside_target_gravity_policy_write_count", 0, true);
        number(output, "outside_target_write_count", 0, true);
        number(output, "post_closure_traversable_count", postTraversable, true);
        number(output, "post_closure_protected_traversable_count", postProtected, true);
        number(output, "post_closure_boundary_traversable_count", postBoundary, true);
        decimal(output, "post_closure_realized_void_fraction", postRealizedFraction, true);
        field(output, "post_feature_acceptance_mode", POST_FEATURE_ACCEPTANCE_MODE, true);
        decimal(
                output,
                "minimum_post_closure_sanity_fraction",
                MINIMUM_POST_CLOSURE_SANITY_FRACTION,
                true);
        bool(output, "post_closure_topology_guard_pass", topologyGuardPass, true);
        bool(output, "post_feature_policy_pass", postFeaturePolicyPass, true);
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

    private static void writeFluidRegister() {
        StringBuilder output = new StringBuilder();
        output.append("target_x\ttarget_y\ttarget_z\tprotected_route\tregistered_boundary")
                .append("\tsource_x\tsource_y\tsource_z\tsource_state\tprior_block_state\tdecision\n");
        String decision = placementRollback()
                ? "PLACEMENT_ROLLBACK"
                : "TARGET_OWNED_FLUID_MEMBRANE";
        for (LiveFluidIngress ingress : liveFluidIngresses) {
            output.append(ingress.target().x()).append('\t')
                    .append(ingress.target().y()).append('\t')
                    .append(ingress.target().z()).append('\t')
                    .append(ingress.protectedRoute()).append('\t')
                    .append(ingress.boundary()).append('\t')
                    .append(ingress.source().x()).append('\t')
                    .append(ingress.source().y()).append('\t')
                    .append(ingress.source().z()).append('\t')
                    .append(ingress.sourceState()).append('\t')
                    .append(ingress.priorBlockState()).append('\t')
                    .append(decision).append('\n');
        }
        try {
            Files.createDirectories(fluidRegisterPath.getParent());
            Path temporary = fluidRegisterPath.resolveSibling(
                    fluidRegisterPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    fluidRegisterPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("fluid register write failed", exception);
        }
    }

    private static void writeGravityRegister() {
        StringBuilder output = new StringBuilder();
        output.append("support_x\tsupport_y\tsupport_z\tprotected_route\tregistered_boundary")
                .append("\torigin_x\torigin_y\torigin_z\torigin_state\tprior_block_state")
                .append("\tqueued\tdecision\n");
        for (LiveGravityOrigin origin : liveGravityOrigins) {
            String decision = !origin.queued()
                    ? "TELEMETRY_NO_ACTIVATION"
                    : placementRollback()
                            ? "PLACEMENT_ROLLBACK"
                            : "TARGET_OWNED_GRAVITY_MEMBRANE";
            output.append(origin.support().x()).append('\t')
                    .append(origin.support().y()).append('\t')
                    .append(origin.support().z()).append('\t')
                    .append(origin.protectedRoute()).append('\t')
                    .append(origin.boundary()).append('\t')
                    .append(origin.origin().x()).append('\t')
                    .append(origin.origin().y()).append('\t')
                    .append(origin.origin().z()).append('\t')
                    .append(origin.originState()).append('\t')
                    .append(origin.priorBlockState()).append('\t')
                    .append(origin.queued()).append('\t')
                    .append(decision).append('\n');
        }
        try {
            Files.createDirectories(gravityRegisterPath.getParent());
            Path temporary = gravityRegisterPath.resolveSibling(
                    gravityRegisterPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    gravityRegisterPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("gravity register write failed", exception);
        }
    }

    private static boolean placementRollback() {
        return postFeatureFluidRollback
                || postFeatureGravityRollback
                || postFeatureTopologyRollback;
    }

    private static int queuedGravityActivationCount() {
        int count = 0;
        for (LiveGravityOrigin origin : liveGravityOrigins) {
            if (origin.queued()) count++;
        }
        return count;
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

    private static String stringProperty(String name, String fallback) {
        String value = System.getProperty(name, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static int integerProperty(String name, int fallback) {
        return Integer.parseInt(stringProperty(name, Integer.toString(fallback)));
    }

    private static double doubleProperty(String name, double fallback) {
        return Double.parseDouble(stringProperty(name, Double.toString(fallback)));
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
