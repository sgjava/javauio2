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
 * Procedural Plasma Effect for I2C OLED. Target: 30 FPS (Optimized for 400 KHz I2C). Handles asynchronous signal termination
 * gracefully to avoid memory segmentation faults.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Plasma", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class Plasma extends Base {

    /**
     * Target Frames Per Second option.
     */
    @Option(names = {"-f", "--fps"}, description = "Target frames per second", defaultValue = "30")
    public int fps;

    /**
     * Asynchronous loop termination flag monitored by the active rendering thread.
     */
    private volatile boolean running = true;

    /**
     * Default constructor initializing the class properties.
     */
    public Plasma() {
        super();
    }

    /**
     * Executes the plasma animation loop. Implements dynamic calculations while tracking interrupt status parameters and handling
     * SIGINT triggers cleanly.
     *
     * @param u8g2 MemorySegment handle to the underlying native u8g2 struct block.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var w = getWidth();
        final var h = getHeight();
        final var maxFrames = fps * 60;
        var frameCount = 0;
        var time = 0.0f;

        log.info("Starting {} FPS Plasma (60s run)...", fps);

        // Register the shutdown hook to toggle the loop state instead of destroying handles
        final var mainThread = Thread.currentThread();
        final var shutdownHook = new Thread(() -> {
            log.debug("Interrupt received, signaling loop shutdown sequence...");
            this.running = false;
            mainThread.interrupt();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            while (running && (frameCount < maxFrames)) {
                U8g2.u8g2_ClearBuffer(u8g2);

                for (var y = 0; y < h; y++) {
                    // Pre-calculating Y-based components outside the X-loop for maximum throughput
                    final var vY = Math.sin((y / 8.0) + (time / 2.0));
                    final var cyBase = y + (8.0 * Math.cos(time / 2.0));

                    for (var x = 0; x < w; x++) {
                        // Combine vertical, horizontal, and diagonal sine waves
                        var v = Math.sin((x / 16.0) + time) + vY;
                        v += Math.sin((x + y + time) / 16.0);

                        // Circular/Radial interference component
                        final var cx = x + (8.0 * Math.sin(time / 3.0));
                        v += Math.sin(Math.sqrt((cx * cx) + (cyBase * cyBase) + 1.0) / 8.0);

                        // Threshold value to 1-bit monochrome display
                        if (v > 0) {
                            U8g2.u8g2_DrawPixel(u8g2, (short) x, (short) y);
                        }
                    }
                }

                U8g2.u8g2_SendBuffer(u8g2);

                time += 0.08f;
                frameCount++;

                // Lock loop frame step intervals
                Thread.sleep(1000L / fps);
            }
        } catch (InterruptedException e) {
            log.debug("Rendering loop thread execution interrupted during shutdown step.");
        } finally {
            // Safely de-register hook if loop terminated naturally by timeframe bounds
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // Ignore failure if JVM termination processing has already initialized
            }
            log.info("Demo complete.");
        }
    }

    /**
     * Main parsing, error tracking, and setup initialization entry point.
     *
     * @param args Array argument options parameter list matching base configuration syntax.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Plasma()).execute(args));
    }
}
