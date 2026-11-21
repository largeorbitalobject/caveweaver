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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;

/** Read-only carver-replaceability correction for Karstogenesis run 217. */
public final class ProviderCarverCorrectionState {
    private static final int RUN = 217;
    private static final int LOW_Y = -64;
    private static final int HIGH_Y = 319;
    private static final int EXPECTED_CHUNKS = 59;
    private static final int EXPECTED_PLANNED = 23328;
    private static final int EXPECTED_PROTECTED = 5395;
    private static final int EXPECTED_BOUNDARY = 42;
    private static final int EXPECTED_PROVIDER_TARGETS = 22154;
    private static final int EXPECTED_PROVIDER_PROTECTED = 4959;
    private static final int EXPECTED_PROVIDER_BOUNDARY = 29;
    private static final int EXPECTED_PROVIDER_QUART_CELLS = 1739;
    private static final int SAVED_AUDIT_TICK = 400;
    private static final double MINIMUM_REALIZABLE_FRACTION = 0.95;
    private static final double MINIMUM_PROVIDER_TARGET_FRACTION = 0.90;
    private static final int MINIMUM_PROVIDER_PROTECTED = 4500;
    private static final int MINIMUM_PROVIDER_QUART_CELLS = 1600;

    private record Sample(
            KarstDeltaChunkReader.PlannedVoxel planned,
            KarstHostArbitration.HostState hostState,
            boolean structureUnionReserved,
            boolean structurePieceReserved,
            boolean blockEntity,
            String biomeIdentity,
            String priorBlockState,
            String action) {}

    private record ChunkObservation(
            KarstDeltaChunkReader.ChunkKey key,
            String packagePlanSha256,
            String arbitrationInputSha256,
            String arbitrationPlanSha256,
            boolean rejected,
            List<Sample> samples) {}

    private static final class Counts {
        int planned;
        int replaceable;
        int existingVoid;
        int fluid;
        int preserve;
        int structureUnionReserved;
        int structurePieceReserved;
        int protectedPieceIntersection;
        int boundaryPieceIntersection;
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

    private static final class SavedCounts {
        int providerTargets;
        int providerProtected;
        int providerBoundary;
        int providerBlocks;
        int air;
        int fluid;
        int emptyCollision;
        final Set<String> providerQuartCells = new TreeSet<>();
        final Set<String> providerBiomes = new TreeSet<>();
        final Set<String> uniqueBlocks = new TreeSet<>();
        final Set<String> uniqueBiomes = new TreeSet<>();
    }

    private static Path receiptPath;
    private static Path registerPath;
    private static Path savedRegisterPath;
    private static String candidateId;
    private static Set<String> expectedMods = Set.of();
    private static String primaryMod;
    private static String providerNamespace;
    private static String expectedProviderBiome;
    private static int translationX;
    private static int translationY;
    private static int translationZ;
    private static KarstDeltaChunkReader reader;
    private static KarstDeltaChunkReader.MinecraftIntentPlanner planner;
    private static List<KarstDeltaChunkReader.ChunkKey> targetChunks = List.of();
    private static Set<String> targetChunkKeys = Set.of();
    private static final Set<String> observedChunks = new HashSet<>();
    private static final TreeMap<String, ChunkObservation> observations = new TreeMap<>();
    private static final List<String> errors = new ArrayList<>();
    private static boolean initialized;
    private static boolean receiptWritten;
    private static boolean stopRequested;
    private static boolean ticketsReleased;
    private static int serverTickCount;

