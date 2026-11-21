package com.orbital.tdbridge.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.SyntheticMapFactory;
import com.orbital.tdbridge.CoarseConditioningPaint;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Five-field reconstruction of the deployed synthetic-map paint mixin.
 * Physical temperature standard deviation is converted to the model's BIO4 x100
 * plane exactly once here. Legacy three-field Delta instances leave planes 2 and 4
 * untouched, preserving old-world behavior.
 */
@Mixin(SyntheticMapFactory.class)
public class SyntheticMapFactoryMixin {
    private static volatile int TDCOARSE_DBG = 0;
    private static final String TDCOARSE_INPUT_DUMP_PATH =
            System.getProperty("tdcoarse.inputdump");
    private static final int TDCOARSE_INPUT_DUMP_MAX =
            Integer.getInteger("tdcoarse.inputdump.max", 4096);
    private static final AtomicInteger TDCOARSE_INPUT_DUMP_N = new AtomicInteger();
    private static volatile BufferedWriter TDCOARSE_INPUT_DUMP_W = null;

    /** Opt-in test instrumentation; inert during ordinary generation. */
    private static void tdcoarse$dumpInput(int worldX, int worldZ, float[][][] syn,
                                           int row, int col) {
        if (TDCOARSE_INPUT_DUMP_PATH == null
                || TDCOARSE_INPUT_DUMP_N.get() >= TDCOARSE_INPUT_DUMP_MAX) return;
        try {
            synchronized (TDCOARSE_INPUT_DUMP_N) {
                if (TDCOARSE_INPUT_DUMP_N.getAndIncrement() >= TDCOARSE_INPUT_DUMP_MAX) return;
                if (TDCOARSE_INPUT_DUMP_W == null) {
                    TDCOARSE_INPUT_DUMP_W = new BufferedWriter(
                            new FileWriter(TDCOARSE_INPUT_DUMP_PATH, true));
                    TDCOARSE_INPUT_DUMP_W.write(
                            "world_x,world_z,elev_sqrt,temp_c,temp_std_x100,precip_mm,precip_cv_pct\n");
                }
                TDCOARSE_INPUT_DUMP_W.write(worldX + "," + worldZ + ","
                        + syn[0][row][col] + "," + syn[1][row][col] + ","
                        + syn[2][row][col] + "," + syn[3][row][col] + ","
                        + syn[4][row][col] + "\n");
                TDCOARSE_INPUT_DUMP_W.flush();
            }
        } catch (Throwable throwable) {
            // Test instrumentation must never affect generation.
        }
    }

    @Inject(method = "sample(IIII)[[[F", at = @At("RETURN"))
    private void tdcoarse$paint(int x1, int y1, int x2, int y2,
                                CallbackInfoReturnable<float[][][]> cir) {
        try {
            if (!CoarseConditioningPaint.ready()) return;
            float[][][] syn = cir.getReturnValue();
            if (syn == null || syn.length < 5) return;
            int height = syn[0].length;
            int width = syn[0][0].length;
            int pixelBlocks = CoarseConditioningPaint.pixelBlocks();
            int half = pixelBlocks / 2;
            int painted = 0;
            int sampleX = 0;
            int sampleZ = 0;
            float sampleTemp = 0.0f;
            float sampleTempStd = 0.0f;
            float samplePrecip = 0.0f;
            float samplePrecipCv = 0.0f;
            float sampleElev = 0.0f;
            for (int row = 0; row < height; row++) {
                int worldZ = (y1 + row) * pixelBlocks + half;
                for (int col = 0; col < width; col++) {
                    int worldX = (x1 + col) * pixelBlocks + half;
                    CoarseConditioningPaint.Delta delta =
                            CoarseConditioningPaint.sampleDelta(worldX, worldZ);
                    if (delta == null) continue;
                    if (CoarseConditioningPaint.SET_MODE) {
                        syn[1][row][col] = Math.max(-10.0f, Math.min(40.0f, delta.tempC));
                        if (delta.hasTempStd) {
                            syn[2][row][col] = Math.max(0.0f, delta.tempStdC) * 100.0f;
                        }
                        syn[3][row][col] = Math.max(0.0f, delta.precipMm);
                        if (delta.hasPrecipCv) {
                            syn[4][row][col] = Math.max(0.0f, delta.precipCvPct);
                        }
                        float elevation = delta.elevM;
                        syn[0][row][col] =
                                (float) (Math.signum(elevation) * Math.sqrt(Math.abs(elevation)));
                    } else {
                        if (delta.tempC != 0.0f) {
                            syn[1][row][col] = Math.max(
                                    -10.0f, Math.min(40.0f, syn[1][row][col] + delta.tempC));
                        }
                        if (delta.hasTempStd && delta.tempStdC != 0.0f) {
                            syn[2][row][col] = Math.max(
                                    0.0f, syn[2][row][col] + delta.tempStdC * 100.0f);
                        }
                        if (delta.precipMm != 0.0f) {
                            syn[3][row][col] = Math.max(
                                    0.0f, syn[3][row][col] + delta.precipMm);
                        }
                        if (delta.hasPrecipCv && delta.precipCvPct != 0.0f) {
                            syn[4][row][col] = Math.max(
                                    0.0f, syn[4][row][col] + delta.precipCvPct);
                        }
                        if (delta.elevM != 0.0f) {
                            float encoded = syn[0][row][col];
                            float elevation = Math.signum(encoded) * encoded * encoded + delta.elevM;
                            syn[0][row][col] =
                                    (float) (Math.signum(elevation) * Math.sqrt(Math.abs(elevation)));
                        }
                    }
                    if (painted == 0) {
                        sampleX = worldX;
                        sampleZ = worldZ;
                        sampleTemp = delta.tempC;
                        sampleTempStd = delta.tempStdC;
                        samplePrecip = delta.precipMm;
                        samplePrecipCv = delta.precipCvPct;
                        sampleElev = delta.elevM;
                    }
                    tdcoarse$dumpInput(worldX, worldZ, syn, row, col);
                    painted++;
                }
            }
            if (TDCOARSE_DBG < 24) {
                TDCOARSE_DBG++;
                System.out.println("[TD-Coarse-Paint] tile px(" + x1 + "," + y1 + ") "
                        + width + "x" + height + " painted " + painted + "/" + (height * width)
                        + " px; sample @(" + sampleX + "," + sampleZ + ") T=" + sampleTemp
                        + "C Tstd=" + sampleTempStd + "C P=" + samplePrecip + "mm PCV="
                        + samplePrecipCv + "% E=" + sampleElev + "m");
            }
        } catch (Throwable throwable) {
            // Existing behavior: paint instrumentation must not crash world generation.
        }
    }
}
