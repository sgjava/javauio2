/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.lang.foreign.MemorySegment;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Procedural Plasma Effect for I2C OLED. Target: 30 FPS (Optimized for 400 KHz I2C).
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Plasma", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class Plasma extends Base {

    /**
     * FPS.
     */
    @Option(names = {"-f", "--fps"}, description = "Target frames per second", defaultValue = "30")
    public int fps;

    /**
     * Executes the plasma animation logic. Base class handles display init/teardown and calls this method.
     *
     * @param u8g2 MemorySegment handle to the u8g2 struct.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var w = getWidth();
        final var h = getHeight();
        final var maxFrames = fps * 60;
        var frameCount = 0;
        var time = 0.0f;

        log.info("Starting 30 FPS Plasma (60s run)...");

        while (frameCount < maxFrames) {
            U8g2.u8g2_ClearBuffer(u8g2);

            for (var y = 0; y < h; y++) {
                // Pre-calculating Y-based components outside the X-loop for speed
                var vY = Math.sin(y / 8.0 + time / 2.0);
                var cyBase = y + 8.0 * Math.cos(time / 2.0);

                for (var x = 0; x < w; x++) {
                    // Combine vertical, horizontal, and diagonal sine waves
                    var v = Math.sin(x / 16.0 + time) + vY;
                    v += Math.sin((x + y + time) / 16.0);

                    // Circular/Radial interference component
                    var cx = x + 8.0 * Math.sin(time / 3.0);
                    v += Math.sin(Math.sqrt(cx * cx + cyBase * cyBase + 1.0) / 8.0);

                    // Threshold value to 1-bit monochrome
                    if (v > 0) {
                        U8g2.u8g2_DrawPixel(u8g2, (short) x, (short) y);
                    }
                }
            }

            U8g2.u8g2_SendBuffer(u8g2);

            time += 0.08f;
            frameCount++;

            // Maintain timing for FPS
            try {
                Thread.sleep(1000L / fps);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Demo complete.");
    }

    /**
     * Main parsing, error handling and handling user requests for usage help or version help are done with one line of code.
     *
     * @param args Argument list.
     */
    public static void main(String... args) {
        System.exit(new CommandLine(new Plasma()).execute(args));
    }
}
