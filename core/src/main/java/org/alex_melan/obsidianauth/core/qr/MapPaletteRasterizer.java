package org.alex_melan.obsidianauth.core.qr;

import com.google.zxing.common.BitMatrix;

/**
 * Converts a ZXing {@link BitMatrix} into a 128×128 boolean grid suitable for painting
 * onto a Paper {@code MapCanvas}.
 *
 * <p>Returns {@code true} where the QR pixel is dark (foreground) and {@code false} where
 * it is light (background). The Paper-side {@code QrMapRenderer} maps these to
 * {@code Color.BLACK} / {@code Color.WHITE} via {@code MapCanvas.setPixelColor}.
 *
 * <p>This class lives in {@code :core} so it has no Bukkit dependency — the Paper layer
 * does the final {@code setPixelColor} call.
 */
public final class MapPaletteRasterizer {

    public static final int CANVAS_DIMENSION = 128;

    private MapPaletteRasterizer() {
        // static-only
    }

    /**
     * Rasterise the matrix to a 128×128 boolean grid. The matrix is expected to already be
     * sized to 128×128 (call {@link QrEncoder#encode(String)} with the default size); a
     * mismatched size raises {@link IllegalArgumentException}.
     */
    public static boolean[][] rasterize(BitMatrix matrix) {
        if (matrix.getWidth() != CANVAS_DIMENSION || matrix.getHeight() != CANVAS_DIMENSION) {
            throw new IllegalArgumentException(
                    "expected " + CANVAS_DIMENSION + "x" + CANVAS_DIMENSION + " matrix, got "
                            + matrix.getWidth() + "x" + matrix.getHeight());
        }
        boolean[][] out = new boolean[CANVAS_DIMENSION][CANVAS_DIMENSION];
        for (int x = 0; x < CANVAS_DIMENSION; x++) {
            for (int y = 0; y < CANVAS_DIMENSION; y++) {
                out[x][y] = matrix.get(x, y);
            }
        }
        return out;
    }
}
