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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Parameter-bound paired preflight and write state for a Karstogenesis package. */
public final class MultichunkTransactionState {
    private static final int RUN = integerProperty("geoworld.multichunk.run", 205);
    private static final int TRANSLATION_X =
            integerProperty("geoworld.multichunk.translationX", 608);
    private static final int TRANSLATION_Y =
            integerProperty("geoworld.multichunk.translationY", 91);
    private static final int TRANSLATION_Z =
            integerProperty("geoworld.multichunk.translationZ", 128);
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;
    private static final int EXPECTED_CHUNKS =
            integerProperty("geoworld.multichunk.expectedChunks", 59);
    private static final int EXPECTED_PLANNED =
            integerProperty("geoworld.multichunk.expectedPlanned", 23328);
    private static final int EXPECTED_PROTECTED =
            integerProperty("geoworld.multichunk.expectedProtected", 5395);
    private static final int EXPECTED_BOUNDARY =
            integerProperty("geoworld.multichunk.expectedBoundary", 42);
    private static final double MINIMUM_REALIZABLE_FRACTION =
            doubleProperty("geoworld.multichunk.minimumRealizableFraction", 0.95);
    private static final int LIFECYCLE_QUIESCENCE_TICKS =
            integerProperty("geoworld.multichunk.lifecycleQuiescenceTicks", 200);
    private static final double MAXIMUM_ORDINARY_FLUID_TARGET_FRACTION =
            doubleProperty("geoworld.multichunk.maximumOrdinaryFluidTargetFraction", 0.001);
    private static final double MAXIMUM_DEFERRED_HOST_FLUID_TARGET_FRACTION =
            doubleProperty("geoworld.multichunk.maximumDeferredHostFluidTargetFraction", 0.003);
    private static final int EXPECTED_PREEMPTIVE_MEMBRANE =
            integerProperty("geoworld.multichunk.expectedPreemptiveMembrane", 0);
    private static final int EXPECTED_SOURCE_CAPS =
            integerProperty("geoworld.multichunk.expectedSourceCaps", 0);
    private static final int EXPECTED_NATURAL_GEOLOGY_OVERRIDES =
            integerProperty("geoworld.multichunk.expectedNaturalGeologyOverrides", 0);
    private static final int EXPECTED_EXTERNAL_VOID_BRIDGE =
            integerProperty("geoworld.multichunk.expectedExternalVoidBridge", 0);
    private static final int EXPECTED_MEMBRANE_STABILIZERS =
            integerProperty("geoworld.multichunk.expectedMembraneStabilizers", 0);

