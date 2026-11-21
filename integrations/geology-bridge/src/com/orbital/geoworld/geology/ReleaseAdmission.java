package com.orbital.geoworld.geology;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Verifies that the configured cache and bridge belong to the world-pinned release. */
final class ReleaseAdmission {
    private static final String PREFIX = "geoworld.geology.";

    private ReleaseAdmission() {}

    static void requireConfigured() {
        boolean required = Boolean.parseBoolean(System.getProperty(PREFIX + "require-release-pin", "false"));
        String manifestValue = System.getProperty(PREFIX + "release-manifest");
        String pinValue = System.getProperty(PREFIX + "world-pin");
        String cacheValue = System.getProperty(PREFIX + "config");
        String cacheArtifact = System.getProperty(PREFIX + "cache-artifact");
        String bridgeValue = System.getProperty(PREFIX + "bridge-path");
        String bridgeArtifact = System.getProperty(PREFIX + "bridge-artifact");
        boolean anyConfigured = manifestValue != null || pinValue != null || cacheArtifact != null
                || bridgeValue != null || bridgeArtifact != null;
        if (!required && !anyConfigured) return;
        if (!required) {
            throw new IllegalStateException("release admission properties require require-release-pin=true");
        }
        try {
            Path manifestPath = regularAbsolute(manifestValue, "release-manifest");
            Path pinPath = regularAbsolute(pinValue, "world-pin");
            Path cachePath = regularAbsolute(cacheValue, "config");
            Path bridgePath = regularAbsolute(bridgeValue, "bridge-path");
            String cacheKey = nonblank(cacheArtifact, "cache-artifact");
            String bridgeKey = nonblank(bridgeArtifact, "bridge-artifact");

            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            JsonObject manifest = parse(manifestBytes, "release manifest");
            JsonObject pin = parse(Files.readAllBytes(pinPath), "world pin");
            requireValue(manifest, "schema_version", "geoworld.model-release.v1");
            requireValue(manifest, "project", "geology");
            requireValue(pin, "schema_version", "geoworld.world-release-pin.v1");

            String releaseId = string(manifest, "release_id");
            if (!releaseId.equals(string(pin, "active_release_id"))) {
                throw new IllegalArgumentException("active release id mismatch");
            }
            String manifestHash = sha256(manifestBytes);
            if (!manifestHash.equalsIgnoreCase(string(pin, "active_manifest_sha256"))) {
                throw new IllegalArgumentException("active manifest hash mismatch");
            }
            JsonObject artifacts = object(manifest, "artifacts");
            requireHash(artifacts, cacheKey, Files.readAllBytes(cachePath), "cache artifact");
            requireHash(artifacts, bridgeKey, Files.readAllBytes(bridgePath), "bridge artifact");
            System.out.println("[GeoWorld-Geology] release admission PASS release=" + releaseId
                    + " manifest=" + manifestHash + " cache_artifact=" + cacheKey
                    + " bridge_artifact=" + bridgeKey);
        } catch (Throwable throwable) {
            throw new IllegalStateException("GeoWorld geology release admission failed", throwable);
        }
    }

    private static Path regularAbsolute(String value, String field) {
        String checked = nonblank(value, field);
        Path path = Path.of(checked);
        if (!path.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        path = path.normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException(field + " is missing");
        return path;
    }

    private static String nonblank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + field);
        return value;
    }

    private static JsonObject parse(byte[] content, String field) {
        JsonObject value = new Gson().fromJson(new String(content, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
        if (value == null) throw new IllegalArgumentException(field + " is not a JSON object");
        return value;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("missing " + key);
        return value.getAsJsonObject();
    }

    private static String string(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("missing " + key);
        return value.getAsString();
    }

    private static void requireValue(JsonObject parent, String key, String expected) {
        if (!expected.equals(string(parent, key))) throw new IllegalArgumentException("unsupported " + key);
    }

    private static void requireHash(JsonObject artifacts, String key, byte[] content, String field) throws Exception {
        String expected = string(artifacts, key);
        if (!sha256(content).equalsIgnoreCase(expected)) throw new IllegalArgumentException(field + " hash mismatch");
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }
}
