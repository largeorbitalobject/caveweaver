package com.orbital.geoworld.geology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Host-neutral ownership arbitration for cave geometry. */
final class KarstHostArbitration {
    private static final int FIXTURE_INTENTS = 4096;
    private static final int AUDIT_REPEATS = 25;
    private static final int AUDIT_THREADS = 8;

    enum HostState {
        REPLACEABLE_SOLID,
        EXISTING_VOID,
        FLUID,
        PRESERVE,
        UNKNOWN
    }

    enum ClaimClass {
        NONE,
        SHARED_VOID,
        DECORATION_ONLY,
        EXCLUSIVE_GEOMETRY,
        RESERVED_STRUCTURE,
        UNKNOWN
    }

    enum BiomeAction {
        PRESERVE_EXISTING,
        REQUEST_SEMANTIC,
        OVERRIDE_RESOLVED
    }

    enum Action {
        CARVE,
        NOOP_EXISTING_VOID,
        DEFER_FLUID,
        SKIP,
        REJECT
    }

    enum PlanState {
        ACTIVE,
        REJECTED
    }

    record GeometryIntent(
            int index,
            boolean protectedRoute,
            boolean registeredBoundary,
            String semanticBiome) {}

    record HostObservation(
            HostState hostState,
            ClaimClass claimClass,
            String claimOwner,
            BiomeAction biomeAction,
            String resolvedBiome) {}

    record Input(GeometryIntent geometry, HostObservation host) {}

    record Decision(
            int index,
            Action action,
            String reason,
            String retainedClaimOwner,
            BiomeAction biomeAction,
            String resolvedBiome,
            String semanticBiome,
            boolean unresolvedSemanticFallback) {}

    record Plan(
            PlanState state,
            String reason,
            int rejectionIndex,
            List<Decision> decisions,
            String planSha256) {
        Plan {
            decisions = List.copyOf(decisions);
        }
    }

    record Fixture(
            String name,
            List<Input> inputs,
            PlanState expectedState,
            Action expectedAction,
            boolean expectedDecorationRetention,
            boolean expectedBiomePreserve,
            boolean expectedUnresolvedFallback) {}

    record FixtureResult(
            String name,
            boolean expected,
            boolean reverseExact,
            boolean concurrentExact,
            boolean structureReserve,
            boolean exclusiveOrdinarySkip,
            boolean exclusiveProtectedRejection,
            boolean unknownClaimRejection,
            boolean existingVoidNoop,
            boolean decorationClaimRetention,
            boolean preserveExistingBiome,
            boolean unresolvedSemanticFallback,
            boolean opaqueNamespacedRegistryIdentity,
            double planP95Seconds,
            String planSha256) {}

    private static Decision evaluate(Input input) {
        GeometryIntent geometry = input.geometry;
        HostObservation host = input.host;
        if (host.hostState == null || host.claimClass == null || host.biomeAction == null) {
            return rejected(geometry, "unknown-host-observation");
        }
        if (host.claimOwner == null || host.claimOwner.isBlank()
                || !host.claimOwner.contains(":")) {
            return rejected(geometry, "invalid-namespaced-claim-owner");
        }
        if (host.claimClass == ClaimClass.UNKNOWN || host.hostState == HostState.UNKNOWN) {
            return rejected(geometry, "unknown-ownership-state");
        }
        if (host.claimClass == ClaimClass.EXCLUSIVE_GEOMETRY
                || host.claimClass == ClaimClass.RESERVED_STRUCTURE
                || host.hostState == HostState.PRESERVE) {
            if (geometry.protectedRoute || geometry.registeredBoundary) {
                return rejected(geometry, protectedReason(geometry, host.claimClass));
            }
            return decision(
                    geometry,
                    Action.SKIP,
                    host.claimClass == ClaimClass.RESERVED_STRUCTURE
                            ? "reserved-structure"
                            : "host-geometry-preserve",
                    host,
                    false);
        }

        Action action;
        String reason;
        if (host.hostState == HostState.EXISTING_VOID) {
            action = Action.NOOP_EXISTING_VOID;
            reason = "existing-void";
        } else if (host.hostState == HostState.FLUID) {
            action = Action.DEFER_FLUID;
            reason = "host-fluid-policy";
        } else {
            action = Action.CARVE;
            reason = host.claimClass == ClaimClass.DECORATION_ONLY
                    ? "carve-retain-decoration-claim"
                    : host.claimClass == ClaimClass.SHARED_VOID
                            ? "carve-shared-void"
                            : "carve-replaceable-solid";
        }
        boolean unresolved = host.biomeAction == BiomeAction.REQUEST_SEMANTIC
                && (host.resolvedBiome == null || host.resolvedBiome.isBlank());
        return decision(geometry, action, reason, host, unresolved);
    }

