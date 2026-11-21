import com.orbital.tdbridge.CoarseConditioningPaint;

import java.lang.reflect.Field;

/** Standalone round-trip test for geoworld.coarse-raster.v1. */
public final class FiveFieldBridgeTest {
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
        require(CoarseConditioningPaint.ready(), "five-field config did not load");
        int[][] cells = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};
        float[][] expected = {
                {-100.0f, -5.0f, 1.0f, 100.0f, 10.0f},
                {900.0f, 5.0f, 2.0f, 200.0f, 20.0f},
                {0.0f, 15.0f, 3.0f, 300.0f, 30.0f},
                {2500.0f, 25.0f, 4.0f, 400.0f, 40.0f}
        };
        for (int index = 0; index < cells.length; index++) {
            int row = cells[index][0];
            int col = cells[index][1];
            int x = (col - 1) * 512 + 256;
            int z = (row - 1) * 512 + 256;
            CoarseConditioningPaint.Delta delta = CoarseConditioningPaint.sampleDelta(x, z);
            require(delta != null, "null delta at cell " + index);
            require(delta.hasTempStd, "temperature variability flag missing");
            require(delta.hasPrecipCv, "precipitation variability flag missing");
            float[] want = expected[index];
            require(Float.floatToIntBits(delta.elevM) == Float.floatToIntBits(want[0]), "elevation mismatch");
            require(Float.floatToIntBits(delta.tempC) == Float.floatToIntBits(want[1]), "temperature mismatch");
            require(Float.floatToIntBits(delta.tempStdC) == Float.floatToIntBits(want[2]), "temperature std mismatch");
            require(Float.floatToIntBits(delta.precipMm) == Float.floatToIntBits(want[3]), "precipitation mismatch");
            require(Float.floatToIntBits(delta.precipCvPct) == Float.floatToIntBits(want[4]), "precipitation CV mismatch");
        }
        System.out.println("PASS: all five physical fields round-tripped bit-exactly across 4 cells");
    }
}

