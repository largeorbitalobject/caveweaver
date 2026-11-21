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
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Thread-safe observation state and deterministic receipt for Karstogenesis run 201. */
public final class PlacementSelectorState {
    private static final int RUN = 201;
    private static final int MINIMUM_CALLBACK_CHUNKS = 4;
    private static final int CONTROL_CHUNK_COUNT = 12;
    private static final int CONTROL_MINIMUM_PLAN_SIZE = 64;
    private static final int TEMPLATE_MINIMUM_PLAN_SIZE = 256;
    private static final int CANDIDATE_COUNT = 49;
    private static final int BASE_X = 604;
    private static final int BASE_Y = 91;
    private static final int BASE_Z = 120;
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;
    private static final int[] OFFSETS = {0, 64, -64, 128, -128, 192, -192};

    private static final Set<String> populateNoiseReturnChunks = new HashSet<>();
    private static final Set<String> carveHeadChunks = new HashSet<>();
    private static final Set<String> carveTailChunks = new HashSet<>();
    private static final Set<String> featuresHeadChunks = new HashSet<>();
    private static final Set<String> featuresTailChunks = new HashSet<>();
    private static final Map<String, Integer> phaseProgress = new HashMap<>();
    private static final Set<String> completeOrderChunks = new HashSet<>();
    private static final Set<String> evaluatedControlChunks = new HashSet<>();
    private static final Set<String> evaluatedCandidateIds = new HashSet<>();
    private static final TreeMap<String, List<Sample>> controlSamplesByChunk = new TreeMap<>();
    private static final TreeMap<String, CandidateEvaluation> candidateEvaluations = new TreeMap<>();
    private static final List<String> errors = new ArrayList<>();