    private static String protectedReason(GeometryIntent geometry, ClaimClass claimClass) {
        String owner = claimClass == ClaimClass.RESERVED_STRUCTURE
                ? "structure"
                : "exclusive-geometry";
        return geometry.registeredBoundary
                ? "registered-boundary-conflict-" + owner
                : "protected-route-conflict-" + owner;
    }

    private static Decision rejected(GeometryIntent geometry, String reason) {
        return new Decision(
                geometry.index,
                Action.REJECT,
                reason,
                "host:unknown",
                BiomeAction.PRESERVE_EXISTING,
                "",
                geometry.semanticBiome,
                false);
    }

    private static Decision decision(
            GeometryIntent geometry,
            Action action,
            String reason,
            HostObservation host,
            boolean unresolved) {
        BiomeAction effectiveBiome = unresolved
                ? BiomeAction.PRESERVE_EXISTING
                : host.biomeAction;
        String resolved = effectiveBiome == BiomeAction.OVERRIDE_RESOLVED
                ? safe(host.resolvedBiome)
                : "";
        return new Decision(
                geometry.index,
                action,
                reason,
                host.claimOwner,
                effectiveBiome,
                resolved,
                geometry.semanticBiome,
                unresolved);
    }

    static Plan arbitrate(List<Input> inputs) {
        List<Decision> decisions = new ArrayList<>(inputs.size());
        for (Input input : inputs) decisions.add(evaluate(input));
        return finish(decisions);
    }

    static Plan arbitrateLocally(List<Input> inputs) {
        List<Decision> decisions = new ArrayList<>(inputs.size());
        for (Input input : inputs) decisions.add(evaluate(input));
        decisions.sort(Comparator.comparingInt(Decision::index));
        Decision rejection = decisions.stream()
                .filter(value -> value.action == Action.REJECT)
                .findFirst()
                .orElse(null);
        PlanState state = rejection == null ? PlanState.ACTIVE : PlanState.REJECTED;
        String reason = rejection == null ? "active" : rejection.reason;
        int rejectionIndex = rejection == null ? -1 : rejection.index;
        return new Plan(
                state,
                reason,
                rejectionIndex,
                decisions,
                digest(state, reason, rejectionIndex, decisions));
    }