    private ProviderCarverCorrectionState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String packageProperty = System.getProperty("geoworld.correction217.package", "").trim();
        String receiptProperty = System.getProperty("geoworld.correction217.receipt", "").trim();
        String registerProperty = System.getProperty("geoworld.correction217.register", "").trim();
        String savedRegisterProperty = System.getProperty("geoworld.correction217.savedRegister", "").trim();
        String expectedModProperty = System.getProperty("geoworld.correction217.expectedMods", "").trim();
        primaryMod = System.getProperty("geoworld.correction217.primaryMod", "").trim();
        providerNamespace = System.getProperty("geoworld.correction217.namespace", "").trim();
        expectedProviderBiome = System.getProperty("geoworld.correction217.expectedBiome", "").trim();
        candidateId = System.getProperty("geoworld.correction217.candidate", "").trim();
        if (packageProperty.isEmpty()
                || receiptProperty.isEmpty()
                || registerProperty.isEmpty()
                || savedRegisterProperty.isEmpty()
                || primaryMod.isEmpty()
                || providerNamespace.isEmpty()
                || expectedProviderBiome.isEmpty()
                || candidateId.isEmpty()) {
            throw new IllegalStateException("carver correction input or output property is empty");
        }
        expectedMods = expectedModProperty.isEmpty()
                ? Set.of()
                : Set.of(expectedModProperty.split(","));
        translationX = integerProperty("geoworld.correction217.translationX");
        translationY = integerProperty("geoworld.correction217.translationY");
        translationZ = integerProperty("geoworld.correction217.translationZ");
        receiptPath = Path.of(receiptProperty).toAbsolutePath().normalize();
        registerPath = Path.of(registerProperty).toAbsolutePath().normalize();
        savedRegisterPath = Path.of(savedRegisterProperty).toAbsolutePath().normalize();
        try {
            reader = KarstDeltaChunkReader.open(Path.of(packageProperty), true);
        } catch (IOException exception) {
            throw new IllegalStateException("package open failed", exception);
        }
        planner = KarstDeltaChunkReader.MinecraftIntentPlanner.bind(
                reader,
                reader.cacheIdentitySha256(),
                new KarstDeltaChunkReader.Translation(
                        translationX, translationY, translationZ),
                new KarstDeltaChunkReader.WorldBounds(
                        new KarstDeltaChunkReader.Voxel(-30_000_000, LOW_Y, -30_000_000),
                        new KarstDeltaChunkReader.Voxel(29_999_999, HIGH_Y, 29_999_999)));
        require(
                planner.bindingState() == KarstDeltaChunkReader.PlanState.ACTIVE,
                "carver correction planner binding is inactive");
        targetChunks = selectTargetChunks();
        require(targetChunks.size() == EXPECTED_CHUNKS, "target chunk count differs");
        Set<String> keys = new HashSet<>();
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) keys.add(chunkKey(key));
        targetChunkKeys = Set.copyOf(keys);
        initialized = true;
        KarstogenesisFabricProviderCarverCorrection.LOGGER.info(
                "Run 217 initialized candidate={} translation={},{},{} chunks={}",
                candidateId,
                translationX,
                translationY,
                translationZ,
                targetChunks.size());
    }

    private static int integerProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (value.isEmpty()) throw new IllegalStateException(name + " is empty");
        return Integer.parseInt(value);
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

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "carver correction is not initialized");
        for (String mod : expectedMods) {
            require(FabricLoader.getInstance().isModLoaded(mod), "expected mod is not loaded: " + mod);
        }
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
            server.getOverworld().getChunkManager().setChunkForced(
                    new ChunkPos(key.x(), key.z()), true);
        }
    }

    public static synchronized void onServerTick(MinecraftServer server) {
        serverTickCount++;
        if (!receiptWritten
                && observedChunks.size() == EXPECTED_CHUNKS
                && serverTickCount >= SAVED_AUDIT_TICK) {
            writeOutputs(server.getOverworld());
        }
        if (receiptWritten && !stopRequested) {
            releaseTickets(server);
            stopRequested = true;
            KarstogenesisFabricProviderCarverCorrection.LOGGER.info(
                    "Run 217 receipt written, stopping disposable server");
            server.stop(false);
        } else if (!receiptWritten && serverTickCount % 200 == 0) {
            KarstogenesisFabricProviderCarverCorrection.LOGGER.info(
                    "Run 217 gate candidate={} chunks={}/{} errors={}",
                    candidateId,
                    observedChunks.size(),
                    EXPECTED_CHUNKS,
                    errors.size());
        } else if (!receiptWritten && serverTickCount == 6000) {
            errors.add("timeout-before-complete-receipt");
            writeOutputs(server.getOverworld());
        }
    }

    private static void releaseTickets(MinecraftServer server) {
        if (ticketsReleased) return;
        for (KarstDeltaChunkReader.ChunkKey key : targetChunks) {
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
        if (!targetChunkKeys.contains(key) || !observedChunks.add(key)) return;
        try {
            observations.put(key, observePlan(world, chunk, structureAccessor));
        } catch (RuntimeException exception) {
            errors.add(key + ":" + errorSummary(exception));
            KarstogenesisFabricProviderCarverCorrection.LOGGER.error(
                    "Run 217 observation failed for {}", key, exception);
        }
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
                samples.add(withAction(
                        raw.get(index), arbitration.decisions().get(index).action().name()));
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
            if (sample.structureUnionReserved()) result.structureUnionReserved++;
            if (sample.structurePieceReserved()) result.structurePieceReserved++;
            if (sample.blockEntity()) result.blockEntity++;
            if (sample.planned().protectedRoute()) result.protectedRoute++;
            if (sample.planned().registeredBoundary()) result.boundary++;
            if (sample.structurePieceReserved() && sample.planned().protectedRoute()) {
                result.protectedPieceIntersection++;
            }
            if (sample.structurePieceReserved() && sample.planned().registeredBoundary()) {
                result.boundaryPieceIntersection++;
            }
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

    private static void writeOutputs(ServerWorld world) {
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
        double protectedAdmissibleFraction = counts.protectedRoute == 0
                ? 0.0
                : (double) counts.protectedAdmissible / counts.protectedRoute;
        double boundaryAdmissibleFraction = counts.boundary == 0
                ? 0.0
                : (double) counts.boundaryAdmissible / counts.boundary;
        double realizableFraction = counts.planned == 0
                ? 0.0
                : (double) (counts.carve + counts.noop) / counts.planned;
        SavedCounts saved = auditSavedContent(world, samples);
        double providerTargetFraction = counts.planned == 0
                ? 0.0
                : (double) saved.providerTargets / counts.planned;
        int primaryLoadedCount = FabricLoader.getInstance().isModLoaded(primaryMod) ? 1 : 0;
        boolean admitted = errors.isEmpty()
                && observations.size() == EXPECTED_CHUNKS
                && counts.planned == EXPECTED_PLANNED
                && counts.protectedRoute == EXPECTED_PROTECTED
                && counts.boundary == EXPECTED_BOUNDARY
                && protectedAdmissibleFraction == 1.0
                && boundaryAdmissibleFraction == 1.0
                && realizableFraction >= MINIMUM_REALIZABLE_FRACTION
                && rejectedChunks == 0
                && counts.protectedPieceIntersection == 0
                && counts.boundaryPieceIntersection == 0
                && providerTargetFraction >= MINIMUM_PROVIDER_TARGET_FRACTION
                && saved.providerTargets == EXPECTED_PROVIDER_TARGETS
                && saved.providerProtected == EXPECTED_PROVIDER_PROTECTED
                && saved.providerBoundary == EXPECTED_PROVIDER_BOUNDARY
                && saved.providerQuartCells.size() == EXPECTED_PROVIDER_QUART_CELLS
                && saved.providerProtected >= MINIMUM_PROVIDER_PROTECTED
                && saved.providerQuartCells.size() >= MINIMUM_PROVIDER_QUART_CELLS
                && saved.providerBiomes.equals(Set.of(expectedProviderBiome))
                && saved.providerBlocks >= 1
                && primaryLoadedCount == 1;
        writeRegister(samples);
        String registerSha;
        String savedRegisterSha;
        try {
            registerSha = sha256(registerPath);
            savedRegisterSha = sha256(savedRegisterPath);
        } catch (IOException exception) {
            throw new IllegalStateException("register digest failed", exception);
        }
        StringBuilder output = new StringBuilder();
        output.append('{');
        field(output, "schema_version", "geoworld.karstogenesis-217-carver-replaceability-correction-receipt.v1", true);
        field(output, "state", admitted
                ? "PASS_CARVER_REPLACEABILITY_CORRECTION"
                : "FAIL_CARVER_REPLACEABILITY_CORRECTION", true);
        bool(output, "admitted", admitted, true);
        number(output, "run", RUN, true);
        field(output, "candidate_id", candidateId, true);
        number(output, "translation_x", translationX, true);
        number(output, "translation_y", translationY, true);
        number(output, "translation_z", translationZ, true);
        field(output, "minecraft_version", "1.21.1", true);
        field(output, "package_cache_identity_sha256", reader.cacheIdentitySha256(), true);
        number(output, "target_chunk_count", targetChunks.size(), true);
        number(output, "observed_chunk_count", observedChunks.size(), true);
        number(output, "planned_voxel_count", counts.planned, true);
        number(output, "replaceable_solid_count", counts.replaceable, true);
        number(output, "existing_void_count", counts.existingVoid, true);
        number(output, "fluid_count", counts.fluid, true);
        number(output, "preserve_count", counts.preserve, true);
        number(output, "structure_union_reserved_count", counts.structureUnionReserved, true);
        number(output, "structure_piece_reserved_count", counts.structurePieceReserved, true);
        number(output, "protected_piece_intersection_count", counts.protectedPieceIntersection, true);
        number(output, "boundary_piece_intersection_count", counts.boundaryPieceIntersection, true);
        number(output, "block_entity_target_count", counts.blockEntity, true);
        number(output, "protected_route_count", counts.protectedRoute, true);
        number(output, "registered_boundary_count", counts.boundary, true);
        number(output, "carve_action_count", counts.carve, true);
        number(output, "existing_void_noop_count", counts.noop, true);
        number(output, "deferred_fluid_count", counts.deferFluid, true);
        number(output, "skipped_host_geometry_count", counts.skip, true);
        number(output, "rejected_action_count", counts.reject, true);
        number(output, "rejected_chunk_count", rejectedChunks, true);
        decimal(output, "protected_admissible_fraction", protectedAdmissibleFraction, true);
        decimal(output, "boundary_admissible_fraction", boundaryAdmissibleFraction, true);
        decimal(output, "realizable_void_fraction", realizableFraction, true);
        field(output, "provider_namespace", providerNamespace, true);
        number(output, "primary_mod_loaded_count", primaryLoadedCount, true);
        number(output, "saved_provider_target_count", saved.providerTargets, true);
        decimal(output, "saved_provider_target_fraction", providerTargetFraction, true);
        number(output, "saved_provider_protected_count", saved.providerProtected, true);
        number(output, "saved_provider_boundary_count", saved.providerBoundary, true);
        number(output, "saved_provider_quart_cell_count", saved.providerQuartCells.size(), true);
        number(output, "saved_provider_unique_biome_count", saved.providerBiomes.size(), true);
        field(output, "saved_provider_biome_ids", String.join(",", saved.providerBiomes), true);
        number(output, "saved_provider_block_count", saved.providerBlocks, true);
        number(output, "saved_air_count", saved.air, true);
        number(output, "saved_fluid_count", saved.fluid, true);
        number(output, "saved_empty_collision_count", saved.emptyCollision, true);
        number(output, "saved_unique_block_count", saved.uniqueBlocks.size(), true);
        number(output, "saved_unique_biome_count", saved.uniqueBiomes.size(), true);
        field(output, "package_plan_sha256", chunkDigest("package-plan", true), true);
        field(output, "arbitration_input_sha256", chunkDigest("arbitration-input", false), true);
        field(output, "arbitration_plan_sha256", chunkDigest("arbitration-plan", false), true);
        field(output, "target_register_sha256", registerSha, true);
        field(output, "saved_content_register_sha256", savedRegisterSha, true);
        number(output, "block_write_count", 0, true);
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

    private static SavedCounts auditSavedContent(ServerWorld world, List<Sample> samples) {
        SavedCounts counts = new SavedCounts();
        StringBuilder output = new StringBuilder();
        output.append("x\ty\tz\tprotected_route\tregistered_boundary\tblock_id\tbiome_id\tair\tfluid\tempty_collision\tfull_state\n");
        for (Sample sample : samples) {
            KarstDeltaChunkReader.PlannedVoxel planned = sample.planned();
            KarstDeltaChunkReader.Voxel voxel = planned.worldVoxel();
            BlockPos position = blockPos(voxel);
            BlockState state = world.getBlockState(position);
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            String biomeId = biomeIdentity(world.getChunk(position), position);
            boolean air = state.isAir();
            boolean fluid = !state.getFluidState().isEmpty();
            boolean emptyCollision = state.getCollisionShape(world, position).isEmpty();
            counts.uniqueBlocks.add(blockId);
            counts.uniqueBiomes.add(biomeId);
            if (blockId.startsWith(providerNamespace + ":")) counts.providerBlocks++;
            if (air) counts.air++;
            if (fluid) counts.fluid++;
            if (emptyCollision) counts.emptyCollision++;
            if (biomeId.startsWith(providerNamespace + ":")) {
                counts.providerTargets++;
                if (planned.protectedRoute()) counts.providerProtected++;
                if (planned.registeredBoundary()) counts.providerBoundary++;
                counts.providerBiomes.add(biomeId);
                counts.providerQuartCells.add(
                        Math.floorDiv(voxel.x(), 4) + ","
                                + Math.floorDiv(voxel.y(), 4) + ","
                                + Math.floorDiv(voxel.z(), 4));
            }
            output.append(voxel.x()).append('\t')
                    .append(voxel.y()).append('\t')
                    .append(voxel.z()).append('\t')
                    .append(planned.protectedRoute()).append('\t')
                    .append(planned.registeredBoundary()).append('\t')
                    .append(blockId).append('\t')
                    .append(biomeId).append('\t')
                    .append(air).append('\t')
                    .append(fluid).append('\t')
                    .append(emptyCollision).append('\t')
                    .append(state).append('\n');
        }
        writeAtomic(savedRegisterPath, output.toString(), "saved content register");
        return counts;
    }

    private static void writeAtomic(Path path, String text, String label) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".part");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException(label + " write failed", exception);
        }
    }

    private static String chunkDigest(String label, boolean packageDigest) {
        MessageDigest digest = digest();
        update(digest, label);
        for (Map.Entry<String, ChunkObservation> entry : observations.entrySet()) {
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
            update(digest, voxelKey(sample.planned().worldVoxel()));
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
