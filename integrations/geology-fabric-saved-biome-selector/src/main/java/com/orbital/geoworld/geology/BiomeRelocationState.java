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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.chunk.Chunk;

/** Read-only direct shortlist and saved-biome selector for Karstogenesis run 215. */
public final class BiomeRelocationState {
    private static final int EXPECTED_TARGETS = 23328;
    private static final int EXPECTED_PROTECTED = 5395;
    private static final int EXPECTED_BOUNDARY = 42;
    private static final int EXPECTED_QUART_CELLS = 1868;
    private static final int EXPECTED_CANDIDATES = 4225;
    private static final int SHORTLIST_SIZE = 4;
    private static final int EXPECTED_FORCED_CHUNKS = 236;
    private static final int MINIMUM_DELTA = -4096;
    private static final int MAXIMUM_DELTA = 4096;
    private static final int DELTA_STEP = 128;
    private static final int AUDIT_TICK = 400;

    private record Target(int x, int y, int z, boolean protectedRoute, boolean boundary) {}

    private record QuartKey(int x, int y, int z) implements Comparable<QuartKey> {
        @Override
        public int compareTo(QuartKey other) {
            int result = Integer.compare(x, other.x);
            if (result != 0) return result;
            result = Integer.compare(y, other.y);
            if (result != 0) return result;
            return Integer.compare(z, other.z);
        }
    }

    private static final class QuartWeight {
        int targets;
        int protectedTargets;
        int boundaryTargets;
    }

    private record QuartCell(QuartKey key, QuartWeight weight) {}

    private record Candidate(
            int index,
            int dx,
            int dz,
            int namespaceTargets,
            int namespaceProtected,
            int namespaceBoundary,
            int namespaceQuartCells,
            List<String> namespaceBiomes) {
        long displacementSquared() {
            return (long) dx * dx + (long) dz * dz;
        }
    }

    private record SavedCandidate(
            Candidate predicted,
            int namespaceTargets,
            int namespaceProtected,
            int namespaceBoundary,
            int namespaceQuartCells,
            List<String> namespaceBiomes) {}

    private static List<Target> targets = List.of();
    private static List<QuartCell> quartCells = List.of();
    private static Set<String> expectedMods = Set.of();
    private static String primaryMod;
    private static String providerNamespace;
    private static Path receiptPath;
    private static Path scorePath;
    private static Path shortlistPath;
    private static Path selectedPath;
    private static List<Candidate> shortlist = List.of();
    private static Candidate selected;
    private static SavedCandidate selectedSaved;
    private static Set<ChunkPos> forcedChunks = Set.of();
    private static int selectionLoadedChunkDelta;
    private static boolean initialized;
    private static boolean scanComplete;
    private static boolean complete;
    private static int ticks;