    static Plan arbitrateConcurrent(List<Input> inputs, int threads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Decision>> tasks = new ArrayList<>(inputs.size());
            for (Input input : inputs) tasks.add(() -> evaluate(input));
            List<Future<Decision>> futures = executor.invokeAll(tasks);
            List<Decision> decisions = new ArrayList<>(futures.size());
            for (Future<Decision> future : futures) decisions.add(future.get());
            return finish(decisions);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Plan finish(List<Decision> input) {
        List<Decision> decisions = new ArrayList<>(input);
        decisions.sort(Comparator.comparingInt(Decision::index));
        Decision rejection = decisions.stream()
                .filter(value -> value.action == Action.REJECT)
                .findFirst()
                .orElse(null);
        PlanState state = rejection == null ? PlanState.ACTIVE : PlanState.REJECTED;
        String reason = rejection == null ? "active" : rejection.reason;
        int rejectionIndex = rejection == null ? -1 : rejection.index;
        List<Decision> emitted = rejection == null ? decisions : List.of();
        return new Plan(
                state,
                reason,
                rejectionIndex,
                emitted,
                digest(state, reason, rejectionIndex, emitted));
    }

    private static String digest(
            PlanState state,
            String reason,
            int rejectionIndex,
            List<Decision> decisions) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        update(digest, state.name());
        update(digest, reason);
        update(digest, Integer.toString(rejectionIndex));
        for (Decision decision : decisions) {
            update(digest, Integer.toString(decision.index));
            update(digest, decision.action.name());
            update(digest, decision.reason);
            update(digest, decision.retainedClaimOwner);
            update(digest, decision.biomeAction.name());
            update(digest, decision.resolvedBiome);
            update(digest, decision.semanticBiome);
            update(digest, Boolean.toString(decision.unresolvedSemanticFallback));
        }
        return hex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item));
        return result.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Fixture fixture(
            String name,
            HostState hostState,
            ClaimClass claimClass,
            String owner,
            BiomeAction biomeAction,
            String resolvedBiome,
            boolean protectedVoxel,
            PlanState expectedState,
            Action expectedAction,
            boolean decorationRetention,
            boolean biomePreserve,
            boolean unresolvedFallback) {
        List<Input> inputs = new ArrayList<>(FIXTURE_INTENTS);
        for (int index = 0; index < FIXTURE_INTENTS; index++) {
            boolean protectedRoute = protectedVoxel && index == FIXTURE_INTENTS / 2;
            GeometryIntent geometry = new GeometryIntent(
                    index,
                    protectedRoute,
                    false,
                    "karst:wetness-" + Math.floorMod(index, 3));
            HostObservation host = new HostObservation(
                    hostState,
                    claimClass,
                    owner,
                    biomeAction,
                    resolvedBiome);
            inputs.add(new Input(geometry, host));
        }
        return new Fixture(
                name,
                List.copyOf(inputs),
                expectedState,
                expectedAction,
                decorationRetention,
                biomePreserve,
                unresolvedFallback);
    }

    private static List<Fixture> fixtures() {
        return List.of(
                fixture("vanilla-replaceable-solid", HostState.REPLACEABLE_SOLID,
                        ClaimClass.NONE, "host:vanilla", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.CARVE, false, true, false),
                fixture("vanilla-existing-void", HostState.EXISTING_VOID,
                        ClaimClass.NONE, "host:vanilla", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.NOOP_EXISTING_VOID, false, true, false),
                fixture("vanilla-fluid-defer", HostState.FLUID,
                        ClaimClass.NONE, "host:vanilla", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.DEFER_FLUID, false, true, false),
                fixture("vanilla-structure-reserve", HostState.REPLACEABLE_SOLID,
                        ClaimClass.RESERVED_STRUCTURE, "host:structure", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.SKIP, false, true, false),
                fixture("modded-tagged-base-stone", HostState.REPLACEABLE_SOLID,
                        ClaimClass.NONE, "examplecaves:base-stone", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.CARVE, false, true, false),
                fixture("modded-preserved-cave-biome", HostState.REPLACEABLE_SOLID,
                        ClaimClass.NONE, "examplecaves:biome-source", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.CARVE, false, true, false),
                fixture("modded-decoration-only-claim", HostState.REPLACEABLE_SOLID,
                        ClaimClass.DECORATION_ONLY, "examplecaves:decorator", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.CARVE, true, true, false),
                fixture("competing-shared-void", HostState.EXISTING_VOID,
                        ClaimClass.SHARED_VOID, "othercaves:shared-void", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.NOOP_EXISTING_VOID, false, true, false),
                fixture("competing-exclusive-ordinary", HostState.REPLACEABLE_SOLID,
                        ClaimClass.EXCLUSIVE_GEOMETRY, "othercaves:exclusive", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.ACTIVE, Action.SKIP, false, true, false),
                fixture("competing-exclusive-protected", HostState.REPLACEABLE_SOLID,
                        ClaimClass.EXCLUSIVE_GEOMETRY, "othercaves:exclusive", BiomeAction.PRESERVE_EXISTING, "",
                        true, PlanState.REJECTED, Action.REJECT, false, false, false),
                fixture("unknown-claim", HostState.REPLACEABLE_SOLID,
                        ClaimClass.UNKNOWN, "unknownmod:claim", BiomeAction.PRESERVE_EXISTING, "",
                        false, PlanState.REJECTED, Action.REJECT, false, false, false),
                fixture("unresolved-semantic-biome", HostState.REPLACEABLE_SOLID,
                        ClaimClass.NONE, "examplecaves:biome-source", BiomeAction.REQUEST_SEMANTIC, "",
                        false, PlanState.ACTIVE, Action.CARVE, false, true, true));
    }

    private static boolean allAction(Plan plan, Action action) {
        return !plan.decisions.isEmpty()
                && plan.decisions.stream().allMatch(value -> value.action == action);
    }

    private static boolean allBiome(Plan plan, BiomeAction action) {
        return !plan.decisions.isEmpty()
                && plan.decisions.stream().allMatch(value -> value.biomeAction == action);
    }

    private static boolean opaqueOwners(Plan plan, Fixture fixture) {
        if (plan.state == PlanState.REJECTED) return true;
        for (int index = 0; index < plan.decisions.size(); index++) {
            if (!plan.decisions.get(index).retainedClaimOwner.equals(
                    fixture.inputs.get(index).host.claimOwner)) return false;
        }
        return true;
    }

    private static FixtureResult auditFixture(Fixture fixture) throws Exception {
        Plan canonical = arbitrate(fixture.inputs);
        List<Input> reverseInputs = new ArrayList<>(fixture.inputs);
        Collections.reverse(reverseInputs);
        Plan reverse = arbitrate(reverseInputs);
        List<Input> striped = new ArrayList<>(fixture.inputs.size());
        for (int lane = 0; lane < AUDIT_THREADS; lane++) {
            for (int index = lane; index < fixture.inputs.size(); index += AUDIT_THREADS) {
                striped.add(fixture.inputs.get(index));
            }
        }
        Plan concurrent = arbitrateConcurrent(striped, AUDIT_THREADS);
        boolean expected = canonical.state == fixture.expectedState;
        if (canonical.state == PlanState.ACTIVE) {
            expected &= allAction(canonical, fixture.expectedAction);
        } else {
            expected &= canonical.rejectionIndex >= 0;
        }
        boolean decorationRetention = !fixture.expectedDecorationRetention
                || canonical.decisions.stream().allMatch(value ->
                        value.retainedClaimOwner.equals("examplecaves:decorator"));
        boolean biomePreserve = !fixture.expectedBiomePreserve
                || allBiome(canonical, BiomeAction.PRESERVE_EXISTING);
        boolean unresolvedFallback = !fixture.expectedUnresolvedFallback
                || canonical.decisions.stream().allMatch(Decision::unresolvedSemanticFallback);

        List<Double> timings = new ArrayList<>(AUDIT_REPEATS);
        for (int repeat = 0; repeat < AUDIT_REPEATS; repeat++) {
            long started = System.nanoTime();
            Plan timed = arbitrate(fixture.inputs);
            if (!timed.planSha256.equals(canonical.planSha256)) {
                throw new IllegalStateException("fixture timing identity differs");
            }
            timings.add((System.nanoTime() - started) / 1_000_000_000.0);
        }
        timings.sort(Double::compareTo);
        int p95Index = (int) Math.ceil(0.95 * timings.size()) - 1;
        return new FixtureResult(
                fixture.name,
                expected,
                canonical.planSha256.equals(reverse.planSha256),
                canonical.planSha256.equals(concurrent.planSha256),
                !fixture.name.equals("vanilla-structure-reserve")
                        || allAction(canonical, Action.SKIP),
                !fixture.name.equals("competing-exclusive-ordinary")
                        || allAction(canonical, Action.SKIP),
                !fixture.name.equals("competing-exclusive-protected")
                        || canonical.state == PlanState.REJECTED,
                !fixture.name.equals("unknown-claim")
                        || canonical.state == PlanState.REJECTED,
                !fixture.name.equals("vanilla-existing-void")
                        || allAction(canonical, Action.NOOP_EXISTING_VOID),
                decorationRetention,
                biomePreserve,
                unresolvedFallback,
                opaqueOwners(canonical, fixture),
                timings.get(p95Index),
                canonical.planSha256);
    }

    private static String json(FixtureResult row) {
        return "{"
                + "\"name\":\"" + row.name + "\","
                + "\"expected\":" + row.expected + ","
                + "\"reverse_exact\":" + row.reverseExact + ","
                + "\"concurrent_exact\":" + row.concurrentExact + ","
                + "\"structure_reserve\":" + row.structureReserve + ","
                + "\"exclusive_ordinary_skip\":" + row.exclusiveOrdinarySkip + ","
                + "\"exclusive_protected_rejection\":"
                + row.exclusiveProtectedRejection + ","
                + "\"unknown_claim_rejection\":" + row.unknownClaimRejection + ","
                + "\"existing_void_noop\":" + row.existingVoidNoop + ","
                + "\"decoration_claim_retention\":" + row.decorationClaimRetention + ","
                + "\"preserve_existing_biome\":" + row.preserveExistingBiome + ","
                + "\"unresolved_semantic_fallback\":"
                + row.unresolvedSemanticFallback + ","
                + "\"opaque_namespaced_registry_identity\":"
                + row.opaqueNamespacedRegistryIdentity + ","
                + "\"plan_p95_seconds\":"
                + String.format(Locale.ROOT, "%.9f", row.planP95Seconds) + ","
                + "\"plan_sha256\":\"" + row.planSha256 + "\""
                + "}";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--audit")) {
            throw new IllegalArgumentException("usage: --audit");
        }
        long started = System.nanoTime();
        List<FixtureResult> results = new ArrayList<>();
        for (Fixture fixture : fixtures()) results.add(auditFixture(fixture));
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        StringBuilder output = new StringBuilder();
        output.append("{\"state\":\"");
        boolean passed = results.stream().allMatch(row ->
                row.expected && row.reverseExact && row.concurrentExact
                        && row.structureReserve && row.exclusiveOrdinarySkip
                        && row.exclusiveProtectedRejection && row.unknownClaimRejection
                        && row.existingVoidNoop && row.decorationClaimRetention
                        && row.preserveExistingBiome && row.unresolvedSemanticFallback
                        && row.opaqueNamespacedRegistryIdentity);
        output.append(passed ? "PASS" : "FAIL");
        output.append("\",\"fixture_count\":").append(results.size());
        output.append(",\"intents_per_fixture\":").append(FIXTURE_INTENTS);
        output.append(",\"world_block_write_count\":0");
        output.append(",\"world_object_reference_used\":false");
        output.append(",\"total_seconds\":")
                .append(String.format(Locale.ROOT, "%.9f", elapsed));
        output.append(",\"fixtures\":[");
        for (int index = 0; index < results.size(); index++) {
            if (index > 0) output.append(',');
            output.append(json(results.get(index)));
        }
        output.append("]}");
        System.out.println(output);
    }
}