    private static Path receiptPath;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner basePlanner;
    private static List<KarstDeltaChunkReader.ChunkKey> controlChunks = List.of();
    private static List<CandidateSpec> candidates = List.of();
    private static Map<String, CandidateSpec> candidateByWorldChunk = Map.of();
    private static KarstDeltaChunkReader.ChunkKey templateChunk;
    private static int templatePlanSize;
    private static int templateProtectedCount;
    private static int templateBoundaryCount;
    private static boolean initialized;
    private static boolean serverStarted;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketsReleased;
    private static int blockRegistryEntryCount;
    private static int biomeRegistryEntryCount;
    private static int structureRegistryEntryCount;
    private static int baseStoneTagEntryCount;
    private static int serverTickCount;

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureReserved,
            String biomeIdentity) {}

    private record CandidateSpec(
            String id,
            int offsetX,
            int offsetZ,
            KarstDeltaChunkReader.Translation translation,
            KarstDeltaChunkReader.MinecraftIntentPlanner planner,
            KarstDeltaChunkReader.ChunkKey worldChunk) {}

    private record CandidateEvaluation(
            CandidateSpec spec,
            int plannedCount,
            int replaceableCount,
            int existingVoidCount,
            int fluidCount,
            int preserveCount,
            int structureReservedCount,
            int protectedCount,
            int boundaryCount,
            int protectedOrBoundaryConflictCount,
            String packagePlanSha256,
            String arbitrationInputSha256,
            KarstHostArbitration.Plan arbitration,
            boolean admitted) {}

    private PlacementSelectorState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String packageProperty = System.getProperty("geoworld.selector.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.selector.receipt", "").trim();
        if (packageProperty.isEmpty() || receiptProperty.isEmpty()) {
            throw new IllegalStateException("selector package or receipt property is empty");
        }
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        try {
            reader = KarstDeltaChunkReader.open(Path.of(packageProperty), true);
        } catch (IOException exception) {
            throw new IllegalStateException("package open failed", exception);
        }
        basePlanner = bind(new KarstDeltaChunkReader.Translation(BASE_X, BASE_Y, BASE_Z));
        controlChunks = selectControlChunks();
        selectTemplateChunk();
        candidates = buildCandidates();
        Map<String, CandidateSpec> byChunk = new HashMap<>();
        for (CandidateSpec candidate : candidates) {
            CandidateSpec previous = byChunk.put(chunkKey(candidate.worldChunk()), candidate);
            if (previous != null) {
                throw new IllegalStateException("candidate translations alias one world chunk");
            }
        }
        candidateByWorldChunk = Map.copyOf(byChunk);
        initialized = true;
        KarstogenesisFabricSelector.LOGGER.info(
                "Run 201 initialized control={} template={} plan={} protected={} boundary={} candidates={}",
                controlChunks.size(),
                chunkKey(templateChunk),
                templatePlanSize,
                templateProtectedCount,
                templateBoundaryCount,
                candidates.size());
    }

    private static KarstDeltaChunkReader.MinecraftIntentPlanner bind(
            KarstDeltaChunkReader.Translation translation) {
        return KarstDeltaChunkReader.MinecraftIntentPlanner.bind(
                reader,
                reader.cacheIdentitySha256(),
                translation,
                new KarstDeltaChunkReader.WorldBounds(
                        new KarstDeltaChunkReader.Voxel(-30_000_000, LOW_Y, -30_000_000),
                        new KarstDeltaChunkReader.Voxel(29_999_999, HIGH_Y, 29_999_999)));
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

    private static List<KarstDeltaChunkReader.ChunkKey> selectControlChunks() {
        List<KarstDeltaChunkReader.ChunkKey> selected = new ArrayList<>();
        KarstDeltaChunkReader.HostMinecraftRegistry registry = neutralRegistry();
        for (KarstDeltaChunkReader.ChunkKey key : basePlanner.candidateWorldChunks()) {
            KarstDeltaChunkReader.ChunkPlan plan =
                    basePlanner.planChunk(key.x(), key.z(), LOW_Y, HIGH_Y, registry);
            if (plan.state() == KarstDeltaChunkReader.PlanState.ACTIVE
                    && plan.planned().size() >= CONTROL_MINIMUM_PLAN_SIZE) {
                selected.add(key);
                if (selected.size() == CONTROL_CHUNK_COUNT) break;
            }
        }
        if (selected.size() != CONTROL_CHUNK_COUNT) {
            throw new IllegalStateException("control target selection is incomplete");
        }
        return List.copyOf(selected);
    }

    private static void selectTemplateChunk() {
        KarstDeltaChunkReader.HostMinecraftRegistry registry = neutralRegistry();
        KarstDeltaChunkReader.ChunkKey bestKey = null;
        int bestSize = -1;
        int bestProtected = -1;
        int bestBoundary = -1;
        for (KarstDeltaChunkReader.ChunkKey key : basePlanner.candidateWorldChunks()) {
            KarstDeltaChunkReader.ChunkPlan plan =
                    basePlanner.planChunk(key.x(), key.z(), LOW_Y, HIGH_Y, registry);
            if (plan.state() != KarstDeltaChunkReader.PlanState.ACTIVE
                    || plan.planned().size() < TEMPLATE_MINIMUM_PLAN_SIZE) {
                continue;
            }
            int protectedCount = 0;
            int boundaryCount = 0;
            for (KarstDeltaChunkReader.PlannedVoxel planned : plan.planned()) {
                if (planned.protectedRoute()) protectedCount++;
                if (planned.registeredBoundary()) boundaryCount++;
            }
            if (protectedCount == 0) continue;
            if (betterTemplate(
                    protectedCount,
                    boundaryCount,
                    plan.planned().size(),
                    key,
                    bestProtected,
                    bestBoundary,
                    bestSize,
                    bestKey)) {
                bestKey = key;
                bestSize = plan.planned().size();
                bestProtected = protectedCount;
                bestBoundary = boundaryCount;
            }
        }
        if (bestKey == null) throw new IllegalStateException("template target selection is empty");
        templateChunk = bestKey;
        templatePlanSize = bestSize;
        templateProtectedCount = bestProtected;
        templateBoundaryCount = bestBoundary;
    }

    private static boolean betterTemplate(
            int protectedCount,
            int boundaryCount,
            int size,
            KarstDeltaChunkReader.ChunkKey key,
            int bestProtected,
            int bestBoundary,
            int bestSize,
            KarstDeltaChunkReader.ChunkKey bestKey) {
        if (protectedCount != bestProtected) return protectedCount > bestProtected;
        if (boundaryCount != bestBoundary) return boundaryCount > bestBoundary;
        if (size != bestSize) return size > bestSize;
        if (bestKey == null || key.x() != bestKey.x()) return bestKey == null || key.x() < bestKey.x();
        return key.z() < bestKey.z();
    }

    private static List<CandidateSpec> buildCandidates() {
        List<int[]> offsets = new ArrayList<>();
        for (int offsetX : OFFSETS) {
            for (int offsetZ : OFFSETS) offsets.add(new int[] {offsetX, offsetZ});
        }
        offsets.sort(Comparator
                .comparingLong((int[] value) ->
                        (long) value[0] * value[0] + (long) value[1] * value[1])
                .thenComparingInt(value -> value[0])
                .thenComparingInt(value -> value[1]));
        List<CandidateSpec> result = new ArrayList<>();
        for (int[] offset : offsets) {
            int offsetX = offset[0];
            int offsetZ = offset[1];
            KarstDeltaChunkReader.Translation translation =
                    new KarstDeltaChunkReader.Translation(
                            BASE_X + offsetX, BASE_Y, BASE_Z + offsetZ);
            KarstDeltaChunkReader.MinecraftIntentPlanner planner = bind(translation);
            KarstDeltaChunkReader.ChunkKey worldChunk = new KarstDeltaChunkReader.ChunkKey(
                    templateChunk.x() + Math.floorDiv(offsetX, 16),
                    templateChunk.z() + Math.floorDiv(offsetZ, 16));
            result.add(new CandidateSpec(
                    offsetId(offsetX, offsetZ),
                    offsetX,
                    offsetZ,
                    translation,
                    planner,
                    worldChunk));
        }
        if (result.size() != CANDIDATE_COUNT) {
            throw new IllegalStateException("candidate lattice size differs from preregistration");
        }
        return List.copyOf(result);
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        if (!initialized) throw new IllegalStateException("selector is not initialized");
        blockRegistryEntryCount = Registries.BLOCK.size();
        biomeRegistryEntryCount = server.getRegistryManager().get(RegistryKeys.BIOME).size();
        structureRegistryEntryCount =
                server.getRegistryManager().get(RegistryKeys.STRUCTURE).size();
        baseStoneTagEntryCount = (int) Registries.BLOCK.streamEntries()
                .filter(entry -> entry.isIn(BlockTags.BASE_STONE_OVERWORLD))
                .count();
        Set<String> requested = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : controlChunks) {
            requestTicket(server, key, requested);
        }
        for (CandidateSpec candidate : candidates) {
            requestTicket(server, candidate.worldChunk(), requested);
        }
        serverStarted = true;
        attemptReceipt();
    }

    private static void requestTicket(
            MinecraftServer server,
            KarstDeltaChunkReader.ChunkKey key,
            Set<String> requested) {
        if (requested.add(chunkKey(key))) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), true);
        }
    }

    public static synchronized void onServerTick(MinecraftServer server) {
        serverTickCount++;
        attemptReceipt();
        if (receiptWritten && !stopRequested) {
            releaseTickets(server);
            stopRequested = true;
            KarstogenesisFabricSelector.LOGGER.info(
                    "Run 201 receipt written, stopping disposable server");
            server.stop(false);
        } else if (!receiptWritten && serverTickCount % 200 == 0) {
            logGateStatus();
        } else if (!receiptWritten && serverTickCount == 1000) {
            releaseTickets(server);
            stopRequested = true;
            KarstogenesisFabricSelector.LOGGER.error(
                    "Run 201 gate remained incomplete, stopping disposable server");
            server.stop(false);
        }
    }

    private static void releaseTickets(MinecraftServer server) {
        if (ticketsReleased) return;
        Set<String> released = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : controlChunks) {
            releaseTicket(server, key, released);
        }
        for (CandidateSpec candidate : candidates) {
            releaseTicket(server, candidate.worldChunk(), released);
        }
        ticketsReleased = true;
    }

    private static void releaseTicket(
            MinecraftServer server,
            KarstDeltaChunkReader.ChunkKey key,
            Set<String> released) {
        if (released.add(chunkKey(key))) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), false);
        }
    }

    private static void logGateStatus() {
        KarstogenesisFabricSelector.LOGGER.info(
                "Run 201 gate populate={} carve-head={} carve-tail={} features-head={} "
                        + "features-tail={} complete={} controls={}/{} candidates={}/{} errors={}",
                populateNoiseReturnChunks.size(),
                carveHeadChunks.size(),
                carveTailChunks.size(),
                featuresHeadChunks.size(),
                featuresTailChunks.size(),
                completeOrderChunks.size(),
                evaluatedControlChunks.size(),
                CONTROL_CHUNK_COUNT,
                evaluatedCandidateIds.size(),
                CANDIDATE_COUNT,
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
        try {
            if (isControl(chunk.getPos()) && evaluatedControlChunks.add(key)) {
                controlSamplesByChunk.put(
                        key,
                        observePlan(basePlanner, world, chunk, structureAccessor).samples());
            }
            CandidateSpec candidate = candidateByWorldChunk.get(key);
            if (candidate != null && evaluatedCandidateIds.add(candidate.id())) {
                ObservedPlan observed =
                        observePlan(candidate.planner(), world, chunk, structureAccessor);
                candidateEvaluations.put(
                        candidate.id(),
                        evaluateCandidate(candidate, observed));
            }
        } catch (RuntimeException exception) {
            String error = errorSummary(exception);
            errors.add(error);
            KarstogenesisFabricSelector.LOGGER.error(
                    "Run 201 target evaluation failed for chunk {}", key, exception);
        }
    }

    public static synchronized void observeFeaturesHead(Chunk chunk) {
        String key = chunkKey(chunk.getPos());
        featuresHeadChunks.add(key);
        advance(key, 3, 4);
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

    private static boolean isControl(ChunkPos position) {
        for (KarstDeltaChunkReader.ChunkKey key : controlChunks) {
            if (key.x() == position.x && key.z() == position.z) return true;
        }
        return false;
    }

    private record ObservedPlan(
            String packagePlanSha256,
            List<Sample> samples) {}

    private static ObservedPlan observePlan(
            KarstDeltaChunkReader.MinecraftIntentPlanner planner,
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
        List<Sample> samples = new ArrayList<>(plan.planned().size());
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
            samples.add(new Sample(
                    planned,
                    classify(state),
                    reserved,
                    biomeIdentity(chunk, position)));
        }
        samples.sort(Comparator.comparing(value -> voxelKey(value.planned().worldVoxel())));
        return new ObservedPlan(plan.planSha256(), List.copyOf(samples));
    }

    private static CandidateEvaluation evaluateCandidate(
            CandidateSpec spec,
            ObservedPlan observed) {
        Counts counts = counts(observed.samples());
        KarstHostArbitration.Plan arbitration = arbitrate(observed.samples());
        boolean admitted = observed.samples().size() >= 256
                && counts.replaceable >= 64
                && counts.protectedRoute >= 1
                && counts.structureReserved == 0
                && counts.fluid == 0
                && counts.preserve == 0
                && counts.protectedOrBoundaryConflict == 0
                && arbitration.state() == KarstHostArbitration.PlanState.ACTIVE;
        return new CandidateEvaluation(
                spec,
                observed.samples().size(),
                counts.replaceable,
                counts.existingVoid,
                counts.fluid,
                counts.preserve,
                counts.structureReserved,
                counts.protectedRoute,
                counts.boundary,
                counts.protectedOrBoundaryConflict,
                observed.packagePlanSha256(),
                inputDigest(observed.samples()),
                arbitration,
                admitted);
    }

    private static final class Counts {
        int replaceable;
        int existingVoid;
        int fluid;
        int preserve;
        int structureReserved;
        int protectedRoute;
        int boundary;
        int protectedOrBoundaryConflict;
    }

    private static Counts counts(List<Sample> samples) {
        Counts result = new Counts();
        for (Sample sample : samples) {
            switch (sample.hostState()) {
                case REPLACEABLE_SOLID -> result.replaceable++;
                case EXISTING_VOID -> result.existingVoid++;
                case FLUID -> result.fluid++;
                case PRESERVE, UNKNOWN -> result.preserve++;
            }
            if (sample.structureReserved()) result.structureReserved++;
            if (sample.planned().protectedRoute()) result.protectedRoute++;
            if (sample.planned().registeredBoundary()) result.boundary++;
            if ((sample.planned().protectedRoute() || sample.planned().registeredBoundary())
                    && (sample.structureReserved()
                            || sample.hostState() == KarstHostArbitration.HostState.PRESERVE
                            || sample.hostState() == KarstHostArbitration.HostState.UNKNOWN)) {
                result.protectedOrBoundaryConflict++;
            }
        }
        return result;
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
        List<Sample> controlSamples = new ArrayList<>();
        for (List<Sample> values : controlSamplesByChunk.values()) {
            controlSamples.addAll(values);
        }
        controlSamples.sort(Comparator.comparing(value -> voxelKey(value.planned().worldVoxel())));
        Counts controlCounts = counts(controlSamples);
        KarstHostArbitration.Plan controlArbitration = arbitrate(controlSamples);
        CandidateEvaluation selected = null;
        int admittedCount = 0;
        for (CandidateSpec spec : candidates) {
            CandidateEvaluation evaluation = candidateEvaluations.get(spec.id());
            if (evaluation != null && evaluation.admitted()) {
                admittedCount++;
                if (selected == null) selected = evaluation;
            }
        }
        boolean pass = controlArbitration.state() == KarstHostArbitration.PlanState.REJECTED
                && controlCounts.structureReserved >= 1
                && controlCounts.protectedOrBoundaryConflict >= 1
                && selected != null
                && errors.isEmpty();
        String receipt = receiptJson(
                controlSamples,
                controlCounts,
                controlArbitration,
                selected,
                admittedCount,
                pass);
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
                && basePlanner.bindingState() == KarstDeltaChunkReader.PlanState.ACTIVE
                && controlChunks.size() == CONTROL_CHUNK_COUNT
                && candidates.size() == CANDIDATE_COUNT
                && evaluatedControlChunks.size() == CONTROL_CHUNK_COUNT
                && controlSamplesByChunk.size() == CONTROL_CHUNK_COUNT
                && evaluatedCandidateIds.size() == CANDIDATE_COUNT
                && candidateEvaluations.size() == CANDIDATE_COUNT
                && populateNoiseReturnChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && carveHeadChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && carveTailChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && featuresHeadChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && featuresTailChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && completeOrderChunks.size() >= MINIMUM_CALLBACK_CHUNKS
                && blockRegistryEntryCount > 0
                && biomeRegistryEntryCount > 0
                && structureRegistryEntryCount > 0
                && baseStoneTagEntryCount > 0
                && errors.isEmpty();
    }

    private static String receiptJson(
            List<Sample> controlSamples,
            Counts controlCounts,
            KarstHostArbitration.Plan controlArbitration,
            CandidateEvaluation selected,
            int admittedCount,
            boolean pass) {
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-201-fabric-conflict-free-placement-receipt.v1", true);
        field(output, "state", pass
                ? "PASS_FABRIC_CONFLICT_FREE_PLACEMENT_SELECTOR"
                : "LIMIT_FABRIC_CONFLICT_FREE_PLACEMENT_SELECTOR", true);
        number(output, "run", RUN, true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        number(output, "block_registry_entry_count", blockRegistryEntryCount, true);
        number(output, "biome_registry_entry_count", biomeRegistryEntryCount, true);
        number(output, "structure_registry_entry_count", structureRegistryEntryCount, true);
        number(output, "base_stone_tag_entry_count", baseStoneTagEntryCount, true);
        number(output, "callback_phase_count", 5, true);
        number(output, "complete_same_chunk_phase_order_count", MINIMUM_CALLBACK_CHUNKS, true);
        number(output, "control_chunk_count", controlChunks.size(), true);
        number(output, "control_sample_count", controlSamples.size(), true);
        number(output, "control_structure_reserved_count", controlCounts.structureReserved, true);
        number(output, "control_protected_or_boundary_rejection_count",
                controlCounts.protectedOrBoundaryConflict, true);
        field(output, "control_arbitration_state", controlArbitration.state().name(), true);
        field(output, "control_arbitration_input_sha256", inputDigest(controlSamples), true);
        field(output, "control_arbitration_plan_sha256", controlArbitration.planSha256(), true);
        field(output, "template_chunk", chunkKey(templateChunk), true);
        number(output, "template_plan_size", templatePlanSize, true);
        number(output, "template_protected_count", templateProtectedCount, true);
        number(output, "template_boundary_count", templateBoundaryCount, true);
        number(output, "search_candidate_count", candidates.size(), true);
        number(output, "admitted_candidate_count", admittedCount, true);
        if (selected == null) {
            field(output, "selected_candidate_id", "", true);
            number(output, "selected_offset_x_blocks", 0, true);
            number(output, "selected_offset_z_blocks", 0, true);
            field(output, "selected_world_chunk", "", true);
            number(output, "selected_planned_voxel_count", 0, true);
            number(output, "selected_replaceable_solid_count", 0, true);
            number(output, "selected_existing_void_count", 0, true);
            number(output, "selected_protected_route_count", 0, true);
            field(output, "selected_package_plan_sha256", "", true);
            field(output, "selected_arbitration_input_sha256", "", true);
            field(output, "selected_arbitration_plan_sha256", "", true);
        } else {
            field(output, "selected_candidate_id", selected.spec().id(), true);
            number(output, "selected_offset_x_blocks", selected.spec().offsetX(), true);
            number(output, "selected_offset_z_blocks", selected.spec().offsetZ(), true);
            field(output, "selected_world_chunk", chunkKey(selected.spec().worldChunk()), true);
            number(output, "selected_planned_voxel_count", selected.plannedCount(), true);
            number(output, "selected_replaceable_solid_count", selected.replaceableCount(), true);
            number(output, "selected_existing_void_count", selected.existingVoidCount(), true);
            number(output, "selected_protected_route_count", selected.protectedCount(), true);
            field(output, "selected_package_plan_sha256", selected.packagePlanSha256(), true);
            field(output, "selected_arbitration_input_sha256",
                    selected.arbitrationInputSha256(), true);
            field(output, "selected_arbitration_plan_sha256",
                    selected.arbitration().planSha256(), true);
        }
        decimal(output, "preserve_existing_biome_fraction", 1.0, true);
        output.append("\"candidate_summaries\":[");
        int summaryIndex = 0;
        for (CandidateSpec spec : candidates) {
            CandidateEvaluation value = candidateEvaluations.get(spec.id());
            if (summaryIndex++ > 0) output.append(',');
            output.append('{');
            field(output, "id", spec.id(), true);
            number(output, "offset_x", spec.offsetX(), true);
            number(output, "offset_z", spec.offsetZ(), true);
            field(output, "world_chunk", chunkKey(spec.worldChunk()), true);
            number(output, "planned", value.plannedCount(), true);
            number(output, "replaceable", value.replaceableCount(), true);
            number(output, "existing_void", value.existingVoidCount(), true);
            number(output, "fluid", value.fluidCount(), true);
            number(output, "preserve", value.preserveCount(), true);
            number(output, "structure_reserved", value.structureReservedCount(), true);
            number(output, "protected", value.protectedCount(), true);
            number(output, "boundary", value.boundaryCount(), true);
            number(output, "protected_or_boundary_conflict",
                    value.protectedOrBoundaryConflictCount(), true);
            field(output, "arbitration_state", value.arbitration().state().name(), true);
            bool(output, "admitted", value.admitted(), false);
            output.append('}');
        }
        output.append("],");
        number(output, "temporary_chunk_ticket_count", uniqueTicketCount(), true);
        bool(output, "persistent_forced_chunk_state_used", false, true);
        number(output, "probe_block_write_count", 0, true);
        number(output, "chunk_callback_error_count", errors.size(), false);
        output.append('}').append('\n');
        return output.toString();
    }

    private static int uniqueTicketCount() {
        Set<String> keys = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : controlChunks) keys.add(chunkKey(key));
        for (CandidateSpec candidate : candidates) keys.add(chunkKey(candidate.worldChunk()));
        return keys.size();
    }

    private static String inputDigest(List<Sample> samples) {
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

    private static String chunkKey(KarstDeltaChunkReader.ChunkKey position) {
        return position.x() + "," + position.z();
    }

    private static String voxelKey(KarstDeltaChunkReader.Voxel voxel) {
        return String.format(Locale.ROOT, "%09d,%05d,%09d", voxel.x(), voxel.y(), voxel.z());
    }

    private static String offsetId(int offsetX, int offsetZ) {
        return String.format(Locale.ROOT, "%+05d,%+05d", offsetX, offsetZ);
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