    private BiomeRelocationState() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String targetValue = property("target");
        String targetSha = property("targetSha256");
        String receiptValue = property("receipt");
        String scoreValue = property("scores");
        String shortlistValue = property("shortlist");
        String selectedValue = property("selected");
        String expectedValue = property("expectedMods");
        primaryMod = property("primaryMod");
        providerNamespace = property("namespace");
        require(!targetValue.isEmpty(), "target register property is empty");
        require(targetSha.length() == 64, "target register digest is invalid");
        require(!receiptValue.isEmpty() && !scoreValue.isEmpty() && !shortlistValue.isEmpty() && !selectedValue.isEmpty(), "output property is empty");
        require(!providerNamespace.isEmpty(), "provider namespace is empty");
        expectedMods = expectedValue.isEmpty() ? Set.of() : Set.of(expectedValue.split(","));
        Path targetPath = Path.of(targetValue).toAbsolutePath().normalize();
        receiptPath = Path.of(receiptValue).toAbsolutePath().normalize();
        scorePath = Path.of(scoreValue).toAbsolutePath().normalize();
        shortlistPath = Path.of(shortlistValue).toAbsolutePath().normalize();
        selectedPath = Path.of(selectedValue).toAbsolutePath().normalize();
        try {
            require(sha256(targetPath).equals(targetSha), "target register digest differs");
            List<Target> loaded = new ArrayList<>();
            TreeMap<QuartKey, QuartWeight> weights = new TreeMap<>();
            int protectedCount = 0;
            int boundaryCount = 0;
            for (String line : Files.readAllLines(targetPath, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                String[] fields = line.split("\\t", 7);
                require(fields.length == 7, "target register row width differs");
                Target target = new Target(
                        Integer.parseInt(fields[0]),
                        Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]),
                        Boolean.parseBoolean(fields[3]),
                        Boolean.parseBoolean(fields[4]));
                loaded.add(target);
                QuartKey key = new QuartKey(
                        Math.floorDiv(target.x(), 4),
                        Math.floorDiv(target.y(), 4),
                        Math.floorDiv(target.z(), 4));
                QuartWeight weight = weights.computeIfAbsent(key, ignored -> new QuartWeight());
                weight.targets++;
                if (target.protectedRoute()) {
                    protectedCount++;
                    weight.protectedTargets++;
                }
                if (target.boundary()) {
                    boundaryCount++;
                    weight.boundaryTargets++;
                }
            }
            loaded.sort(Comparator.comparingInt(Target::x)
                    .thenComparingInt(Target::y)
                    .thenComparingInt(Target::z));
            require(loaded.size() == EXPECTED_TARGETS, "target count differs");
            require(protectedCount == EXPECTED_PROTECTED, "protected count differs");
            require(boundaryCount == EXPECTED_BOUNDARY, "boundary count differs");
            require(weights.size() == EXPECTED_QUART_CELLS, "quart-biome cell count differs");
            targets = List.copyOf(loaded);
            quartCells = weights.entrySet().stream()
                    .map(entry -> new QuartCell(entry.getKey(), entry.getValue()))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Run 215 input read failed", exception);
        }
        initialized = true;
    }

    public static synchronized void onServerStarted(MinecraftServer server) {
        require(initialized, "Run 215 is not initialized");
        for (String mod : expectedMods) {
            require(FabricLoader.getInstance().isModLoaded(mod), "expected mod is not loaded: " + mod);
        }
        ServerWorld world = server.getOverworld();
        int loadedBefore = world.getChunkManager().getLoadedChunkCount();
        List<Candidate> candidates = scanCandidates(world);
        int loadedAfter = world.getChunkManager().getLoadedChunkCount();
        selectionLoadedChunkDelta = loadedAfter - loadedBefore;
        require(candidates.size() == EXPECTED_CANDIDATES, "candidate count differs");
        shortlist = candidates.stream()
                .sorted(BiomeRelocationState::compareCandidates)
                .limit(SHORTLIST_SIZE)
                .toList();
        require(shortlist.size() == SHORTLIST_SIZE, "shortlist size differs");
        writeScoreRegister(candidates);
        Set<ChunkPos> chunks = new HashSet<>();
        for (Candidate candidate : shortlist) {
            for (Target target : targets) {
                chunks.add(new ChunkPos(
                        Math.floorDiv(target.x() + candidate.dx(), 16),
                        Math.floorDiv(target.z() + candidate.dz(), 16)));
            }
        }
        forcedChunks = Set.copyOf(chunks);
        require(forcedChunks.size() == EXPECTED_FORCED_CHUNKS, "shortlist union chunk count differs");
        for (ChunkPos chunk : forcedChunks) {
            world.getChunkManager().setChunkForced(chunk, true);
        }
        scanComplete = true;
    }

    public static void onServerTick(MinecraftServer server) {
        synchronized (BiomeRelocationState.class) {
            if (complete || !scanComplete) return;
            ticks++;
            if (ticks < AUDIT_TICK) return;
            complete = true;
        }
        try {
            audit(server);
        } catch (RuntimeException exception) {
            KarstogenesisFabricBiomeRelocation.LOGGER.error("Run 215 probe failed", exception);
            writeFailure(exception);
        }
        for (ChunkPos chunk : forcedChunks) {
            server.getOverworld().getChunkManager().setChunkForced(chunk, false);
        }
        server.stop(false);
    }

    private static List<Candidate> scanCandidates(ServerWorld world) {
        BiomeSource source = world.getChunkManager().getChunkGenerator().getBiomeSource();
        MultiNoiseUtil.MultiNoiseSampler sampler = world.getChunkManager().getNoiseConfig().getMultiNoiseSampler();
        List<Candidate> candidates = new ArrayList<>(EXPECTED_CANDIDATES);
        int index = 0;
        for (int dx = MINIMUM_DELTA; dx <= MAXIMUM_DELTA; dx += DELTA_STEP) {
            for (int dz = MINIMUM_DELTA; dz <= MAXIMUM_DELTA; dz += DELTA_STEP) {
                int namespaceTargets = 0;
                int namespaceProtected = 0;
                int namespaceBoundary = 0;
                int namespaceQuartCells = 0;
                TreeSet<String> namespaceBiomes = new TreeSet<>();
                int quartDx = Math.floorDiv(dx, 4);
                int quartDz = Math.floorDiv(dz, 4);
                for (QuartCell cell : quartCells) {
                    QuartKey key = cell.key();
                    RegistryEntry<Biome> biome = source.getBiome(
                            key.x() + quartDx,
                            key.y(),
                            key.z() + quartDz,
                            sampler);
                    String biomeId = biomeIdentity(biome);
                    if (biomeId.startsWith(providerNamespace + ":")) {
                        QuartWeight weight = cell.weight();
                        namespaceTargets += weight.targets;
                        namespaceProtected += weight.protectedTargets;
                        namespaceBoundary += weight.boundaryTargets;
                        namespaceQuartCells++;
                        namespaceBiomes.add(biomeId);
                    }
                }
                candidates.add(new Candidate(
                        index++,
                        dx,
                        dz,
                        namespaceTargets,
                        namespaceProtected,
                        namespaceBoundary,
                        namespaceQuartCells,
                        List.copyOf(namespaceBiomes)));
            }
        }
        return List.copyOf(candidates);
    }

    private static int compareCandidates(Candidate left, Candidate right) {
        int result = Integer.compare(right.namespaceTargets(), left.namespaceTargets());
        if (result != 0) return result;
        result = Integer.compare(right.namespaceProtected(), left.namespaceProtected());
        if (result != 0) return result;
        result = Integer.compare(right.namespaceQuartCells(), left.namespaceQuartCells());
        if (result != 0) return result;
        result = Integer.compare(right.namespaceBiomes().size(), left.namespaceBiomes().size());
        if (result != 0) return result;
        result = Long.compare(left.displacementSquared(), right.displacementSquared());
        if (result != 0) return result;
        result = Integer.compare(left.dz(), right.dz());
        if (result != 0) return result;
        return Integer.compare(left.dx(), right.dx());
    }

    private static SavedCandidate scoreSavedCandidate(ServerWorld world, Candidate candidate) {
        int namespaceTargets = 0;
        int namespaceProtected = 0;
        int namespaceBoundary = 0;
        int namespaceQuartCells = 0;
        TreeSet<String> namespaceBiomes = new TreeSet<>();
        int quartDx = Math.floorDiv(candidate.dx(), 4);
        int quartDz = Math.floorDiv(candidate.dz(), 4);
        for (QuartCell cell : quartCells) {
            QuartKey sourceKey = cell.key();
            int quartX = sourceKey.x() + quartDx;
            int quartZ = sourceKey.z() + quartDz;
            BlockPos position = new BlockPos(quartX * 4, sourceKey.y() * 4, quartZ * 4);
            String biomeId = biomeIdentity(world.getChunk(position), position);
            if (biomeId.startsWith(providerNamespace + ":")) {
                QuartWeight weight = cell.weight();
                namespaceTargets += weight.targets;
                namespaceProtected += weight.protectedTargets;
                namespaceBoundary += weight.boundaryTargets;
                namespaceQuartCells++;
                namespaceBiomes.add(biomeId);
            }
        }
        return new SavedCandidate(
                candidate,
                namespaceTargets,
                namespaceProtected,
                namespaceBoundary,
                namespaceQuartCells,
                List.copyOf(namespaceBiomes));
    }

    private static int compareSavedCandidates(SavedCandidate left, SavedCandidate right) {
        int result = Integer.compare(right.namespaceTargets(), left.namespaceTargets());
        if (result != 0) return result;
        result = Integer.compare(right.namespaceProtected(), left.namespaceProtected());
        if (result != 0) return result;
        result = Integer.compare(right.namespaceQuartCells(), left.namespaceQuartCells());
        if (result != 0) return result;
        result = Integer.compare(right.namespaceBiomes().size(), left.namespaceBiomes().size());
        if (result != 0) return result;
        return compareCandidates(left.predicted(), right.predicted());
    }

    private static void writeShortlistRegister(List<SavedCandidate> savedCandidates) {
        StringBuilder register = new StringBuilder();
        register.append("direct_rank\tcandidate_index\tdx\tdz\tpredicted_namespace_target_count\tpredicted_namespace_protected_count\tpredicted_namespace_quart_cell_count\tsaved_namespace_target_count\tsaved_namespace_target_fraction\tsaved_namespace_protected_count\tsaved_namespace_boundary_count\tsaved_namespace_quart_cell_count\tsaved_namespace_unique_biome_count\tsaved_namespace_biome_ids\tprediction_minus_saved_target_count\n");
        for (int index = 0; index < savedCandidates.size(); index++) {
            SavedCandidate saved = savedCandidates.get(index);
            Candidate predicted = saved.predicted();
            register.append(index + 1).append('\t')
                    .append(predicted.index()).append('\t')
                    .append(predicted.dx()).append('\t')
                    .append(predicted.dz()).append('\t')
                    .append(predicted.namespaceTargets()).append('\t')
                    .append(predicted.namespaceProtected()).append('\t')
                    .append(predicted.namespaceQuartCells()).append('\t')
                    .append(saved.namespaceTargets()).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", saved.namespaceTargets() / (double) EXPECTED_TARGETS)).append('\t')
                    .append(saved.namespaceProtected()).append('\t')
                    .append(saved.namespaceBoundary()).append('\t')
                    .append(saved.namespaceQuartCells()).append('\t')
                    .append(saved.namespaceBiomes().size()).append('\t')
                    .append(String.join(",", saved.namespaceBiomes())).append('\t')
                    .append(predicted.namespaceTargets() - saved.namespaceTargets()).append('\n');
        }
        writeAtomic(shortlistPath, register.toString());
    }

    private static void writeScoreRegister(List<Candidate> candidates) {
        StringBuilder register = new StringBuilder();
        register.append("candidate_index\tdx\tdz\tnamespace_target_count\tnamespace_target_fraction\tnamespace_protected_count\tnamespace_boundary_count\tnamespace_quart_cell_count\tnamespace_unique_biome_count\tnamespace_biome_ids\n");
        for (Candidate candidate : candidates) {
            register.append(candidate.index()).append('\t')
                    .append(candidate.dx()).append('\t')
                    .append(candidate.dz()).append('\t')
                    .append(candidate.namespaceTargets()).append('\t')
                    .append(String.format(Locale.ROOT, "%.12f", candidate.namespaceTargets() / (double) EXPECTED_TARGETS)).append('\t')
                    .append(candidate.namespaceProtected()).append('\t')
                    .append(candidate.namespaceBoundary()).append('\t')
                    .append(candidate.namespaceQuartCells()).append('\t')
                    .append(candidate.namespaceBiomes().size()).append('\t')
                    .append(String.join(",", candidate.namespaceBiomes())).append('\n');
        }
        writeAtomic(scorePath, register.toString());
    }

    private static void audit(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        List<SavedCandidate> savedCandidates = shortlist.stream()
                .map(candidate -> scoreSavedCandidate(world, candidate))
                .toList();
        writeShortlistRegister(savedCandidates);
        selectedSaved = savedCandidates.stream()
                .min(BiomeRelocationState::compareSavedCandidates)
                .orElseThrow();
        selected = selectedSaved.predicted();
        StringBuilder register = new StringBuilder();
        register.append("source_x\tsource_y\tsource_z\tx\ty\tz\tprotected_route\tregistered_boundary\tblock_id\tbiome_id\tair\tfluid\tempty_collision\tbase_stone\tfull_state\n");
        Set<String> uniqueBlocks = new HashSet<>();
        Set<String> uniqueBiomes = new HashSet<>();
        int namespaceBlockCount = 0;
        int namespaceBiomeCount = 0;
        for (Target target : targets) {
            int x = target.x() + selected.dx();
            int z = target.z() + selected.dz();
            BlockPos position = new BlockPos(x, target.y(), z);
            BlockState state = world.getBlockState(position);
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            String biomeId = biomeIdentity(world.getChunk(position), position);
            boolean air = state.isAir();
            boolean fluid = !state.getFluidState().isEmpty();
            boolean emptyCollision = state.getCollisionShape(world, position).isEmpty();
            boolean baseStone = state.isIn(net.minecraft.registry.tag.BlockTags.BASE_STONE_OVERWORLD);
            uniqueBlocks.add(blockId);
            uniqueBiomes.add(biomeId);
            if (blockId.startsWith(providerNamespace + ":")) namespaceBlockCount++;
            if (biomeId.startsWith(providerNamespace + ":")) namespaceBiomeCount++;
            register.append(target.x()).append('\t')
                    .append(target.y()).append('\t')
                    .append(target.z()).append('\t')
                    .append(x).append('\t')
                    .append(target.y()).append('\t')
                    .append(z).append('\t')
                    .append(target.protectedRoute()).append('\t')
                    .append(target.boundary()).append('\t')
                    .append(blockId).append('\t')
                    .append(biomeId).append('\t')
                    .append(air).append('\t')
                    .append(fluid).append('\t')
                    .append(emptyCollision).append('\t')
                    .append(baseStone).append('\t')
                    .append(state).append('\n');
        }
        require(namespaceBiomeCount == selectedSaved.namespaceTargets(), "selected saved-biome count differs from target inventory");
        writeAtomic(selectedPath, register.toString());
        Set<ChunkPos> selectedChunks = new HashSet<>();
        for (Target target : targets) {
            selectedChunks.add(new ChunkPos(
                    Math.floorDiv(target.x() + selected.dx(), 16),
                    Math.floorDiv(target.z() + selected.dz(), 16)));
        }
        require(selectedChunks.size() == 59, "selected chunk count differs");
        int selectedDirectRank = shortlist.indexOf(selected) + 1;
        int primaryLoadedCount = primaryMod.isEmpty() ? 0 : (FabricLoader.getInstance().isModLoaded(primaryMod) ? 1 : 0);
        String receipt = String.format(
                Locale.ROOT,
                "{\"schema_version\":\"geoworld.karstogenesis-215-saved-biome-shortlist-selection-arm.v1\",\"state\":\"DONE\",\"candidate_count\":%d,\"source_quart_cell_count\":%d,\"selection_loaded_chunk_delta\":%d,\"shortlist_size\":%d,\"shortlist_union_chunk_count\":%d,\"selected_direct_rank\":%d,\"selected_candidate_index\":%d,\"selected_dx\":%d,\"selected_dz\":%d,\"selected_predicted_namespace_target_count\":%d,\"selected_predicted_namespace_protected_count\":%d,\"selected_predicted_namespace_quart_cell_count\":%d,\"selected_saved_namespace_target_count\":%d,\"selected_saved_namespace_target_fraction\":%.12f,\"selected_saved_namespace_protected_count\":%d,\"selected_saved_namespace_boundary_count\":%d,\"selected_saved_namespace_quart_cell_count\":%d,\"selected_saved_namespace_unique_biome_count\":%d,\"selected_saved_namespace_biome_ids\":\"%s\",\"target_count\":%d,\"protected_count\":%d,\"boundary_count\":%d,\"selected_chunk_count\":%d,\"primary_mod_loaded_count\":%d,\"provider_namespace\":\"%s\",\"namespace_block_observation_count\":%d,\"namespace_biome_observation_count\":%d,\"unique_block_count\":%d,\"unique_biome_count\":%d,\"score_register_sha256\":\"%s\",\"shortlist_register_sha256\":\"%s\",\"selected_register_sha256\":\"%s\"}\n",
                EXPECTED_CANDIDATES,
                EXPECTED_QUART_CELLS,
                selectionLoadedChunkDelta,
                shortlist.size(),
                forcedChunks.size(),
                selectedDirectRank,
                selected.index(),
                selected.dx(),
                selected.dz(),
                selected.namespaceTargets(),
                selected.namespaceProtected(),
                selected.namespaceQuartCells(),
                selectedSaved.namespaceTargets(),
                selectedSaved.namespaceTargets() / (double) EXPECTED_TARGETS,
                selectedSaved.namespaceProtected(),
                selectedSaved.namespaceBoundary(),
                selectedSaved.namespaceQuartCells(),
                selectedSaved.namespaceBiomes().size(),
                jsonEscape(String.join(",", selectedSaved.namespaceBiomes())),
                targets.size(),
                EXPECTED_PROTECTED,
                EXPECTED_BOUNDARY,
                selectedChunks.size(),
                primaryLoadedCount,
                jsonEscape(providerNamespace),
                namespaceBlockCount,
                namespaceBiomeCount,
                uniqueBlocks.size(),
                uniqueBiomes.size(),
                sha256(scorePath),
                sha256(shortlistPath),
                sha256(selectedPath));
        writeAtomic(receiptPath, receipt);
    }

    private static String biomeIdentity(Chunk chunk, BlockPos position) {
        return biomeIdentity(chunk.getBiomeForNoiseGen(
                Math.floorDiv(position.getX(), 4),
                Math.floorDiv(position.getY(), 4),
                Math.floorDiv(position.getZ(), 4)));
    }

    private static String biomeIdentity(RegistryEntry<Biome> biome) {
        return biome.getKey()
                .map(key -> key.getValue().toString())
                .orElse("host:unregistered-biome");
    }

    private static String property(String name) {
        return System.getProperty("geoworld.probe215." + name, "").trim();
    }

    private static void writeFailure(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ":" + String.valueOf(exception.getMessage());
        writeAtomic(
                receiptPath,
                "{\"schema_version\":\"geoworld.karstogenesis-215-saved-biome-shortlist-selection-arm.v1\",\"state\":\"FAIL\",\"error\":\""
                        + jsonEscape(message)
                        + "\"}\n");
    }

    private static void writeAtomic(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".partial");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Run 215 output write failed", exception);
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("Run 215 digest failed", exception);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
