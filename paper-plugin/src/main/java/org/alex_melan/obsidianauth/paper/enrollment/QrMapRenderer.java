package org.alex_melan.obsidianauth.paper.enrollment;

import java.awt.Color;
import org.alex_melan.obsidianauth.core.qr.MapPaletteRasterizer;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

/**
 * Paper {@link MapRenderer} that paints the QR pixels onto a 128×128 map canvas exactly
 * once, then keeps the canvas static.
 *
 * <p>Uses {@link MapCanvas#setPixelColor(int, int, Color)} — the deprecated
 * {@code setPixel(byte)} is avoided so this code keeps working through Paper API changes.
 */
public final class QrMapRenderer extends MapRenderer {

    private final boolean[][] grid;
    private volatile boolean dirty = true;

    /** Caller passes the 128×128 grid from {@link MapPaletteRasterizer#rasterize}. */
    public QrMapRenderer(boolean[][] grid) {
        super(false /* contextual = false: shared bitmap, not per-player */);
        if (grid.length != MapPaletteRasterizer.CANVAS_DIMENSION
                || grid[0].length != MapPaletteRasterizer.CANVAS_DIMENSION) {
            throw new IllegalArgumentException("grid must be 128x128");
        }
        this.grid = grid;
    }

    @Override
    public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
        if (!dirty) return;
        for (int x = 0; x < MapPaletteRasterizer.CANVAS_DIMENSION; x++) {
            for (int y = 0; y < MapPaletteRasterizer.CANVAS_DIMENSION; y++) {
                canvas.setPixelColor(x, y, grid[x][y] ? Color.BLACK : Color.WHITE);
            }
        }
        dirty = false;
    }
}
