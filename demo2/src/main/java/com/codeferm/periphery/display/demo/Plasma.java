/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import java.awt.image.DataBufferInt;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Procedural RGB Plasma Effect for color displays utilizing Java 25 FFM driver with dynamic sizing.
 * <p>
 * This demo performs high-frequency pixel manipulation directly on the {@link java.awt.image.DataBufferInt} raster array and pushes
 * frames to the native display bus with zero heap allocation per frame.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Plasma", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Procedural RGB Plasma Effect with dynamic sizing")
public class Plasma extends Base {

    /**
     * Internal animation time tracking variable.
     */
    private float time = 0.0f;

    /**
     * Total frames processed counter.
     */
    private int frameCount = 0;

    /**
     * Maximum frames to run before auto-termination (60 seconds at target FPS).
     */
    private int maxFrames = 0;

    /**
     * Pre-allocated pixel data buffer reference extracted from the raster.
     */
    private int[] pixelData;

    /**
     * Initializes the plasma demo state and caches raster data references.
     *
     * @param w Display width.
     * @param h Display height.
     * @param fullReset True if structural states should be completely reset.
     */
    public final void initLevel(final int w, final int h, final boolean fullReset) {
        if (fullReset) {
            time = 0.0f;
            frameCount = 0;
            maxFrames = getFps() * 60;
        }
        final var canvas = getImage();
        pixelData = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
    }

    /**
     * Updates the plasma pattern calculations and renders directly to the pixel array.
     *
     * @param w Display width.
     * @param h Display height.
     */
    private void updateLogic(final int w, final int h) {
        for (var y = 0; y < h; y++) {
            // Pre-calculate Y components for the inner loop optimization
            final var vY = Math.sin(y / 8.0 + time / 2.0);
            final var cyBase = y + 8.0 * Math.cos(time / 2.0);
            for (var x = 0; x < w; x++) {
                // Combine interference patterns (Plasma math)
                var v = Math.sin(x / 16.0 + time) + vY;
                v += Math.sin((x + y + time) / 16.0);
                final var cx = x + 8.0 * Math.sin(time / 3.0);
                v += Math.sin(Math.sqrt(cx * cx + cyBase * cyBase + 1.0) / 8.0);

                // Generate RGB components based on wave value 'v' with phase shifting
                final var r = (int) (128.0 + 127.0 * Math.sin(v * Math.PI + time));
                final var g = (int) (128.0 + 127.0 * Math.sin(v * Math.PI + time / 2.0 + Math.PI / 2.0));
                final var b = (int) (128.0 + 127.0 * Math.sin(v * Math.PI + time / 4.0 + Math.PI));

                // Direct array write to avoid GC pressure
                pixelData[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        time += 0.08f;
        frameCount++;
    }

    /**
     * Main execution loop adhering to the base class lifecycle and timing standards.
     *
     * @return Exit code.
     * @throws Exception Hardware or timing exception.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        final var w = getWidth();
        final var h = getHeight();
        final var targetFps = getFps();
        initLevel(w, h, true);

        log.info("Starting RGB Plasma (60s run) at {} FPS with resolution {}x{}...", targetFps, w, h);

        try {
            final var display = getDisplay();
            while (isRunning() && frameCount < maxFrames && !Thread.currentThread().isInterrupted()) {
                final var startFrame = System.nanoTime();

                updateLogic(w, h);
                display.drawImage(getImage(), 0, 0, w, h);

                final var endFrame = System.nanoTime();
                final var workTimeMs = (endFrame - startFrame) / 1_000_000L;
                final var sleepTime = (1000L / targetFps) - workTimeMs;

                if (sleepTime > 0) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(sleepTime);
                    } catch (final InterruptedException e) {
                        log.info("Game loop interrupted, shutting down...");
                        Thread.currentThread().interrupt();
                    }
                }
            }
            log.info("Demo complete ({} frames processed).", frameCount);
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Argument list.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Plasma()).execute(args));
    }
}
