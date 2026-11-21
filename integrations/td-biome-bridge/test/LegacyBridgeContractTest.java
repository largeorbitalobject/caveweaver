import com.orbital.tdbridge.CoarseConditioningPaint;

import java.lang.reflect.Field;

/** Backward-compatibility and fail-closed checks for the five-field extension. */
public final class LegacyBridgeContractTest {
    private static void setStatic(String name, Object value) throws Exception {
        Field field = CoarseConditioningPaint.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void reset() throws Exception {
        setStatic("LOAD_ATTEMPTED", false);
        setStatic("CONFIG", null);
        setStatic("ENABLED", false);
        setStatic("SET_MODE", false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        reset();
        if (args[0].equals("legacy")) {
            require(CoarseConditioningPaint.ready(), "legacy raster did not load");
            CoarseConditioningPaint.Delta west = CoarseConditioningPaint.sampleDelta(-256, 256);
            CoarseConditioningPaint.Delta east = CoarseConditioningPaint.sampleDelta(256, 256);
            require(west != null && east != null, "legacy cell missing");
            require(!west.hasTempStd && !west.hasPrecipCv, "legacy raster invented new fields");
            require(west.elevM == 100.0f && west.tempC == 5.0f && west.precipMm == 300.0f,
                    "legacy west values changed");
            require(east.elevM == 200.0f && east.tempC == 10.0f && east.precipMm == 600.0f,
                    "legacy east values changed");
            System.out.println("PASS: legacy three-field raster behavior is unchanged");
        } else if (args[0].equals("malformed")) {
            require(!CoarseConditioningPaint.ready(), "malformed GeoWorld v1 raster did not fail closed");
            System.out.println("PASS: malformed GeoWorld v1 raster failed closed");
        } else {
            throw new IllegalArgumentException("expected legacy or malformed mode");
        }
    }
}