    private enum Mode {
        PREFLIGHT,
        FEATURE_PREFLIGHT,
        CARVE,
        POSTGEN_PREFLIGHT,
        POSTGEN_CARVE
    }

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureReserved,
            boolean blockEntity,
            String biomeIdentity,
            String priorBlockState,
            BlockState priorState,
            String action) {}

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

    private record Repair(
            KarstDeltaChunkReader.Voxel voxel,
            boolean protectedRoute,
            boolean boundary,
            String priorBlockState) {}

    private record PostCounts(int traversable, int protectedTraversable, int boundaryTraversable) {}

    private record FeatureFluidIngress(
            KarstDeltaChunkReader.Voxel target,
            boolean protectedRoute,
            boolean boundary,
            KarstDeltaChunkReader.Voxel source,
            String sourceState) {}

    private record FeatureDirectFluidTarget(
            KarstDeltaChunkReader.Voxel target,
            boolean protectedRoute,
            boolean boundary,
            String state) {}

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
    private static Path featureFluidRegisterPath;
    private static Path repairRegisterPath;
    private static Mode mode;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static List<KarstDeltaChunkReader.ChunkKey> targetChunks = List.of();
    private static List<KarstDeltaChunkReader.ChunkKey> auditChunks = List.of();
    private static Set<String> targetChunkKeys = Set.of();
    private static Set<String> auditChunkKeys = Set.of();
    private static Map<String, FrozenTarget> frozenTargets = Map.of();
    private static String frozenBindingSha256 = "";
    private static Map<String, String> preemptiveMembrane = Map.of();
    private static String preemptiveMembraneBindingSha256 = "";
    private static Map<String, String> sourceCaps = Map.of();
    private static String sourceCapBindingSha256 = "";
    private static Map<String, String> naturalGeologyOverrides = Map.of();
    private static boolean projectionBindingsLoaded;
    private static String naturalGeologyBindingSha256 = "";
    private static Set<String> projectedProtectedTargets = Set.of();
    private static String protectedRouteOverlaySha256 = "";
    private static Map<String, String> externalVoidBridge = Map.of();
    private static String externalVoidBridgeSha256 = "";
    private static Map<String, String> membraneStabilizers = Map.of();
    private static String membraneStabilizerBindingSha256 = "";
    private static final Set<String> observedCarveChunks = new HashSet<>();
    private static final Set<String> observedFeatureChunks = new HashSet<>();
    private static final Set<String> observedAuditFeatureChunks = new HashSet<>();
    private static final TreeMap<String, ChunkObservation> observations = new TreeMap<>();
    private static final TreeMap<String, PostCounts> postCounts = new TreeMap<>();
    private static final List<String> errors = new ArrayList<>();
    private static final List<Repair> repairs = new ArrayList<>();
    private static boolean initialized;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketsReleased;
    private static boolean featureFluidAuditStarted;
    private static boolean featureFluidAuditComplete;
    private static boolean postgenTransactionStarted;
    private static boolean protectedClosureStarted;
    private static boolean protectedClosureComplete;
    private static boolean lifecycleRollback;
    private static boolean quiescenceComplete;
    private static int serverTickCount;
    private static int protectedClosureCompletedTick = -1;
    private static int blockWriteCount;
    private static int fluidMembraneWriteCount;
    private static int fluidMembraneExactCount;
    private static int preemptiveMembranePreserveCount;
    private static int preemptiveMembraneRestoreWriteCount;
    private static int preemptiveMembraneExactCount;
    private static int sourceCapWriteCount;
    private static int sourceCapExactCount;
    private static int externalVoidBridgeExactCount;
    private static int membraneStabilizerWriteCount;
    private static int scheduledHydrologyFluidTickCount;
    private static int hydrologyTickableChunkCountBeforeReassertion;
    private static int hydrologyTickableChunkCountAfterReassertion;
    private static int targetLocalQueuedFluidTickCountAfterQuiescence;
    private static int ordinaryLiveFluidTargetCount;
    private static int hostHydrologyFluidCountAfterQuiescence;
    private static int rollbackWriteCount;
    private static final List<FeatureFluidIngress> featureFluidIngresses = new ArrayList<>();
    private static final List<FeatureDirectFluidTarget> featureDirectFluidTargets = new ArrayList<>();
    private static final TreeMap<String, Sample> fluidMembraneTargets = new TreeMap<>();
    private static final TreeMap<String, Sample> hostHydrologyTargets = new TreeMap<>();

    private MultichunkTransactionState() {}

    private static boolean hasFeatureAudit() {
        return mode == Mode.FEATURE_PREFLIGHT
                || mode == Mode.CARVE
                || mode == Mode.POSTGEN_PREFLIGHT
                || mode == Mode.POSTGEN_CARVE;
    }

    private static boolean isCarve() {
        return mode == Mode.CARVE || mode == Mode.POSTGEN_CARVE;
    }

    private static boolean isPostgen() {
        return mode == Mode.POSTGEN_PREFLIGHT || mode == Mode.POSTGEN_CARVE;
    }

    public static synchronized void initialize() {
        if (initialized) return;
        String packageProperty = System.getProperty("geoworld.multichunk.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.multichunk.receipt", "").trim();
        String registerProperty = System.getProperty("geoworld.multichunk.register", "").trim();
        String featureFluidRegisterProperty =
                System.getProperty("geoworld.multichunk.featureFluidRegister", "").trim();
        String repairRegisterProperty =
                System.getProperty("geoworld.multichunk.repairRegister", "").trim();
        String modeProperty = System.getProperty("geoworld.multichunk.mode", "").trim();
        if (packageProperty.isEmpty()
                || receiptProperty.isEmpty()
                || registerProperty.isEmpty()
                || modeProperty.isEmpty()) {
            throw new IllegalStateException("multichunk package, receipt, register or mode is empty");
        }
        mode = switch (modeProperty) {
            case "preflight" -> Mode.PREFLIGHT;
            case "feature-preflight" -> Mode.FEATURE_PREFLIGHT;
            case "carve" -> Mode.CARVE;
            case "postgen-preflight" -> Mode.POSTGEN_PREFLIGHT;
            case "postgen-carve" -> Mode.POSTGEN_CARVE;
            default -> throw new IllegalArgumentException("unknown multichunk mode");
        };
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        registerPath = Path.of(registerProperty).toAbsolutePath().normalize();
        if (hasFeatureAudit()) {
            require(!featureFluidRegisterProperty.isEmpty(), "feature fluid register is empty");
            featureFluidRegisterPath =
                    Path.of(featureFluidRegisterProperty).toAbsolutePath().normalize();
        }
        if (isCarve()) {
            require(
                    LIFECYCLE_QUIESCENCE_TICKS >= 200,
                    "carve lifecycle quiescence is shorter than 200 ticks");
            require(
                    MAXIMUM_ORDINARY_FLUID_TARGET_FRACTION >= 0.0
                            && MAXIMUM_ORDINARY_FLUID_TARGET_FRACTION <= 0.01,
                    "ordinary fluid target fraction is outside the bounded contract");
            require(
                    MAXIMUM_DEFERRED_HOST_FLUID_TARGET_FRACTION >= 0.0
                            && MAXIMUM_DEFERRED_HOST_FLUID_TARGET_FRACTION <= 0.01,
                    "deferred host fluid target fraction is outside the bounded contract");
            require(!repairRegisterProperty.isEmpty(), "protected closure register is empty");
            repairRegisterPath = Path.of(repairRegisterProperty).toAbsolutePath().normalize();
        }
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
        if (mode == Mode.POSTGEN_CARVE
                || (mode == Mode.POSTGEN_PREFLIGHT
                        && !System.getProperty(
                                "geoworld.multichunk.naturalGeologyBinding", "").trim().isEmpty())) {
            loadProjectionBindings();
            projectionBindingsLoaded = true;
        }
        targetChunks = selectTargetChunks();
        require(targetChunks.size() == EXPECTED_CHUNKS, "target chunk count differs");
        Set<String> keys = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) keys.add(chunkKey(key));
        targetChunkKeys = Set.copyOf(keys);
        if (hasFeatureAudit()) {
            if (isCarve()) {
                loadFrozenBinding();
                loadPreemptiveMembraneBinding();
                loadMembraneStabilizerBinding();
                loadSourceCapBinding();
            }
            auditChunks = selectAuditChunks();
            Set<String> auditKeys = new HashSet<>();
            for (KarstDeltaChunkReader.ChunkKey key : auditChunks) {
                auditKeys.add(chunkKey(key));
            }
            auditChunkKeys = Set.copyOf(auditKeys);
        }
        initialized = true;
        KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                "Run {} initialized mode={} target-chunks={} frozen-targets={}",
                RUN,
                mode.name(),
                targetChunks.size(),
                frozenTargets.size());
    }

    private static List<KarstDeltaChunkReader.ChunkKey> selectAuditChunks() {
        Set<String> seen = new HashSet<>();
        List<KarstDeltaChunkReader.ChunkKey> result = new ArrayList<>();
        for (KarstDeltaChunkReader.ChunkKey target : targetChunks) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    KarstDeltaChunkReader.ChunkKey key =
                            new KarstDeltaChunkReader.ChunkKey(target.x() + dx, target.z() + dz);
                    if (seen.add(chunkKey(key))) result.add(key);
                }
            }
        }
        result.sort(Comparator.comparingInt(KarstDeltaChunkReader.ChunkKey::x)
                .thenComparingInt(KarstDeltaChunkReader.ChunkKey::z));
        return List.copyOf(result);
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
        String bindingProperty = System.getProperty("geoworld.multichunk.binding", "").trim();
        String hashProperty =
                System.getProperty("geoworld.multichunk.bindingSha256", "").trim();
        if (bindingProperty.isEmpty() || hashProperty.length() != 64) {
            throw new IllegalStateException("carve mode requires a frozen binding and digest");
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

    private static void loadPreemptiveMembraneBinding() {
        String bindingProperty =
                System.getProperty("geoworld.multichunk.preemptiveMembraneBinding", "").trim();
        String hashProperty = System.getProperty(
                "geoworld.multichunk.preemptiveMembraneBindingSha256", "").trim();
        require(EXPECTED_PREEMPTIVE_MEMBRANE >= 0, "preemptive membrane count is negative");
        require(!bindingProperty.isEmpty(), "preemptive membrane binding is empty");
        require(hashProperty.length() == 64, "preemptive membrane digest width differs");
        Path path = Path.of(bindingProperty).toAbsolutePath().normalize();
        try {
            preemptiveMembraneBindingSha256 = sha256(path);
            require(
                    preemptiveMembraneBindingSha256.equals(hashProperty),
                    "preemptive membrane digest differs");
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 4);
                require(fields.length == 4, "preemptive membrane row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                FrozenTarget frozen = frozenTargets.get(key);
                require(frozen != null, "preemptive membrane target is outside frozen binding");
                require(!frozen.protectedRoute(), "preemptive membrane intersects protected route");
                require(!frozen.boundary(), "preemptive membrane intersects boundary topology");
                require("CARVE".equals(frozen.action()), "preemptive membrane is not CARVE");
                require(
                        frozen.priorBlockState().equals(fields[3]),
                        "preemptive membrane prior state differs");
                require(values.put(key, fields[3]) == null, "preemptive membrane repeats target");
            }
            require(
                    values.size() == EXPECTED_PREEMPTIVE_MEMBRANE,
                    "preemptive membrane target count differs");
            preemptiveMembrane = Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("preemptive membrane binding read failed", exception);
        }
    }

    private static void loadSourceCapBinding() {
        String bindingProperty =
                System.getProperty("geoworld.multichunk.sourceCapBinding", "").trim();
        String hashProperty =
                System.getProperty("geoworld.multichunk.sourceCapBindingSha256", "").trim();
        require(EXPECTED_SOURCE_CAPS >= 0, "source cap count is negative");
        require(!bindingProperty.isEmpty(), "source cap binding is empty");
        require(hashProperty.length() == 64, "source cap digest width differs");
        Path path = Path.of(bindingProperty).toAbsolutePath().normalize();
        try {
            sourceCapBindingSha256 = sha256(path);
            require(sourceCapBindingSha256.equals(hashProperty), "source cap digest differs");
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 4);
                require(fields.length == 4, "source cap row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                FrozenTarget frozen = frozenTargets.get(key);
                require(frozen != null, "source cap target is outside frozen binding");
                require(!frozen.protectedRoute(), "source cap intersects protected route");
                require(!frozen.boundary(), "source cap intersects boundary topology");
                require(
                        "DEFER_FLUID".equals(frozen.action())
                                || "NOOP_EXISTING_VOID".equals(frozen.action()),
                        "source cap target is not host hydrology");
                Identifier replacementId = Identifier.of(fields[3]);
                require(
                        Registries.BLOCK.getId(Registries.BLOCK.get(replacementId))
                                .equals(replacementId),
                        "source cap replacement block is not registered");
                require(values.put(key, fields[3]) == null, "source cap repeats target");
            }
            require(values.size() == EXPECTED_SOURCE_CAPS, "source cap count differs");
            sourceCaps = Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("source cap binding read failed", exception);
        }
    }

    private static void loadMembraneStabilizerBinding() {
        String bindingProperty = System.getProperty(
                "geoworld.multichunk.membraneStabilizerBinding", "").trim();
        String hashProperty = System.getProperty(
                "geoworld.multichunk.membraneStabilizerBindingSha256", "").trim();
        require(!bindingProperty.isEmpty(), "membrane stabilizer binding is empty");
        require(hashProperty.length() == 64, "membrane stabilizer digest width differs");
        try {
            Path path = Path.of(bindingProperty).toAbsolutePath().normalize();
            membraneStabilizerBindingSha256 = sha256(path);
            require(membraneStabilizerBindingSha256.equals(hashProperty),
                    "membrane stabilizer digest differs");
            Map<String, String> values = new HashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 4);
                require(fields.length == 4, "membrane stabilizer row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                require(preemptiveMembrane.containsKey(key),
                        "membrane stabilizer is outside preemptive membrane");
                Identifier replacementId = Identifier.of(fields[3]);
                require(Registries.BLOCK.getId(Registries.BLOCK.get(replacementId))
                                .equals(replacementId),
                        "membrane stabilizer block is not registered");
                require(values.put(key, fields[3]) == null,
                        "membrane stabilizer repeats target");
            }
            require(values.size() == EXPECTED_MEMBRANE_STABILIZERS,
                    "membrane stabilizer count differs");
            membraneStabilizers = Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("membrane stabilizer binding read failed", exception);
        }
    }

    private static void loadProjectionBindings() {
        String geologyPathProperty =
                System.getProperty("geoworld.multichunk.naturalGeologyBinding", "").trim();
        String geologyHashProperty = System.getProperty(
                "geoworld.multichunk.naturalGeologyBindingSha256", "").trim();
        String overlayPathProperty =
                System.getProperty("geoworld.multichunk.protectedRouteOverlay", "").trim();
        String overlayHashProperty = System.getProperty(
                "geoworld.multichunk.protectedRouteOverlaySha256", "").trim();
        String bridgePathProperty =
                System.getProperty("geoworld.multichunk.externalVoidBridge", "").trim();
        String bridgeHashProperty = System.getProperty(
                "geoworld.multichunk.externalVoidBridgeSha256", "").trim();
        require(!geologyPathProperty.isEmpty() && geologyHashProperty.length() == 64,
                "postgen natural geology binding is incomplete");
        require(!overlayPathProperty.isEmpty() && overlayHashProperty.length() == 64,
                "postgen protected route overlay is incomplete");
        require(!bridgePathProperty.isEmpty() && bridgeHashProperty.length() == 64,
                "postgen external void bridge is incomplete");
        try {
            Path geologyPath = Path.of(geologyPathProperty).toAbsolutePath().normalize();
            naturalGeologyBindingSha256 = sha256(geologyPath);
            require(naturalGeologyBindingSha256.equals(geologyHashProperty),
                    "natural geology binding digest differs");
            Map<String, String> geology = new HashMap<>();
            for (String line : Files.readAllLines(geologyPath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 5);
                require(fields.length == 5, "natural geology row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                require(geology.put(key, fields[4]) == null,
                        "natural geology binding repeats target");
            }
            require(geology.size() == EXPECTED_NATURAL_GEOLOGY_OVERRIDES,
                    "natural geology override count differs");
            naturalGeologyOverrides = Map.copyOf(geology);

            Path overlayPath = Path.of(overlayPathProperty).toAbsolutePath().normalize();
            protectedRouteOverlaySha256 = sha256(overlayPath);
            require(protectedRouteOverlaySha256.equals(overlayHashProperty),
                    "protected route overlay digest differs");
            Set<String> projected = new HashSet<>();
            int targetCount = 0;
            for (String line : Files.readAllLines(overlayPath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 4);
                require(fields.length == 4, "protected route overlay row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                require(projected.add(key), "protected route overlay repeats voxel");
                if ("TARGET".equals(fields[3])) targetCount++;
                else require("EXTERNAL_EXISTING_VOID".equals(fields[3]),
                        "protected route overlay kind differs");
            }
            require(targetCount == EXPECTED_PROTECTED,
                    "projected target protected count differs");
            projectedProtectedTargets = Set.copyOf(projected);

            Path bridgePath = Path.of(bridgePathProperty).toAbsolutePath().normalize();
            externalVoidBridgeSha256 = sha256(bridgePath);
            require(externalVoidBridgeSha256.equals(bridgeHashProperty),
                    "external void bridge digest differs");
            Map<String, String> bridge = new HashMap<>();
            for (String line : Files.readAllLines(bridgePath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t", 4);
                require(fields.length == 4, "external void bridge row width differs");
                String key = fields[0] + "," + fields[1] + "," + fields[2];
                require(projected.contains(key), "external bridge is outside topology overlay");
                require(bridge.put(key, fields[3]) == null,
                        "external void bridge repeats voxel");
            }
            require(bridge.size() == EXPECTED_EXTERNAL_VOID_BRIDGE,
                    "external void bridge count differs");
            externalVoidBridge = Map.copyOf(bridge);
        } catch (IOException exception) {
            throw new IllegalStateException("postgen projection binding read failed", exception);
        }
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "multichunk transaction is not initialized");
        List<KarstDeltaChunkReader.ChunkKey> forced =
                mode == Mode.PREFLIGHT ? targetChunks : auditChunks;
        for (KarstDeltaChunkReader.ChunkKey key : forced) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), true);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        boolean shouldRunPostgen;
        boolean shouldAudit;
        synchronized (MultichunkTransactionState.class) {
            serverTickCount++;
            shouldRunPostgen = isPostgen()
                    && !postgenTransactionStarted
                    && serverTickCount >= LIFECYCLE_QUIESCENCE_TICKS;
            if (shouldRunPostgen) postgenTransactionStarted = true;
            shouldAudit = mode == Mode.FEATURE_PREFLIGHT
                    && !featureFluidAuditStarted
                    && !featureFluidAuditComplete
                    && observedCarveChunks.size() == EXPECTED_CHUNKS
                    && observedFeatureChunks.size() == EXPECTED_CHUNKS
                    && observedAuditFeatureChunks.size() == auditChunks.size();
            if (shouldAudit) featureFluidAuditStarted = true;
        }
        if (shouldRunPostgen) {
            try {
                runPostgenTransaction(server);
            } catch (RuntimeException exception) {
                synchronized (MultichunkTransactionState.class) {
                    errors.add("postgen-transaction:" + errorSummary(exception));
                }
                KarstogenesisFabricMultichunkTransaction.LOGGER.error(
                        "Run {} postgen transaction failed", RUN, exception);
            }
        }
        if (shouldAudit) {
            try {
                auditFeatureFluidInterface(server);
            } catch (RuntimeException exception) {
                synchronized (MultichunkTransactionState.class) {
                    errors.add("feature-fluid-audit:" + errorSummary(exception));
                }
                KarstogenesisFabricMultichunkTransaction.LOGGER.error(
                        "Run {} feature fluid audit failed", RUN, exception);
            }
            synchronized (MultichunkTransactionState.class) {
                featureFluidAuditComplete = true;
            }
        }
        boolean shouldClose;
        synchronized (MultichunkTransactionState.class) {
            shouldClose = isCarve()
                    && !protectedClosureStarted
                    && !protectedClosureComplete
                    && observedCarveChunks.size() == EXPECTED_CHUNKS
                    && observedAuditFeatureChunks.size() == auditChunks.size();
            if (shouldClose) protectedClosureStarted = true;
        }
        if (shouldClose) {
            try {
                applyLifecycleClosure(server);
            } catch (RuntimeException exception) {
                synchronized (MultichunkTransactionState.class) {
                    errors.add("lifecycle-closure:" + errorSummary(exception));
                }
                KarstogenesisFabricMultichunkTransaction.LOGGER.error(
                        "Run {} lifecycle closure failed", RUN, exception);
            }
            synchronized (MultichunkTransactionState.class) {
                protectedClosureComplete = true;
                protectedClosureCompletedTick = serverTickCount;
            }
        }
        boolean shouldFinishQuiescence;
        synchronized (MultichunkTransactionState.class) {
            shouldFinishQuiescence = isCarve()
                    && protectedClosureComplete
                    && !quiescenceComplete
                    && serverTickCount - protectedClosureCompletedTick
                            >= LIFECYCLE_QUIESCENCE_TICKS;
        }
        if (shouldFinishQuiescence) {
            try {
                auditAfterQuiescence(server);
            } catch (RuntimeException exception) {
                synchronized (MultichunkTransactionState.class) {
                    errors.add("quiescence-audit:" + errorSummary(exception));
                }
                KarstogenesisFabricMultichunkTransaction.LOGGER.error(
                        "Run {} lifecycle quiescence audit failed", RUN, exception);
            }
            synchronized (MultichunkTransactionState.class) {
                quiescenceComplete = true;
            }
            releaseTickets(server);
        }
        boolean shouldStop = false;
        synchronized (MultichunkTransactionState.class) {
            attemptReceipt();
            if (!receiptWritten && serverTickCount == 6000) {
                errors.add("timeout-before-complete-receipt");
                writeOutputs();
            } else if (!receiptWritten && serverTickCount % 200 == 0) {
                KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                        "Run {} gate mode={} carve={}/{} features={}/{} writes={} errors={}",
                        RUN,
                        mode.name(),
                        observedCarveChunks.size(),
                        EXPECTED_CHUNKS,
                        observedFeatureChunks.size(),
                        EXPECTED_CHUNKS,
                        blockWriteCount,
                        errors.size());
            }
            if (receiptWritten && !stopRequested) {
                stopRequested = true;
                shouldStop = true;
            }
        }
        if (shouldStop) {
            releaseTickets(server);
            KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                    "Run {} receipt written, stopping disposable server", RUN);
            server.stop(false);
        }
    }

    private static void releaseTickets(MinecraftServer server) {
        if (ticketsReleased) return;
        List<KarstDeltaChunkReader.ChunkKey> forced =
                mode == Mode.PREFLIGHT ? targetChunks : auditChunks;
        for (KarstDeltaChunkReader.ChunkKey key : forced) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), false);
        }
        ticketsReleased = true;
    }

    private static void runPostgenTransaction(MinecraftServer server) {
        require(isPostgen(), "postgen transaction requires postgen mode");
        for (KarstDeltaChunkReader.ChunkKey key : auditChunks) {
            server.getOverworld().getChunk(key.x(), key.z());
            observedAuditFeatureChunks.add(chunkKey(key));
        }
        externalVoidBridgeExactCount = 0;
        for (Map.Entry<String, String> entry : externalVoidBridge.entrySet()) {
            String[] fields = entry.getKey().split(",", 3);
            BlockState state = server.getOverworld().getBlockState(new BlockPos(
                    Integer.parseInt(fields[0]),
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2])));
            if (state.isAir()
                    && Registries.BLOCK.getId(state.getBlock()).toString().equals(entry.getValue())) {
                externalVoidBridgeExactCount++;
            }
        }
        require(externalVoidBridgeExactCount == EXPECTED_EXTERNAL_VOID_BRIDGE,
                "external existing-void bridge state differs");
        StructureAccessor structureAccessor = server.getOverworld().getStructureAccessor();
        Map<String, ChunkObservation> transaction = new HashMap<>();
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
            Chunk chunk = server.getOverworld().getChunk(key.x(), key.z());
            ChunkObservation observation = observePlan(
                    server.getOverworld(), chunk, structureAccessor);
            if (mode == Mode.POSTGEN_CARVE) {
                validateFrozen(observation);
                require(!observation.rejected(), "frozen postgen arbitration rejected");
            }
            transaction.put(chunkKey(key), observation);
        }
        if (mode == Mode.POSTGEN_CARVE) {
            for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
                Chunk chunk = server.getOverworld().getChunk(key.x(), key.z());
                applyWrites(chunk, transaction.get(chunkKey(key)).samples());
            }
        }
        for (Map.Entry<String, ChunkObservation> entry : transaction.entrySet()) {
            observations.put(entry.getKey(), entry.getValue());
            observedCarveChunks.add(entry.getKey());
            observedFeatureChunks.add(entry.getKey());
        }
        observePostCounts(server);
        if (mode == Mode.POSTGEN_PREFLIGHT) {
            auditFeatureFluidInterface(server);
            featureFluidAuditComplete = true;
            releaseTickets(server);
        }
        KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                "Run {} postgen transaction observed targets={} audit-chunks={} writes={}",
                RUN,
                observations.size(),
                observedAuditFeatureChunks.size(),
                blockWriteCount);
    }

    public static synchronized void observeCarveTail(
            ChunkRegion world,
            Chunk chunk,
            StructureAccessor structureAccessor) {
        String key = chunkKey(chunk.getPos());
        if (!targetChunkKeys.contains(key) || !observedCarveChunks.add(key)) return;
        try {
            ChunkObservation observation = observePlan(world, chunk, structureAccessor);
            if (mode == Mode.CARVE) {
                validateFrozen(observation);
                require(!observation.rejected(), "frozen chunk arbitration rejected");
                applyWrites(chunk, observation.samples());
            }
            observations.put(key, observation);
        } catch (RuntimeException exception) {
            errors.add(key + ":" + errorSummary(exception));
            KarstogenesisFabricMultichunkTransaction.LOGGER.error(
                    "Run {} carve-tail observation failed for {}", RUN, key, exception);
        }
        attemptReceipt();
    }

    public static synchronized void observeFeaturesTail(Chunk chunk) {
        String key = chunkKey(chunk.getPos());
        if (mode != Mode.PREFLIGHT && auditChunkKeys.contains(key)) {
            observedAuditFeatureChunks.add(key);
        }
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

    private static void applyLifecycleClosure(MinecraftServer server) {
        require(isCarve(), "lifecycle closure requires carve mode");
        require(!protectedClosureComplete, "lifecycle closure already completed");
        require(observations.size() == EXPECTED_CHUNKS, "lifecycle closure observations differ");
        restorePreemptiveMembrane(server);
        auditFeatureFluidInterface(server);
        featureFluidAuditComplete = true;
        classifyFluidTargets();
        if (lifecycleRollback) {
            applyTargetRollback(server);
            observePostCounts(server);
            writeRepairRegister();
            writeFeatureFluidRegister();
            KarstogenesisFabricMultichunkTransaction.LOGGER.warn(
                    "Run {} lifecycle policy rolled back carve writes={} fluid-targets={}",
                    RUN,
                    rollbackWriteCount,
                    fluidMembraneTargets.size() + hostHydrologyTargets.size());
            return;
        }
        applyFluidMembrane(server);
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
                chunk.setBlockState(position, Blocks.CAVE_AIR.getDefaultState(), false);
            }
        }
        repairs.sort(Comparator.comparing(value -> voxelKey(value.voxel())));
        observePostCounts(server);
        writeRepairRegister();
        writeFeatureFluidRegister();
        KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                "Run {} lifecycle closure completed repairs={} membranes={} host-hydrology={} audit-chunks={}",
                RUN,
                repairs.size(),
                fluidMembraneTargets.size(),
                hostHydrologyTargets.size(),
                auditChunks.size());
    }

    /**
     * Direct chunk writes intentionally suppress neighbor updates while the transaction is open.
     * Once its geometry and safety membranes are final, explicitly wake every fluid at or
     * face-adjacent to a registered target so the bounded quiescence interval saves the same
     * hydrology state that a later ordinary chunk load would otherwise discover.
     */
    private static void scheduleHydrologySettlement(MinecraftServer server) {
        for (KarstDeltaChunkReader.ChunkKey key : auditChunks) {
            if (server.getOverworld().shouldTickBlocksInChunk(new ChunkPos(key.x(), key.z()).toLong())) {
                hydrologyTickableChunkCountBeforeReassertion++;
            }
            if (server.getOverworld().shouldTickBlocksInChunk(new ChunkPos(key.x(), key.z()).toLong())) {
                hydrologyTickableChunkCountAfterReassertion++;
            }
        }
        TreeMap<String, BlockPos> scheduled = targetLocalFluidFrontier(server);
        for (BlockPos position : scheduled.values()) {
            var fluidState = server.getOverworld().getFluidState(position);
            if (fluidState.isEmpty()) continue;
            server.getOverworld().scheduleFluidTick(position, fluidState.getFluid(), 1);
            scheduledHydrologyFluidTickCount++;
        }
    }

    private static TreeMap<String, BlockPos> targetLocalFluidFrontier(MinecraftServer server) {
        int[][] offsets = {
            {0, 0, 0}, {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
        };
        TreeMap<String, BlockPos> scheduled = new TreeMap<>();
        for (ChunkObservation observation : observations.values()) {
            for (Sample sample : observation.samples()) {
                BlockPos target = blockPos(sample.planned().worldVoxel());
                for (int[] offset : offsets) {
                    BlockPos candidate = target.add(offset[0], offset[1], offset[2]);
                    if (server.getOverworld().getFluidState(candidate).isEmpty()) continue;
                    scheduled.put(candidate.getX() + "," + candidate.getY() + "," + candidate.getZ(), candidate);
                }
            }
        }
        return scheduled;
    }

    private static void classifyFluidTargets() {
        fluidMembraneTargets.clear();
        hostHydrologyTargets.clear();
        lifecycleRollback = false;
        ordinaryLiveFluidTargetCount = 0;
        TreeMap<String, Sample> samples = samplesByVoxel();
        Set<String> targets = new HashSet<>();
        for (Map.Entry<String, Sample> entry : samples.entrySet()) {
            Sample sample = entry.getValue();
            if (!"DEFER_FLUID".equals(sample.action())) continue;
            if (sourceCaps.containsKey(entry.getKey())) continue;
            if (sample.planned().protectedRoute() || sample.planned().registeredBoundary()) {
                lifecycleRollback = true;
            } else {
                hostHydrologyTargets.put(entry.getKey(), sample);
            }
        }
        for (FeatureDirectFluidTarget direct : featureDirectFluidTargets) {
            targets.add(voxelKey(direct.target()));
        }
        for (FeatureFluidIngress ingress : featureFluidIngresses) {
            targets.add(voxelKey(ingress.target()));
        }
        for (String key : targets) {
            Sample sample = samples.get(key);
            require(sample != null, "fluid target sample is unavailable");
            if (sourceCaps.containsKey(key)) continue;
            if (preemptiveMembrane.containsKey(key)) continue;
            ordinaryLiveFluidTargetCount++;
            if (sample.planned().protectedRoute() || sample.planned().registeredBoundary()) {
                lifecycleRollback = true;
            } else if ("CARVE".equals(sample.action())) {
                fluidMembraneTargets.put(key, sample);
            } else if ("NOOP_EXISTING_VOID".equals(sample.action())) {
                hostHydrologyTargets.put(key, sample);
            } else {
                lifecycleRollback = true;
            }
        }
        if ((double) ordinaryLiveFluidTargetCount / EXPECTED_PLANNED
                > MAXIMUM_ORDINARY_FLUID_TARGET_FRACTION) {
            lifecycleRollback = true;
        }
    }

    private static TreeMap<String, Sample> samplesByVoxel() {
        TreeMap<String, Sample> result = new TreeMap<>();
        for (ChunkObservation observation : observations.values()) {
            for (Sample sample : observation.samples()) {
                result.put(voxelKey(sample.planned().worldVoxel()), sample);
            }
        }
        return result;
    }

    private static void applyFluidMembrane(MinecraftServer server) {
        for (Sample sample : fluidMembraneTargets.values()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            Chunk chunk = server.getOverworld().getChunk(voxel.x() >> 4, voxel.z() >> 4);
            BlockPos position = blockPos(voxel);
            if (chunk.getBlockState(position).equals(sample.priorState())) continue;
            chunk.setBlockState(position, sample.priorState(), false);
            fluidMembraneWriteCount++;
        }
    }

    private static void restorePreemptiveMembrane(MinecraftServer server) {
        TreeMap<String, Sample> samples = samplesByVoxel();
        for (String key : preemptiveMembrane.keySet()) {
            Sample sample = samples.get(key);
            require(sample != null, "preemptive membrane sample is unavailable");
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            Chunk chunk = server.getOverworld().getChunk(voxel.x() >> 4, voxel.z() >> 4);
            BlockPos position = blockPos(voxel);
            BlockState expected = membraneState(key, sample);
            if (chunk.getBlockState(position).equals(expected)) continue;
            chunk.setBlockState(position, expected, false);
            preemptiveMembraneRestoreWriteCount++;
        }
    }

    private static void applyTargetRollback(MinecraftServer server) {
        for (ChunkObservation observation : observations.values()) {
            Chunk chunk = server.getOverworld().getChunk(
                    observation.key().x(), observation.key().z());
            for (Sample sample : observation.samples()) {
                String key = voxelKey(sample.planned().worldVoxel());
                if (!"CARVE".equals(sample.action()) && !sourceCaps.containsKey(key)) continue;
                BlockPos position = blockPos(sample.planned().worldVoxel());
                if (chunk.getBlockState(position).equals(sample.priorState())) continue;
                chunk.setBlockState(position, sample.priorState(), false);
                rollbackWriteCount++;
            }
        }
    }

    private static void auditAfterQuiescence(MinecraftServer server) {
        require(protectedClosureComplete, "quiescence audit preceded lifecycle closure");
        observePostCounts(server);
        fluidMembraneExactCount = 0;
        for (Sample sample : fluidMembraneTargets.values()) {
            if (server.getOverworld()
                    .getBlockState(blockPos(sample.planned().worldVoxel()))
                    .equals(sample.priorState())) {
                fluidMembraneExactCount++;
            }
        }
        hostHydrologyFluidCountAfterQuiescence = 0;
        for (Sample sample : hostHydrologyTargets.values()) {
            if (!server.getOverworld()
                    .getBlockState(blockPos(sample.planned().worldVoxel()))
                    .getFluidState()
                    .isEmpty()) {
                hostHydrologyFluidCountAfterQuiescence++;
            }
        }
        targetLocalQueuedFluidTickCountAfterQuiescence = 0;
        for (BlockPos position : targetLocalFluidFrontier(server).values()) {
            var fluidState = server.getOverworld().getFluidState(position);
            if (!fluidState.isEmpty()
                    && server.getOverworld().getFluidTickScheduler()
                            .isQueued(position, fluidState.getFluid())) {
                targetLocalQueuedFluidTickCountAfterQuiescence++;
            }
        }
        preemptiveMembraneExactCount = 0;
        TreeMap<String, Sample> samples = samplesByVoxel();
        for (String key : preemptiveMembrane.keySet()) {
            Sample sample = samples.get(key);
            if (server.getOverworld()
                    .getBlockState(blockPos(sample.planned().worldVoxel()))
                    .equals(membraneState(key, sample))) {
                preemptiveMembraneExactCount++;
            }
        }
        sourceCapExactCount = 0;
        for (Map.Entry<String, String> entry : sourceCaps.entrySet()) {
            Sample sample = samples.get(entry.getKey());
            BlockState replacement = Registries.BLOCK
                    .get(Identifier.of(entry.getValue()))
                    .getDefaultState();
            if (server.getOverworld()
                    .getBlockState(blockPos(sample.planned().worldVoxel()))
                    .equals(replacement)) {
                sourceCapExactCount++;
            }
        }
        KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                "Run {} quiescence completed ticks={} exact-membranes={}/{} host-fluid={}/{} rollback={}",
                RUN,
                LIFECYCLE_QUIESCENCE_TICKS,
                fluidMembraneExactCount,
                fluidMembraneTargets.size(),
                hostHydrologyFluidCountAfterQuiescence,
                hostHydrologyTargets.size(),
                lifecycleRollback);
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
                boolean air = chunk.getBlockState(blockPos(sample.planned().worldVoxel())).isAir();
                if (air) traversable++;
                if (air && sample.planned().protectedRoute()) protectedTraversable++;
                if (air && sample.planned().registeredBoundary()) boundaryTraversable++;
            }
            postCounts.put(
                    chunkKey(observation.key()),
                    new PostCounts(traversable, protectedTraversable, boundaryTraversable));
        }
    }

    private static void auditFeatureFluidInterface(MinecraftServer server) {
        featureFluidIngresses.clear();
        featureDirectFluidTargets.clear();
        Set<String> targetKeys = new HashSet<>();
        for (ChunkObservation observation : observations.values()) {
            for (Sample sample : observation.samples()) {
                targetKeys.add(voxelKey(sample.planned().worldVoxel()));
            }
        }
        int[][] offsets = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0},
            {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
        };
        for (ChunkObservation observation : observations.values()) {
            for (Sample sample : observation.samples()) {
                if (!"CARVE".equals(sample.action())
                        && !"NOOP_EXISTING_VOID".equals(sample.action())) {
                    continue;
                }
                KarstDeltaChunkReader.Voxel target = sample.planned().worldVoxel();
                BlockState targetState = server.getOverworld().getBlockState(blockPos(target));
                if (!targetState.getFluidState().isEmpty()) {
                    featureDirectFluidTargets.add(new FeatureDirectFluidTarget(
                            target,
                            sample.planned().protectedRoute(),
                            sample.planned().registeredBoundary(),
                            targetState.toString()));
                }
                for (int[] offset : offsets) {
                    KarstDeltaChunkReader.Voxel source = new KarstDeltaChunkReader.Voxel(
                            target.x() + offset[0],
                            target.y() + offset[1],
                            target.z() + offset[2]);
                    if (targetKeys.contains(voxelKey(source))) continue;
                    BlockState sourceState = server.getOverworld().getBlockState(blockPos(source));
                    if (sourceState.getFluidState().isEmpty()) continue;
                    featureFluidIngresses.add(new FeatureFluidIngress(
                            target,
                            sample.planned().protectedRoute(),
                            sample.planned().registeredBoundary(),
                            source,
                            sourceState.toString()));
                }
            }
        }
        featureFluidIngresses.sort(Comparator
                .comparing((FeatureFluidIngress value) -> voxelKey(value.target()))
                .thenComparing(value -> voxelKey(value.source())));
        featureDirectFluidTargets.sort(Comparator.comparing(
                value -> voxelKey(value.target())));
        KarstogenesisFabricMultichunkTransaction.LOGGER.info(
                "Run {} feature preflight completed direct-fluid-targets={} external-fluid-edges={}",
                RUN,
                featureDirectFluidTargets.size(),
                featureFluidIngresses.size());
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
        for (KarstDeltaChunkReader.PlannedVoxel sourcePlanned : plan.planned()) {
            KarstDeltaChunkReader.PlannedVoxel planned = projectedTopology(sourcePlanned);
            BlockPos position = blockPos(planned.worldVoxel());
            BlockState state = chunk.getBlockState(position);
            boolean reserved = false;
            for (StructureStart start : starts) {
                if (!start.hasChildren()) continue;
                for (StructurePiece piece : start.getChildren()) {
                    if (piece.getBoundingBox().contains(position)) {
                        reserved = true;
                        break;
                    }
                }
                if (reserved) break;
            }
            KarstHostArbitration.HostState hostState = classify(state);
            String key = voxelKey(planned.worldVoxel());
            if (naturalGeologyOverrides.containsKey(key)) {
                require(state.toString().equals(naturalGeologyOverrides.get(key)),
                        "natural geology prior state differs");
                hostState = KarstHostArbitration.HostState.REPLACEABLE_SOLID;
            }
            raw.add(new Sample(
                    planned,
                    hostState,
                    reserved,
                    state.hasBlockEntity(),
                    biomeIdentity(chunk, position),
                    state.toString(),
                    state,
                    ""));
        }
        raw.sort(Comparator.comparing(sample -> voxelKey(sample.planned().worldVoxel())));
        KarstHostArbitration.Plan arbitration = arbitrate(raw);
        List<Sample> samples = new ArrayList<>(raw.size());
        if (arbitration.state() == KarstHostArbitration.PlanState.ACTIVE
                || (isPostgen() && arbitration.decisions().size() == raw.size())) {
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

    private static KarstDeltaChunkReader.PlannedVoxel projectedTopology(
            KarstDeltaChunkReader.PlannedVoxel planned) {
        if (!projectionBindingsLoaded) return planned;
        return new KarstDeltaChunkReader.PlannedVoxel(
                planned.sourceVoxel(),
                planned.worldVoxel(),
                planned.evolvedAddition(),
                projectedProtectedTargets.contains(voxelKey(planned.worldVoxel())),
                planned.registeredBoundary(),
                planned.material(),
                planned.biome());
    }

    private static Sample withAction(Sample sample, String action) {
        return new Sample(
                sample.planned(),
                sample.hostState(),
                sample.structureReserved(),
                sample.blockEntity(),
                sample.biomeIdentity(),
                sample.priorBlockState(),
                sample.priorState(),
                action);
    }

    private static void validateFrozen(ChunkObservation observation) {
        for (Sample sample : observation.samples()) {
            KarstDeltaChunkReader.Voxel voxel = sample.planned().worldVoxel();
            String key = voxelKey(voxel);
            FrozenTarget frozen = frozenTargets.get(key);
            require(frozen != null, "frozen target is absent at " + key);
            require(
                    frozen.protectedRoute() == sample.planned().protectedRoute(),
                    "frozen protected flag differs at " + key);
            require(
                    frozen.boundary() == sample.planned().registeredBoundary(),
                    "frozen boundary flag differs at " + key);
            require(
                    frozen.action().equals(sample.action()),
                    "frozen action differs at " + key
                            + " expected=" + frozen.action()
                            + " actual=" + sample.action()
                            + " prior=" + sample.priorBlockState());
            require(
                    frozen.priorBlockState().equals(sample.priorBlockState()),
                    "frozen prior block differs at " + key
                            + " expected=" + frozen.priorBlockState()
                            + " actual=" + sample.priorBlockState());
        }
    }

    private static void applyWrites(Chunk chunk, List<Sample> samples) {
        for (Sample sample : samples) {
            String key = voxelKey(sample.planned().worldVoxel());
            if (sourceCaps.containsKey(key)) {
                BlockPos position = blockPos(sample.planned().worldVoxel());
                BlockState replacement = Registries.BLOCK
                        .get(Identifier.of(sourceCaps.get(key)))
                        .getDefaultState();
                if (!chunk.getBlockState(position).equals(replacement)) {
                    chunk.setBlockState(position, replacement, false);
                    sourceCapWriteCount++;
                }
                continue;
            }
            if (!"CARVE".equals(sample.action())) continue;
            if (preemptiveMembrane.containsKey(key)) {
                preemptiveMembranePreserveCount++;
                if (membraneStabilizers.containsKey(key)) {
                    BlockPos position = blockPos(sample.planned().worldVoxel());
                    BlockState replacement = membraneState(key, sample);
                    if (!chunk.getBlockState(position).equals(replacement)) {
                        chunk.setBlockState(position, replacement, false);
                        membraneStabilizerWriteCount++;
                    }
                }
                continue;
            }
            BlockPos position = blockPos(sample.planned().worldVoxel());
            chunk.setBlockState(position, Blocks.CAVE_AIR.getDefaultState(), false);
            blockWriteCount++;
        }
    }

    private static BlockState membraneState(String key, Sample sample) {
        String replacement = membraneStabilizers.get(key);
        return replacement == null
                ? sample.priorState()
                : Registries.BLOCK.get(Identifier.of(replacement)).getDefaultState();
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
                            sample.structureReserved()
                                    ? KarstHostArbitration.ClaimClass.RESERVED_STRUCTURE
                                    : KarstHostArbitration.ClaimClass.NONE,
                            sample.structureReserved() ? "host:structure" : "host:vanilla",
                            KarstHostArbitration.BiomeAction.PRESERVE_EXISTING,
                            sample.biomeIdentity());
            inputs.add(new KarstHostArbitration.Input(geometry, host));
        }
        return isPostgen()
                ? KarstHostArbitration.arbitrateLocally(inputs)
                : KarstHostArbitration.arbitrate(inputs);
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
            if (sample.structureReserved()) result.structureReserved++;
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
                || ((mode == Mode.FEATURE_PREFLIGHT || mode == Mode.POSTGEN_PREFLIGHT)
                        && !featureFluidAuditComplete)
                || (isCarve()
                        && (observedAuditFeatureChunks.size() != auditChunks.size()
                                || !protectedClosureComplete
                                || !quiescenceComplete
                                || !ticketsReleased))) {
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
        Set<String> featureFluidIngressTargets = new HashSet<>();
        Set<String> featureProtectedFluidIngressTargets = new HashSet<>();
        Set<String> featureBoundaryFluidIngressTargets = new HashSet<>();
        Set<String> allFeatureFluidTargets = new HashSet<>();
        Set<String> allFeatureProtectedFluidTargets = new HashSet<>();
        Set<String> allFeatureBoundaryFluidTargets = new HashSet<>();
        for (FeatureDirectFluidTarget direct : featureDirectFluidTargets) {
            String key = voxelKey(direct.target());
            allFeatureFluidTargets.add(key);
            if (direct.protectedRoute()) allFeatureProtectedFluidTargets.add(key);
            if (direct.boundary()) allFeatureBoundaryFluidTargets.add(key);
        }
        for (FeatureFluidIngress ingress : featureFluidIngresses) {
            String key = voxelKey(ingress.target());
            featureFluidIngressTargets.add(key);
            allFeatureFluidTargets.add(key);
            if (ingress.protectedRoute()) {
                featureProtectedFluidIngressTargets.add(key);
                allFeatureProtectedFluidTargets.add(key);
            }
            if (ingress.boundary()) {
                featureBoundaryFluidIngressTargets.add(key);
                allFeatureBoundaryFluidTargets.add(key);
            }
        }
        String packagePlanDigest = chunkDigest("package-plan", observations, true);
        String arbitrationInputDigest = chunkDigest("arbitration-input", observations, false);
        String arbitrationPlanDigest = chunkDigest("arbitration-plan", observations, false);
        writeRegister(samples);
        if (hasFeatureAudit()) {
            writeFeatureFluidRegister();
        }
        if (isCarve() && !Files.isRegularFile(repairRegisterPath)) {
            writeRepairRegister();
        }
        String registerSha256;
        String featureFluidRegisterSha256 = "";
        String repairRegisterSha256 = "";
        try {
            registerSha256 = sha256(registerPath);
            if (hasFeatureAudit()) {
                featureFluidRegisterSha256 = sha256(featureFluidRegisterPath);
            }
            if (isCarve()) repairRegisterSha256 = sha256(repairRegisterPath);
        } catch (IOException exception) {
            throw new IllegalStateException("register digest failed", exception);
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
                && observations.size() == EXPECTED_CHUNKS
                && counts.planned == EXPECTED_PLANNED
                && counts.protectedRoute == EXPECTED_PROTECTED
                && counts.boundary == EXPECTED_BOUNDARY
                && counts.protectedAdmissible == EXPECTED_PROTECTED
                && counts.boundaryAdmissible == EXPECTED_BOUNDARY
                && counts.carve + counts.noop + counts.deferFluid + counts.skip
                        == counts.planned
                && counts.reject == 0
                && rejectedChunks == 0
                && realizableFraction >= MINIMUM_REALIZABLE_FRACTION
                && (!isPostgen()
                        || (naturalGeologyOverrides.size()
                                        == EXPECTED_NATURAL_GEOLOGY_OVERRIDES
                                && externalVoidBridge.size() == EXPECTED_EXTERNAL_VOID_BRIDGE
                                && externalVoidBridgeExactCount
                                        == EXPECTED_EXTERNAL_VOID_BRIDGE));
        boolean pass = commonPass
                && (!isCarve()
                        ? blockWriteCount == 0
                        : frozenTargets.size() == EXPECTED_PLANNED
                                && preemptiveMembrane.size() == EXPECTED_PREEMPTIVE_MEMBRANE
                                && preemptiveMembranePreserveCount
                                        == EXPECTED_PREEMPTIVE_MEMBRANE
                                && blockWriteCount
                                        == counts.carve - EXPECTED_PREEMPTIVE_MEMBRANE
                                && protectedClosureComplete
                                && quiescenceComplete
                                && ticketsReleased
                                && !lifecycleRollback
                                && observedAuditFeatureChunks.size() == auditChunks.size()
                                && postProtected == EXPECTED_PROTECTED
                                && postBoundary == EXPECTED_BOUNDARY
                                && fluidMembraneExactCount == fluidMembraneTargets.size()
                                && preemptiveMembraneExactCount
                                        == EXPECTED_PREEMPTIVE_MEMBRANE
                                && membraneStabilizers.size()
                                        == EXPECTED_MEMBRANE_STABILIZERS
                                && sourceCaps.size() == EXPECTED_SOURCE_CAPS
                                && sourceCapExactCount == EXPECTED_SOURCE_CAPS
                                && allFeatureProtectedFluidTargets.isEmpty()
                                && allFeatureBoundaryFluidTargets.isEmpty()
                                && (double) ordinaryLiveFluidTargetCount / counts.planned
                                        <= MAXIMUM_ORDINARY_FLUID_TARGET_FRACTION
                                && (double) counts.deferFluid / counts.planned
                                        <= MAXIMUM_DEFERRED_HOST_FLUID_TARGET_FRACTION
                                && postRealizedFraction >= MINIMUM_REALIZABLE_FRACTION);
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-" + RUN
                + "-multichunk-transaction-receipt.v1", true);
        field(output, "state", pass
                ? "PASS_FABRIC_MULTICHUNK_TRANSACTION_ARM"
                : "FAIL_FABRIC_MULTICHUNK_TRANSACTION_ARM", true);
        number(output, "run", RUN, true);
        field(output, "mode", mode.name(), true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        number(output, "target_chunk_count", targetChunks.size(), true);
        number(output, "observed_carve_chunk_count", observedCarveChunks.size(), true);
        number(output, "observed_feature_chunk_count", observedFeatureChunks.size(), true);
        number(output, "audit_chunk_count", auditChunks.size(), true);
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
        decimal(
                output,
                "deferred_host_fluid_target_fraction",
                counts.planned == 0 ? 0.0 : (double) counts.deferFluid / counts.planned,
                true);
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
        field(output, "feature_fluid_register_sha256", featureFluidRegisterSha256, true);
        field(output, "protected_closure_register_sha256", repairRegisterSha256, true);
        field(output, "frozen_binding_sha256", frozenBindingSha256, true);
        field(
                output,
                "preemptive_membrane_binding_sha256",
                preemptiveMembraneBindingSha256,
                true);
        field(output, "source_cap_binding_sha256", sourceCapBindingSha256, true);
        field(output, "natural_geology_binding_sha256", naturalGeologyBindingSha256, true);
        field(output, "protected_route_overlay_sha256", protectedRouteOverlaySha256, true);
        field(output, "external_void_bridge_sha256", externalVoidBridgeSha256, true);
        field(output, "membrane_stabilizer_binding_sha256",
                membraneStabilizerBindingSha256, true);
        number(output, "natural_geology_override_count", naturalGeologyOverrides.size(), true);
        number(output, "external_void_bridge_target_count", externalVoidBridge.size(), true);
        number(output, "external_void_bridge_exact_count", externalVoidBridgeExactCount, true);
        number(output, "membrane_stabilizer_target_count", membraneStabilizers.size(), true);
        number(output, "membrane_stabilizer_write_count", membraneStabilizerWriteCount, true);
        number(
                output,
                "scheduled_hydrology_fluid_tick_count",
                scheduledHydrologyFluidTickCount,
                true);
        number(
                output,
                "hydrology_tickable_chunk_count_before_reassertion",
                hydrologyTickableChunkCountBeforeReassertion,
                true);
        number(
                output,
                "hydrology_tickable_chunk_count_after_reassertion",
                hydrologyTickableChunkCountAfterReassertion,
                true);
        number(
                output,
                "target_local_queued_fluid_tick_count_after_quiescence",
                targetLocalQueuedFluidTickCountAfterQuiescence,
                true);
        number(output, "block_write_count", blockWriteCount, true);
        number(output, "protected_closure_write_count", repairs.size(), true);
        bool(output, "protected_closure_complete", protectedClosureComplete, true);
        number(output, "lifecycle_quiescence_ticks", LIFECYCLE_QUIESCENCE_TICKS, true);
        bool(output, "lifecycle_quiescence_complete", quiescenceComplete, true);
        bool(output, "lifecycle_rollback", lifecycleRollback, true);
        number(output, "lifecycle_rollback_write_count", rollbackWriteCount, true);
        number(output, "fluid_membrane_target_count", fluidMembraneTargets.size(), true);
        number(output, "fluid_membrane_write_count", fluidMembraneWriteCount, true);
        number(output, "fluid_membrane_exact_after_quiescence_count", fluidMembraneExactCount, true);
        number(output, "preemptive_membrane_target_count", preemptiveMembrane.size(), true);
        number(
                output,
                "preemptive_membrane_preserve_count",
                preemptiveMembranePreserveCount,
                true);
        number(
                output,
                "preemptive_membrane_restore_write_count",
                preemptiveMembraneRestoreWriteCount,
                true);
        number(
                output,
                "preemptive_membrane_exact_after_quiescence_count",
                preemptiveMembraneExactCount,
                true);
        number(output, "source_cap_target_count", sourceCaps.size(), true);
        number(output, "source_cap_write_count", sourceCapWriteCount, true);
        number(output, "source_cap_exact_after_quiescence_count", sourceCapExactCount, true);
        number(output, "host_hydrology_target_count", hostHydrologyTargets.size(), true);
        number(
                output,
                "host_hydrology_fluid_after_quiescence_count",
                hostHydrologyFluidCountAfterQuiescence,
                true);
        decimal(
                output,
                "ordinary_fluid_target_fraction",
                counts.planned == 0
                        ? 0.0
                        : (double) ordinaryLiveFluidTargetCount / counts.planned,
                true);
        number(
                output,
                "ordinary_live_fluid_target_count",
                ordinaryLiveFluidTargetCount,
                true);
        number(
                output,
                "total_write_count",
                blockWriteCount
                        + repairs.size()
                        + fluidMembraneWriteCount
                        + preemptiveMembraneRestoreWriteCount
                        + sourceCapWriteCount
                        + membraneStabilizerWriteCount
                        + rollbackWriteCount,
                true);
        number(output, "post_feature_traversable_count", postTraversable, true);
        number(output, "post_feature_protected_traversable_count", postProtected, true);
        number(output, "post_feature_boundary_traversable_count", postBoundary, true);
        decimal(output, "post_feature_realized_void_fraction", postRealizedFraction, true);
        bool(output, "feature_fluid_audit_complete", featureFluidAuditComplete, true);
        number(
                output,
                "feature_direct_fluid_target_count",
                featureDirectFluidTargets.size(),
                true);
        number(
                output,
                "feature_external_fluid_ingress_edge_count",
                featureFluidIngresses.size(),
                true);
        number(
                output,
                "feature_external_fluid_ingress_target_count",
                featureFluidIngressTargets.size(),
                true);
        number(
                output,
                "feature_protected_fluid_ingress_target_count",
                featureProtectedFluidIngressTargets.size(),
                true);
        number(
                output,
                "feature_boundary_fluid_ingress_target_count",
                featureBoundaryFluidIngressTargets.size(),
                true);
        number(output, "feature_all_fluid_target_count", allFeatureFluidTargets.size(), true);
        number(
                output,
                "feature_all_protected_fluid_target_count",
                allFeatureProtectedFluidTargets.size(),
                true);
        number(
                output,
                "feature_all_boundary_fluid_target_count",
                allFeatureBoundaryFluidTargets.size(),
                true);
        number(output, "biome_write_count", 0, true);
        number(output, "structure_write_count", 0, true);
        number(output, "feature_registry_write_count", 0, true);
        bool(output, "persistent_forced_chunk_state_used", false, true);
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
            Files.createDirectories(repairRegisterPath.getParent());
            Path temporary = repairRegisterPath.resolveSibling(
                    repairRegisterPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    repairRegisterPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("protected closure register write failed", exception);
        }
    }

    private static void writeFeatureFluidRegister() {
        StringBuilder output = new StringBuilder();
        output.append("kind\ttarget_x\ttarget_y\ttarget_z\tprotected_route")
                .append("\tregistered_boundary\tsource_x\tsource_y\tsource_z\tsource_state\n");
        for (FeatureDirectFluidTarget target : featureDirectFluidTargets) {
            output.append("DIRECT_TARGET\t")
                    .append(target.target().x()).append('\t')
                    .append(target.target().y()).append('\t')
                    .append(target.target().z()).append('\t')
                    .append(target.protectedRoute()).append('\t')
                    .append(target.boundary()).append('\t')
                    .append(target.target().x()).append('\t')
                    .append(target.target().y()).append('\t')
                    .append(target.target().z()).append('\t')
                    .append(target.state()).append('\n');
        }
        for (FeatureFluidIngress ingress : featureFluidIngresses) {
            output.append("EXTERNAL_EDGE\t")
                    .append(ingress.target().x()).append('\t')
                    .append(ingress.target().y()).append('\t')
                    .append(ingress.target().z()).append('\t')
                    .append(ingress.protectedRoute()).append('\t')
                    .append(ingress.boundary()).append('\t')
                    .append(ingress.source().x()).append('\t')
                    .append(ingress.source().y()).append('\t')
                    .append(ingress.source().z()).append('\t')
                    .append(ingress.sourceState()).append('\n');
        }
        try {
            Files.createDirectories(featureFluidRegisterPath.getParent());
            Path temporary = featureFluidRegisterPath.resolveSibling(
                    featureFluidRegisterPath.getFileName() + ".part");
            Files.writeString(temporary, output.toString(), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    featureFluidRegisterPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("feature fluid register write failed", exception);
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
            update(digest, Boolean.toString(sample.structureReserved()));
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

    private static int integerProperty(String name, int fallback) {
        String value = System.getProperty(name, "").trim();
        return value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    private static double doubleProperty(String name, double fallback) {
        String value = System.getProperty(name, "").trim();
        return value.isEmpty() ? fallback : Double.parseDouble(value);
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
