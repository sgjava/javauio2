/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.awt.image.DataBufferInt;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Procedural RGB Plasma Effect for SSD1331 utilizing Java 25 FFM driver.
 * <p>
 * This demo performs high-frequency pixel manipulation directly on the {@link java.awt.image.DataBufferInt} and pushes frames to
 * the native SPI bus with zero heap allocation per frame.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 2.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Plasma", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class Plasma extends Base {

    /**
     * Executes the plasma animation logic.
     * <p>
     * The effect uses sine wave interference patterns to generate smooth color transitions.
     * </p>
     *
     * @param oled SSD1331 driver instance.
     * @throws Exception On SPI or timing errors.
     */
    public void demo(final Ssd1331 oled) throws Exception {
        // Access cached dimensions and resources from Base class
        final var w = getWidth();
        final var h = getHeight();
        final var targetFps = getFps();
        final var maxFrames = targetFps * 60; // 60 second run
        var frameCount = 0;
        var time = 0.0f;
        // Use the BufferedImage pre-allocated in Base to avoid GC pressure [cite: 2026-02-13]
        final var canvas = getImage();
        final int[] pixelData = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        log.info("Starting RGB Plasma (60s run) at {} FPS...", targetFps);
        while (frameCount < maxFrames) {
            final var startFrame = System.nanoTime();
            for (var y = 0; y < h; y++) {
                // Pre-calculate Y components for the inner loop
                final var vY = Math.sin(y / 8.0 + time / 2.0);
                final var cyBase = y + 8.0 * Math.cos(time / 2.0);
                for (var x = 0; x < w; x++) {
                    // Combine interference patterns (Plasma math)
                    var v = Math.sin(x / 16.0 + time) + vY;
                    v += Math.sin((x + y + time) / 16.0);
                    final var cx = x + 8.0 * Math.sin(time / 3.0);
                    v += Math.sin(Math.sqrt(cx * cx + cyBase * cyBase + 1.0) / 8.0);
                    // Generate RGB components based on wave value 'v'
                    // Math.PI * v provides the phase shift
                    final var r = (int) (128.0 + 127.0 * Math.sin(v * Math.PI + time));
                    final var g = (int) (128.0 + 127.0 * Math.sin(v * Math.PI + time / 2.0 + Math.PI / 2.0));
                    final var b = (int) (128.0 + 127.0 * Math.sin(v * Math.PI + time / 4.0 + Math.PI));
                    // Direct write to the BufferedImage's internal array
                    pixelData[y * w + x] = (r << 16) | (g << 8) | b;
                }
            }

            // Push the procedural image to the hardware using FFM
            oled.drawImage(canvas);
            time += 0.08f;
            frameCount++;
            // Precise FPS timing: calculate sleep based on frame processing time
            final var endFrame = System.nanoTime();
            final var workTimeMs = (endFrame - startFrame) / 1_000_000L;
            final var sleepTime = (1000L / targetFps) - workTimeMs;

            if (sleepTime > 0) {
                TimeUnit.MILLISECONDS.sleep(sleepTime);
            }
        }
        log.info("Demo complete ({} frames processed).", frameCount);
    }

    /**
     * Execution logic for plasma demo.
     *
     * @return Exit code.
     * @throws Exception Possible hardware exception.
     */
    @Override
    public Integer call() throws Exception {
        // Initializes hardware (SPI/GPIO handles) and Java2D canvas
        super.call();
        try {
            demo(getOled());
        } finally {
            // Clean up native memory segments and handles
            done();
        }
        return 0;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Argument list.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new Plasma()).execute(args));
    }
}
