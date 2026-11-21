import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

final class HostNeutralKarstPackageAudit {
    private static final String SCHEMA =
            "geoworld.karstogenesis-host-neutral-delta-package.v1";
    private static final int[] TILE_SHAPE = {256, 96, 256};

    record Voxel(int x, int y, int z) implements Comparable<Voxel> {
        @Override
        public int compareTo(Voxel other) {
            int xOrder = Integer.compare(x, other.x);
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Integer.compare(y, other.y);
            if (yOrder != 0) {
                return yOrder;
            }
            return Integer.compare(z, other.z);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static int[] readInt32(Path path) throws IOException {
        byte[] payload = Files.readAllBytes(path);
        if (payload.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("int32 payload width differs");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int[] result = new int[payload.length / Integer.BYTES];
        for (int index = 0; index < result.length; index++) {
            result[index] = buffer.getInt();
        }
        return result;
    }

    private static long[] readUInt64(Path path) throws IOException {
        byte[] payload = Files.readAllBytes(path);
        if (payload.length % Long.BYTES != 0) {
            throw new IllegalArgumentException("uint64 payload width differs");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long[] result = new long[payload.length / Long.BYTES];
        for (int index = 0; index < result.length; index++) {
            result[index] = buffer.getLong();
            if (result[index] < 0) {
                throw new IllegalArgumentException("uint64 offset exceeds signed runtime range");
            }
        }
        return result;
    }

    private static int[] readUInt16(Path path) throws IOException {
        byte[] payload = Files.readAllBytes(path);
        if (payload.length % Short.BYTES != 0) {
            throw new IllegalArgumentException("uint16 payload width differs");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int[] result = new int[payload.length / Short.BYTES];
        for (int index = 0; index < result.length; index++) {
            result[index] = Short.toUnsignedInt(buffer.getShort());
        }
        return result;
    }

    private static TreeSet<Voxel> readRegister(Path root, String prefix)
            throws IOException {
        int[] tile = readInt32(root.resolve(prefix + "-tiles.i32le.bin"));
        long[] offsets = readUInt64(root.resolve(prefix + "-offsets.u64le.bin"));
        int[] local = readUInt16(root.resolve(prefix + "-local-xyz.u16le.bin"));
        if (tile.length % 3 != 0 || local.length % 3 != 0) {
            throw new IllegalArgumentException("sparse register coordinate width differs");
        }
        int tileCount = tile.length / 3;
        int localCount = local.length / 3;
        if (offsets.length != tileCount + 1 || offsets[0] != 0
                || offsets[offsets.length - 1] != localCount) {
            throw new IllegalArgumentException("sparse register offset range differs");
        }
        Set<Voxel> ownedTiles = new HashSet<>();
        TreeSet<Voxel> result = new TreeSet<>();
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            if (offsets[tileIndex] > offsets[tileIndex + 1]) {
                throw new IllegalArgumentException("sparse register offsets are not monotonic");
            }
            Voxel tileKey = new Voxel(
                    tile[tileIndex * 3],
                    tile[tileIndex * 3 + 1],
                    tile[tileIndex * 3 + 2]);
            if (!ownedTiles.add(tileKey)) {
                throw new IllegalArgumentException("sparse register repeats tile ownership");
            }
            for (long row = offsets[tileIndex]; row < offsets[tileIndex + 1]; row++) {
                int offset = Math.toIntExact(row * 3);
                int localX = local[offset];
                int localY = local[offset + 1];
                int localZ = local[offset + 2];
                if (localX >= TILE_SHAPE[0] || localY >= TILE_SHAPE[1]
                        || localZ >= TILE_SHAPE[2]) {
                    throw new IllegalArgumentException("local coordinate escaped tile");
                }
                Voxel voxel = new Voxel(
                        Math.addExact(Math.multiplyExact(tileKey.x, TILE_SHAPE[0]), localX),
                        Math.addExact(Math.multiplyExact(tileKey.y, TILE_SHAPE[1]), localY),
                        Math.addExact(Math.multiplyExact(tileKey.z, TILE_SHAPE[2]), localZ));
                if (!result.add(voxel)) {
                    throw new IllegalArgumentException("sparse register repeats coordinate");
                }
            }
        }
        return result;
    }

    private static void updateVoxel(MessageDigest digest, Voxel voxel) {
        ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(voxel.x).putInt(voxel.y).putInt(voxel.z);
        digest.update(buffer.array());
    }

    private static String volumeDigest(
            TreeSet<Voxel> voidVoxels, TreeSet<Voxel> protectedVoxels) {
        MessageDigest digest = sha256();
        for (Voxel voxel : voidVoxels) {
            updateVoxel(digest, voxel);
        }
        for (Voxel voxel : protectedVoxels) {
            updateVoxel(digest, voxel);
        }
        return hex(digest.digest());
    }

    private static String registerDigest(TreeSet<Voxel> voxels) {
        MessageDigest digest = sha256();
        for (Voxel voxel : voxels) {
            updateVoxel(digest, voxel);
        }
        return hex(digest.digest());
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
        }
        return properties;
    }

    private static int requiredInt(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing integer property: " + key);
        }
        return Integer.parseInt(value);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing property: " + key);
        }
        return value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String audit(Path root) throws IOException {
        Properties properties = loadProperties(root.resolve("package.properties"));
        if (!SCHEMA.equals(required(properties, "schema"))
                || !"ready".equals(required(properties, "state"))) {
            throw new IllegalArgumentException("package is not ready");
        }
        String identity = sha256(root.resolve("identity.json"));
        if (!identity.equals(required(properties, "cache_identity_sha256"))) {
            throw new IllegalArgumentException("cache identity differs");
        }
        int fileCount = requiredInt(properties, "file_count");
        for (int index = 0; index < fileCount; index++) {
            String prefix = String.format("file_%02d", index);
            Path path = root.resolve(required(properties, prefix + "_name"));
            long expectedBytes = Long.parseLong(required(properties, prefix + "_bytes"));
            if (!Files.isRegularFile(path) || Files.size(path) != expectedBytes) {
                throw new IllegalArgumentException("payload size differs");
            }
            if (!sha256(path).equals(required(properties, prefix + "_sha256"))) {
                throw new IllegalArgumentException("payload hash differs");
            }
        }

        TreeSet<Voxel> base = readRegister(root, "base-void");
        TreeSet<Voxel> delta = readRegister(root, "delta-void");
        TreeSet<Voxel> protectedVoxels = readRegister(root, "base-protected");
        TreeSet<Voxel> boundary = readRegister(root, "base-boundary");
        TreeSet<Voxel> closed = new TreeSet<>(base);
        for (Voxel voxel : delta) {
            if (!closed.add(voxel)) {
                throw new IllegalArgumentException("base and delta overlap");
            }
        }
        if (!closed.containsAll(protectedVoxels)) {
            throw new IllegalArgumentException("protected register leaves void");
        }
        String volumeDigest = volumeDigest(closed, protectedVoxels);
        String boundaryDigest = registerDigest(boundary);
        if (base.size() != requiredInt(properties, "base_void_voxel_count")
                || delta.size() != requiredInt(properties, "delta_void_voxel_count")
                || closed.size() != requiredInt(properties, "closed_void_voxel_count")
                || protectedVoxels.size() != requiredInt(properties, "protected_voxel_count")
                || boundary.size() != requiredInt(properties, "boundary_voxel_count")) {
            throw new IllegalArgumentException("reconstructed voxel count differs");
        }
        if (!volumeDigest.equals(required(properties, "expected_volume_sha256"))
                || !boundaryDigest.equals(required(properties, "expected_boundary_sha256"))) {
            throw new IllegalArgumentException("reconstructed digest differs");
        }
        return "{\"state\":\"PASS\",\"package\":\"" + escape(root.toString())
                + "\",\"cache_identity_sha256\":\"" + identity
                + "\",\"base_void_voxel_count\":" + base.size()
                + ",\"delta_void_voxel_count\":" + delta.size()
                + ",\"closed_void_voxel_count\":" + closed.size()
                + ",\"protected_voxel_count\":" + protectedVoxels.size()
                + ",\"boundary_voxel_count\":" + boundary.size()
                + ",\"volume_sha256\":\"" + volumeDigest
                + "\",\"boundary_sha256\":\"" + boundaryDigest + "\"}";
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("provide at least one package path");
        }
        for (String argument : args) {
            System.out.println(audit(Path.of(argument)));
        }
    }
}
