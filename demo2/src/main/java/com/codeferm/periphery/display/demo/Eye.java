/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * High-performance realistic animated eyeball demo featuring procedural 3D shading, dynamic gaze shifting, and natural blinking
 * eyelids using FFM and a producer-consumer background rendering pipeline.
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Eye", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Realistic animated eyeball with blinking eyelids and random gaze tracking")
public class Eye extends Base {

    /**
     * Picocli command spec for inspecting parse results.
     */
    @Spec
    private CommandSpec spec;

    /**
     * Random generator for gaze direction and blinking behavior.
     */
    private final Random random = new Random();

    /**
     * Eye state container for smooth interpolation and blinking.
     */
    private static class EyeState {

        double targetGazeX;
        double targetGazeY;
        double currentGazeX;
        double currentGazeY;
        double blinkProgress; // 0.0 (open) to 1.0 (fully closed)
        int stateTicks;
        boolean blinking;

        EyeState(final double targetGazeX, final double targetGazeY, final double currentGazeX, final double currentGazeY,
                final double blinkProgress, final int stateTicks, final boolean blinking) {
            this.targetGazeX = targetGazeX;
            this.targetGazeY = targetGazeY;
            this.currentGazeX = currentGazeX;
            this.currentGazeY = currentGazeY;
            this.blinkProgress = blinkProgress;
            this.stateTicks = stateTicks;
            this.blinking = blinking;
        }
    }

