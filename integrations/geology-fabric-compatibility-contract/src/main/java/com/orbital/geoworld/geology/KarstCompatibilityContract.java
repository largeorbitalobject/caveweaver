package com.orbital.geoworld.geology;

import java.util.EnumSet;
import java.util.Set;

/** Host-neutral registry claim resolver for cave geometry and ecology. */
public final class KarstCompatibilityContract {
    public enum ClaimTag {
        PASS_THROUGH_DECORATION,
        COLLISION_BEARING_ECOLOGY,
        EXCLUSIVE_GEOMETRY,
        SHARED_VOID,
        REPLACEABLE_SOLID,
        PRESERVE
    }

    public enum Status {
        ACTIVE,
        REJECTED
    }

    public enum Resolution {
        AIR,
        FLUID,
        PASS_THROUGH_DECORATION,
        COLLISION_BEARING_ECOLOGY,
        PRESERVE_EXCLUSIVE,
        SHARED_VOID,
        REPLACEABLE_SOLID,
        PRESERVE,
        UNCLAIMED_PASS_THROUGH,
        PRESERVE_UNCLAIMED_COLLISION,
        REJECT_AMBIGUOUS_CLAIM,
        REJECT_INVALID_DECLARATION,
        REJECT_PROTECTED_COLLISION
    }

    public record Input(
            int index,
            boolean air,
            boolean fluid,
            boolean emptyCollision,
            boolean protectedRoute,
            boolean registeredBoundary,
            Set<ClaimTag> claims) {
        public Input {
            claims = claims.isEmpty()
                    ? Set.of()
                    : Set.copyOf(EnumSet.copyOf(claims));
        }
    }

    public record Decision(
            int index,
            Status status,
            Resolution resolution,
            String reason,
            boolean explicitClaim,
            boolean collisionFree) {}

    private KarstCompatibilityContract() {}

    public static Decision resolve(Input input) {
        boolean collisionFree = input.air() || input.fluid() || input.emptyCollision();
        if (input.claims().size() > 1) {
            return rejected(input, Resolution.REJECT_AMBIGUOUS_CLAIM, "multiple-claim-tags", collisionFree);
        }
        ClaimTag claim = input.claims().stream().findFirst().orElse(null);
        if (claim != null && !validDeclaration(input, claim)) {
            return rejected(input, Resolution.REJECT_INVALID_DECLARATION, "claim-shape-mismatch", collisionFree);
        }
        if ((input.protectedRoute() || input.registeredBoundary()) && !collisionFree) {
            return rejected(input, Resolution.REJECT_PROTECTED_COLLISION, "protected-collision", false);
        }
        if (claim == null) {
            if (input.air()) return active(input, Resolution.AIR, "unclaimed-air", false, true);
            if (input.fluid()) return active(input, Resolution.FLUID, "unclaimed-fluid", false, true);
            if (input.emptyCollision()) {
                return active(input, Resolution.UNCLAIMED_PASS_THROUGH, "unclaimed-empty-collision", false, true);
            }
            return active(input, Resolution.PRESERVE_UNCLAIMED_COLLISION, "unclaimed-collision-preserve", false, false);
        }
        return switch (claim) {
            case PASS_THROUGH_DECORATION -> active(input, Resolution.PASS_THROUGH_DECORATION, "tag-pass-through-decoration", true, true);
            case COLLISION_BEARING_ECOLOGY -> active(input, Resolution.COLLISION_BEARING_ECOLOGY, "tag-collision-bearing-ecology", true, false);
            case EXCLUSIVE_GEOMETRY -> active(input, Resolution.PRESERVE_EXCLUSIVE, "tag-exclusive-geometry", true, collisionFree);
            case SHARED_VOID -> active(input, Resolution.SHARED_VOID, "tag-shared-void", true, true);
            case REPLACEABLE_SOLID -> active(input, Resolution.REPLACEABLE_SOLID, "tag-replaceable-solid", true, false);
            case PRESERVE -> active(input, Resolution.PRESERVE, "tag-preserve", true, collisionFree);
        };
    }

    private static boolean validDeclaration(Input input, ClaimTag claim) {
        return switch (claim) {
            case PASS_THROUGH_DECORATION -> !input.air() && !input.fluid() && input.emptyCollision();
            case COLLISION_BEARING_ECOLOGY, REPLACEABLE_SOLID -> !input.air() && !input.fluid() && !input.emptyCollision();
            case SHARED_VOID -> input.air() || input.fluid() || input.emptyCollision();
            case EXCLUSIVE_GEOMETRY, PRESERVE -> true;
        };
    }

    private static Decision active(
            Input input,
            Resolution resolution,
            String reason,
            boolean explicit,
            boolean collisionFree) {
        return new Decision(input.index(), Status.ACTIVE, resolution, reason, explicit, collisionFree);
    }

    private static Decision rejected(
            Input input,
            Resolution resolution,
            String reason,
            boolean collisionFree) {
        return new Decision(input.index(), Status.REJECTED, resolution, reason, !input.claims().isEmpty(), collisionFree);
    }
}
