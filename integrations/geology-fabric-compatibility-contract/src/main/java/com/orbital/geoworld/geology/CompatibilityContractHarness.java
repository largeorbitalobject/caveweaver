package com.orbital.geoworld.geology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Deterministic host-neutral contract fixtures for Run 211. */
public final class CompatibilityContractHarness {
    private record Fixture(
            String name,
            KarstCompatibilityContract.Input input,
            KarstCompatibilityContract.Status status,
            KarstCompatibilityContract.Resolution resolution,
            boolean requiredFailClosed) {}

    private CompatibilityContractHarness() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) throw new IllegalArgumentException("output required");
        List<Fixture> fixtures = fixtures();
        List<KarstCompatibilityContract.Decision> canonical = evaluate(fixtures);
        List<Fixture> reversed = new ArrayList<>(fixtures);
        java.util.Collections.reverse(reversed);
        List<KarstCompatibilityContract.Decision> reverse = evaluate(reversed);
        List<KarstCompatibilityContract.Decision> concurrent = evaluateConcurrent(fixtures);
        String canonicalDigest = digest(canonical);
        String reverseDigest = digest(reverse);
        String concurrentDigest = digest(concurrent);
        int expected = 0;
        int requiredFailedClosed = 0;
        for (int index = 0; index < fixtures.size(); index++) {
            Fixture fixture = fixtures.get(index);
            KarstCompatibilityContract.Decision decision = canonical.stream()
                    .filter(value -> value.index() == fixture.input().index())
                    .findFirst().orElseThrow();
            if (decision.status() == fixture.status() && decision.resolution() == fixture.resolution()) expected++;
            if (fixture.requiredFailClosed() && decision.status() == KarstCompatibilityContract.Status.REJECTED) requiredFailedClosed++;
        }
        String json = String.format(Locale.ROOT,
                "{\"schema_version\":\"geoworld.karstogenesis-211-contract-fixtures.v1\",\"state\":\"%s\",\"fixture_count\":%d,\"expected_count\":%d,\"required_fail_closed_count\":%d,\"canonical_sha256\":\"%s\",\"reverse_sha256\":\"%s\",\"concurrent_sha256\":\"%s\",\"reverse_identity_fraction\":%.1f,\"concurrent_identity_fraction\":%.1f}\n",
                expected == fixtures.size() && requiredFailedClosed == 6 && canonicalDigest.equals(reverseDigest) && canonicalDigest.equals(concurrentDigest) ? "PASS" : "FAIL",
                fixtures.size(), expected, requiredFailedClosed, canonicalDigest, reverseDigest, concurrentDigest,
                canonicalDigest.equals(reverseDigest) ? 1.0 : 0.0,
                canonicalDigest.equals(concurrentDigest) ? 1.0 : 0.0);
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private static List<KarstCompatibilityContract.Decision> evaluate(List<Fixture> fixtures) {
        List<KarstCompatibilityContract.Decision> result = new ArrayList<>();
        for (Fixture fixture : fixtures) result.add(KarstCompatibilityContract.resolve(fixture.input()));
        result.sort(Comparator.comparingInt(KarstCompatibilityContract.Decision::index));
        return result;
    }

    private static List<KarstCompatibilityContract.Decision> evaluateConcurrent(List<Fixture> fixtures) throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<KarstCompatibilityContract.Decision>> tasks = new ArrayList<>();
            for (Fixture fixture : fixtures) tasks.add(() -> KarstCompatibilityContract.resolve(fixture.input()));
            List<KarstCompatibilityContract.Decision> result = new ArrayList<>();
            for (var value : executor.invokeAll(tasks)) result.add(value.get());
            result.sort(Comparator.comparingInt(KarstCompatibilityContract.Decision::index));
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String digest(List<KarstCompatibilityContract.Decision> decisions) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var value : decisions) {
                digest.update((value.index() + "\0" + value.status() + "\0" + value.resolution() + "\0" + value.reason() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Fixture f(int index, String name, boolean air, boolean fluid, boolean empty, boolean protectedRoute, boolean boundary, Set<KarstCompatibilityContract.ClaimTag> claims, KarstCompatibilityContract.Status status, KarstCompatibilityContract.Resolution resolution, boolean requiredFailClosed) {
        return new Fixture(name, new KarstCompatibilityContract.Input(index, air, fluid, empty, protectedRoute, boundary, claims), status, resolution, requiredFailClosed);
    }

    private static List<Fixture> fixtures() {
        return List.of(
                f(0, "unclaimed-air", true, false, true, false, false, Set.of(), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.AIR, false),
                f(1, "unclaimed-fluid", false, true, true, false, false, Set.of(), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.FLUID, false),
                f(2, "unclaimed-empty-collision", false, false, true, false, false, Set.of(), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.UNCLAIMED_PASS_THROUGH, false),
                f(3, "unclaimed-collision-ordinary", false, false, false, false, false, Set.of(), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.PRESERVE_UNCLAIMED_COLLISION, false),
                f(4, "protected-unclaimed-collision", false, false, false, true, false, Set.of(), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_PROTECTED_COLLISION, true),
                f(5, "boundary-unclaimed-collision", false, false, false, false, true, Set.of(), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_PROTECTED_COLLISION, false),
                f(6, "pass-through-valid", false, false, true, false, false, Set.of(KarstCompatibilityContract.ClaimTag.PASS_THROUGH_DECORATION), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.PASS_THROUGH_DECORATION, false),
                f(7, "invalid-pass-through-collision", false, false, false, false, false, Set.of(KarstCompatibilityContract.ClaimTag.PASS_THROUGH_DECORATION), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_INVALID_DECLARATION, true),
                f(8, "collision-ecology-valid", false, false, false, false, false, Set.of(KarstCompatibilityContract.ClaimTag.COLLISION_BEARING_ECOLOGY), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.COLLISION_BEARING_ECOLOGY, false),
                f(9, "invalid-collision-ecology-empty-shape", false, false, true, false, false, Set.of(KarstCompatibilityContract.ClaimTag.COLLISION_BEARING_ECOLOGY), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_INVALID_DECLARATION, true),
                f(10, "shared-void-air", true, false, true, false, false, Set.of(KarstCompatibilityContract.ClaimTag.SHARED_VOID), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.SHARED_VOID, false),
                f(11, "shared-void-empty-shape", false, false, true, false, false, Set.of(KarstCompatibilityContract.ClaimTag.SHARED_VOID), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.SHARED_VOID, false),
                f(12, "invalid-shared-void-solid", false, false, false, false, false, Set.of(KarstCompatibilityContract.ClaimTag.SHARED_VOID), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_INVALID_DECLARATION, true),
                f(13, "replaceable-solid-valid", false, false, false, false, false, Set.of(KarstCompatibilityContract.ClaimTag.REPLACEABLE_SOLID), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.REPLACEABLE_SOLID, false),
                f(14, "preserve-ordinary", false, false, false, false, false, Set.of(KarstCompatibilityContract.ClaimTag.PRESERVE), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.PRESERVE, false),
                f(15, "exclusive-ordinary", false, false, false, false, false, Set.of(KarstCompatibilityContract.ClaimTag.EXCLUSIVE_GEOMETRY), KarstCompatibilityContract.Status.ACTIVE, KarstCompatibilityContract.Resolution.PRESERVE_EXCLUSIVE, false),
                f(16, "boundary-exclusive-collision", false, false, false, false, true, Set.of(KarstCompatibilityContract.ClaimTag.EXCLUSIVE_GEOMETRY), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_PROTECTED_COLLISION, true),
                f(17, "ambiguous-two-tag-claim", false, false, true, false, false, Set.of(KarstCompatibilityContract.ClaimTag.PASS_THROUGH_DECORATION, KarstCompatibilityContract.ClaimTag.PRESERVE), KarstCompatibilityContract.Status.REJECTED, KarstCompatibilityContract.Resolution.REJECT_AMBIGUOUS_CLAIM, true));
    }
}