    /**
     * Main rendering and animation loop using a producer-consumer pattern.
     *
     * @param display Abstract color display driver instance used for foreign memory transfers.
     */
    public final void runDemo(final AbstractColorDisplay display) {
        final var width = getWidth();
        final var height = getHeight();
        final var centerX = width / 2.0;
        final var centerY = height / 2.0;
        final var eyeRadius = (int) (Math.min(width, height) * 0.45);

        // Initialize eye state
        final var eye = new EyeState(0.0, 0.0, 0.0, 0.0, 0.0, 0, false);

        final var frameDelay = 1000 / getFps();
        log.info("Starting Eyeball Animation via FFM at {} FPS with resolution {}x{}", getFps(), width, height);

        // Double-buffering queue to decouple frame generation from hardware transmission
        final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(2);

        // Pre-allocate dual buffers for zero-allocation handoff
        final BufferedImage bufferA = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage bufferB = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Fetch base snapshot handler image buffer if snapshot flag is enabled
        final BufferedImage snapshotBi = getImage();

        // Background Producer Thread (Procedural Eyeball & Eyelid Rendering)
        final Thread renderThread = new Thread(() -> {
            var activeBuffer = bufferA;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                // Update animation state per frame
                if (!eye.blinking && random.nextDouble() < 0.02) {
                    eye.blinking = true;
                }

                if (eye.blinking) {
                    eye.blinkProgress += 0.2;
                    if (eye.blinkProgress >= 1.0) {
                        eye.blinkProgress = 1.0;
                        eye.blinking = false; // start opening
                    }
                } else if (eye.blinkProgress > 0.0) {
                    eye.blinkProgress -= 0.2;
                    if (eye.blinkProgress < 0.0) {
                        eye.blinkProgress = 0.0;
                    }
                }

                // Gaze shifting logic
                if (eye.stateTicks <= 0) {
                    eye.targetGazeX = (random.nextDouble() - 0.5) * 45.0;
                    eye.targetGazeY = (random.nextDouble() - 0.5) * 45.0;
                    eye.stateTicks = 30 + random.nextInt(60);
                }
                eye.currentGazeX += (eye.targetGazeX - eye.currentGazeX) * 0.15;
                eye.currentGazeY += (eye.targetGazeY - eye.currentGazeY) * 0.15;
                eye.stateTicks--;

                final var pixels = ((DataBufferInt) activeBuffer.getRaster().getDataBuffer()).getData();
                final var socketColor = (20 << 16) | (15 << 8) | 12; // dark eye socket background

                // Render background / eye socket
                for (var i = 0; i < pixels.length; i++) {
                    pixels[i] = socketColor;
                }

                final var irisCenterX = centerX + eye.currentGazeX;
                final var irisCenterY = centerY + eye.currentGazeY;
                final var irisRadius = (int) (eyeRadius * 0.38);
                final var pupilRadius = (int) (irisRadius * 0.45);

                // Render eyeball pixels
                for (var y = 0; y < height; y++) {
                    final var dy = y - centerY;
                    final var rowOffset = y * width;
                    for (var x = 0; x < width; x++) {
                        final var dx = x - centerX;
                        final var distSq = dx * dx + dy * dy;

                        if (distSq <= eyeRadius * eyeRadius) {
                            // 3D Spherical Shading on Sclera (White of the eye)
                            final var distFromCenter = Math.sqrt(distSq);
                            final var shade = Math.clamp(1.0 - (distFromCenter / eyeRadius) * 0.25, 0.7, 1.0);

                            var r = (int) (245 * shade);
                            var g = (int) (245 * shade);
                            var b = (int) (240 * shade);

                            // Check iris
                            final var irisDx = x - irisCenterX;
                            final var irisDy = y - irisCenterY;
                            final var irisDistSq = irisDx * irisDx + irisDy * irisDy;

                            if (irisDistSq <= irisRadius * irisRadius) {
                                // Iris color (Vibrant Hazel/Blue gradient with radial texture lines)
                                final var angle = Math.atan2(irisDy, irisDx);
                                final var radialPattern = (int) (Math.sin(angle * 12.0) * 15);
                                final var irisShade = Math.clamp(1.0 - (Math.sqrt(irisDistSq) / irisRadius) * 0.4, 0.5, 1.0);

                                r = Math.clamp((int) ((40 + radialPattern) * irisShade * 1.5), 0, 255);
                                g = Math.clamp((int) ((110 + radialPattern) * irisShade * 1.5), 0, 255);
                                b = Math.clamp((int) ((190 + radialPattern) * irisShade * 1.5), 0, 255);

                                // Check pupil
                                if (irisDistSq <= pupilRadius * pupilRadius) {
                                    r = 10;
                                    g = 10;
                                    b = 10;

                                    // Specular highlight reflection dot
                                    if (x < irisCenterX - pupilRadius * 0.3 && y < irisCenterY - pupilRadius * 0.3) {
                                        r = 255;
                                        g = 255;
                                        b = 255;
                                    }
                                }
                            }

                            pixels[rowOffset + x] = (r << 16) | (g << 8) | b;
                        }
                    }
                }

                // Render Eyelids (Skin tone curves sweeping from top and bottom)
                if (eye.blinkProgress > 0.0) {
                    final var lidHeight = (int) (eyeRadius * eye.blinkProgress);
                    final var skinR = 210;
                    final var skinG = 165;
                    final var skinB = 135;
                    final var skinColor = (skinR << 16) | (skinG << 8) | skinB;

                    // Upper eyelid sweep
                    for (var y = 0; y < centerY - eyeRadius + lidHeight; y++) {
                        final var rowOffset = y * width;
                        for (var x = 0; x < width; x++) {
                            final var dx = x - centerX;
                            final var dy = y - centerY;
                            if (dx * dx + dy * dy <= eyeRadius * eyeRadius + 200) {
                                pixels[rowOffset + x] = skinColor;
                            }
                        }
                    }

                    // Lower eyelid sweep
                    for (var y = (int) (centerY + eyeRadius - lidHeight); y < height; y++) {
                        final var rowOffset = y * width;
                        for (var x = 0; x < width; x++) {
                            final var dx = x - centerX;
                            final var dy = y - centerY;
                            if (dx * dx + dy * dy <= eyeRadius * eyeRadius + 200) {
                                pixels[rowOffset + x] = skinColor;
                            }
                        }
                    }
                }

                // Mirror into snapshot buffer if enabled
                if (snapshotBi != null) {
                    final var snapPixels = ((DataBufferInt) snapshotBi.getRaster().getDataBuffer()).getData();
                    System.arraycopy(pixels, 0, snapPixels, 0, pixels.length);
                }

                try {
                    frameQueue.put(activeBuffer);
                    activeBuffer = (activeBuffer == bufferA) ? bufferB : bufferA;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Eye-Worker");

        renderThread.start();

        // Main Consumer Loop (Pacing & Hardware Display Transfer)
        try {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                final var startTime = System.currentTimeMillis();

                try {
                    final BufferedImage frame = frameQueue.poll(frameDelay, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        display.drawImage(frame, 0, 0, width, height);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                final var diff = System.currentTimeMillis() - startTime;
                if (diff < frameDelay) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(frameDelay - diff);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            renderThread.interrupt();
            try {
                renderThread.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Entry point for CLI execution via Picocli.
     *
     * @return Exit code.
     * @throws Exception possible hardware or foreign memory exception.
     */
    @Override
    public final Integer call() throws Exception {
        if (!spec.commandLine().getParseResult().hasMatchedOption("fps")) {
            setFps(30);
        }
        super.call();
        try {
            runDemo(getDisplay());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Main method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Eye()).execute(args));
    }
}
