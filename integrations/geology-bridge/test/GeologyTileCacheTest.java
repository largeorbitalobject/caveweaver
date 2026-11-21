import com.orbital.geoworld.geology.GeologyTileCache;

public final class GeologyTileCacheTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String mode = System.getProperty("geoworld.geology.test-mode");
        if ("cw10_absolute_classes".equals(mode)) {
            require(GeologyTileCache.cw10AbsoluteClassesEnabled(), "CW10 mode did not load");
            require(GeologyTileCache.preflightCw10ClassTiles(), "CW10 class preflight failed");
            long[] counts = new long[3];
            long[] routeCount = new long[1];
            long candidates = GeologyTileCache.visitCw10ClassBlocks((x, y, z, blockClass, protectedRoute) -> {
                require(x >= 0 && x < 256, "CW10 X escaped registered extent");
                require(y >= -52 && y < 44, "CW10 Y escaped registered extent");
                require(z >= 0 && z < 256, "CW10 Z escaped registered extent");
                counts[blockClass]++;
                if (protectedRoute) routeCount[0]++;
                return false;
            });
            require(candidates == 85314, "CW10 candidate count changed");
            require(counts[1] == 49757, "CW10 air count changed");
            require(counts[2] == 35557, "CW10 water count changed");
            require(routeCount[0] == 5735, "CW10 protected route count changed");
            System.out.println("PASS: CW10 absolute classes, extents, air, and water counts");
            return;
        }
        if ("cw10_corrupt".equals(mode)) {
            require(GeologyTileCache.cw10AbsoluteClassesEnabled(), "CW10 corrupt mode did not load");
            require(!GeologyTileCache.preflightCw10ClassTiles(), "corrupt CW10 cache passed preflight");
            require(GeologyTileCache.visitCw10ClassBlocks(
                    (x, y, z, blockClass, protectedRoute) -> true) == 0,
                    "corrupt CW10 cache exposed class blocks");
            System.out.println("PASS: corrupt CW10 class cache fails closed without writes");
            return;
        }
        if ("post_noise_multi".equals(mode)) {
            require(GeologyTileCache.shouldCarve(258, 40, 2), "tile 1 did not load first");
            require(GeologyTileCache.shouldCarve(254, 40, 2), "tile 0 did not load second");
            require(!GeologyTileCache.shouldCarve(256, 70, 2), "shared surface shell was carved");
            require(!GeologyTileCache.shouldCarve(600, 40, 2), "missing tile did not fall back");
            System.out.println("PASS: multi-tile seam ports, reverse request order, and fallback");
            return;
        }
        if ("post_noise_corrupt".equals(mode)) {
            require(!GeologyTileCache.shouldCarve(258, 40, 2), "corrupt tile did not fall back");
            require(!GeologyTileCache.shouldCarve(258, 40, 2), "corrupt tile fallback was not permanent");
            require(GeologyTileCache.shouldCarve(254, 40, 2), "healthy neighbor was disabled");
            System.out.println("PASS: corrupt tile falls back permanently without poisoning neighbor");
            return;
        }
        if ("post_noise_air".equals(mode)) {
            require(!GeologyTileCache.shouldCarve(2, 40, 2), "positive SDF carved solid");
            require(GeologyTileCache.shouldCarve(6, 40, 2), "negative SDF did not carve air");
            require(!GeologyTileCache.shouldCarve(6, 70, 2), "surface shell was carved");
            require(!GeologyTileCache.shouldCarve(300, 40, 2), "missing tile did not fall back");
            System.out.println("PASS: post-noise air mask, frozen surface, protection, and fallback");
            return;
        }
        double solid = GeologyTileCache.apply(2, 40, 2, 40.0);
        double cave = GeologyTileCache.apply(6, 40, 2, 40.0);
        double protectedSurface = GeologyTileCache.apply(6, 70, 2, 8.0);
        double missing = GeologyTileCache.apply(300, 40, 2, 40.0);
        require(solid == 40.0, "positive SDF changed solid density");
        require(cave < 0.0, "negative SDF did not carve");
        require(protectedSurface == 8.0, "surface protection shell was modified");
        require(missing == 40.0, "missing tile did not use legacy fallback");
        System.out.println("PASS: immutable SDF sampling, surface protection, and fallback");
    }
}
