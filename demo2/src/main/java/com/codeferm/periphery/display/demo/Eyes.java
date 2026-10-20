/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.Gc9a01;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Dual animated eyeball demo featuring synchronized human-like conjugate gaze, simultaneous blinking, and high-detail procedural
 * realistic iris textures across two independent displays using parallel producer-consumer background rendering pipelines.
 *
 * @author Steven P. Goldsmith
 * @version 1.3.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Eyes", mixinStandardHelpOptions = true, version = "1.3.0-SNAPSHOT",
        description = "Dual synchronized animated eyeballs with high-detail realistic iris textures")
@Getter
@Setter
public class Eyes extends Base {

    /**
     * Picocli command spec for inspecting parse results.
     */
    @Spec
    private CommandSpec spec;

    /**
     * Second display SPI device node.
     */
    @Option(names = {"-d2", "--device2"}, description = "SPI device for second eye (default: ${DEFAULT-VALUE})")
    private String device2 = "/dev/spidev1.1";

    /**
     * Second display Data/Command GPIO pin.
     */
    @Option(names = {"-dc2"}, description = "Data/Command GPIO pin for second eye (default: ${DEFAULT-VALUE})")
    private int dcPin2 = 11;

    /**
     * Second display Reset GPIO pin.
     */
    @Option(names = {"-res2"}, description = "Reset GPIO pin for second eye (default: ${DEFAULT-VALUE})")
    private int rstPin2 = 199;

    /**
     * Second display SPI speed in Hz.
     */
    @Option(names = {"-s2", "--spi-speed2"}, description = "SPI speed for second eye in Hz (default: ${DEFAULT-VALUE})")
    private int spiSpeed2 = 32000000;

    /**
     * Eye color theme: blue, amber, skeletal.
     */
    @Option(names = {"-es", "--eye-style"}, description = "Eye color theme: blue, amber, skeletal (default: ${DEFAULT-VALUE})")
    private String eyeStyle = "blue";

    /**
     * Random generator for gaze direction and blinking behavior.
     */
    private final Random random = new Random();

    /**
     * Shared eye state container for synchronized conjugate gaze and blinking.
     */
    private static class EyeState {

        double targetGazeX;
        double targetGazeY;
        double currentGazeX;
        double currentGazeY;
        double blinkProgress; // 0.0 (open) to 1.0 (fully closed)
        int stateTicks;
        boolean blinking;
        long lastUpdateFrame = -1;

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

        /**
         * Updates the shared gaze and blink physics exactly once per frame tick.
         *
         * @param rand Random generator.
         * @param frameCount Current frame sequence number.
         */
        synchronized void update(final Random rand, final long frameCount) {
            if (lastUpdateFrame == frameCount) {
                return; // Prevent double updates from dual worker threads
            }
            lastUpdateFrame = frameCount;

            if (!blinking && rand.nextDouble() < 0.02) {
                blinking = true;
            }

            if (blinking) {
                blinkProgress += 0.25;
                if (blinkProgress >= 1.0) {
                    blinkProgress = 1.0;
                    blinking = false; // start opening
                }
            } else if (blinkProgress > 0.0) {
                blinkProgress -= 0.25;
                if (blinkProgress < 0.0) {
                    blinkProgress = 0.0;
                }
            }

            // Gaze shifting logic
            if (stateTicks <= 0) {
                targetGazeX = (rand.nextDouble() - 0.5) * 45.0;
                targetGazeX = Math.clamp(targetGazeX, -22.0, 22.0);
                targetGazeY = (rand.nextDouble() - 0.5) * 45.0;
                targetGazeY = Math.clamp(targetGazeY, -22.0, 22.0);
                stateTicks = 30 + rand.nextInt(60);
            }
            currentGazeX += (targetGazeX - currentGazeX) * 0.15;
            currentGazeY += (targetGazeY - currentGazeY) * 0.15;
            stateTicks--;
        }
    }

    /**
     * Main rendering and animation loop for a single eye using a producer-consumer pattern.
     *
     * @param display Abstract color display driver instance used for foreign memory transfers.
     * @param eye Shared eye state container.
     * @param threadName Name of the worker thread.
     */
    private void runEyeDemo(final AbstractColorDisplay display, final EyeState eye, final String threadName) {
        final var width = getWidth();
        final var height = getHeight();
        final var centerX = width / 2.0;
        final var centerY = height / 2.0;
        final var eyeRadius = (int) (Math.min(width, height) * 0.45);

        final var frameDelay = 1000 / getFps();
        log.info("Starting Synchronized Realistic Eyeball Animation ({}) [{}] via FFM at {} FPS", threadName, eyeStyle, getFps());

        // Double-buffering queue to decouple frame generation from hardware transmission
        final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(2);

        // Pre-allocate dual buffers for zero-allocation handoff
        final BufferedImage bufferA = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage bufferB = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Background Producer Thread (Procedural Eyeball & Eyelid Rendering)
        final Thread renderThread = new Thread(() -> {
            var activeBuffer = bufferA;
            long frameCount = 0;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                // Update shared human-like conjugate gaze and blink state once per frame
                eye.update(random, frameCount++);

                final var pixels = ((DataBufferInt) activeBuffer.getRaster().getDataBuffer()).getData();
                final var socketColor = 0x020204; // Deep socket shadow

                for (var i = 0; i < pixels.length; i++) {
                    pixels[i] = socketColor;
                }

                final var irisCenterX = centerX + eye.currentGazeX;
                final var irisCenterY = centerY + eye.currentGazeY;
                final var irisRadius = (int) (eyeRadius * 0.40);
                final var pupilRadius = (int) (irisRadius * 0.42);

                // Render eyeball pixels
                for (var y = 0; y < height; y++) {
                    final var dy = y - centerY;
                    final var rowOffset = y * width;
                    for (var x = 0; x < width; x++) {
                        final var dx = x - centerX;
                        final var distSq = dx * dx + dy * dy;

                        if (distSq <= eyeRadius * eyeRadius) {
                            // High-detail 3D Sclera (White of the eye) with subtle blood vessel hints near edges
                            final var distFromCenter = Math.sqrt(distSq);
                            final var scleraShade = Math.clamp(1.0 - (distFromCenter / eyeRadius) * 0.22, 0.75, 1.0);

                            var r = (int) (245 * scleraShade);
                            var g = (int) (245 * scleraShade);
                            var b = (int) (240 * scleraShade);

                            // Check iris
                            final var irisDx = x - irisCenterX;
                            final var irisDy = y - irisCenterY;
                            final var irisDistSq = irisDx * irisDx + irisDy * irisDy;

                            if (irisDistSq <= irisRadius * irisRadius) {
                                final var irisDist = Math.sqrt(irisDistSq);
                                final var angle = Math.atan2(irisDy, irisDx);

                                // Rich radial striations (fine fibers in the iris)
                                final var striations = Math.sin(angle * 36.0) * 18.0 + Math.cos(angle * 72.0) * 8.0;
                                final var irisNormDist = irisDist / irisRadius;
                                final var irisShade = Math.clamp(1.0 - irisNormDist * 0.35, 0.5, 1.0);

                                if ("amber".equalsIgnoreCase(eyeStyle)) {
                                    // Warm amber/brown detailed iris
                                    r = Math.clamp((int) ((160 + striations * 1.2) * irisShade * 1.3), 0, 255);
                                    g = Math.clamp((int) ((100 + striations) * irisShade * 1.2), 0, 255);
                                    b = Math.clamp((int) ((35 + striations * 0.5) * irisShade), 0, 255);
                                } else if ("skeletal".equalsIgnoreCase(eyeStyle)) {
                                    // Fiery eerie skeletal gold/orange
                                    r = Math.clamp((int) ((230 + striations) * irisShade), 0, 255);
                                    g = Math.clamp((int) ((140 + striations) * irisShade), 0, 255);
                                    b = Math.clamp((int) ((40 + striations * 0.5) * irisShade), 0, 255);
                                } else {
                                    // Vibrant realistic piercing blue iris
                                    r = Math.clamp((int) ((30 + striations * 0.8) * irisShade * 1.2), 0, 255);
                                    g = Math.clamp((int) ((120 + striations * 1.2) * irisShade * 1.3), 0, 255);
                                    b = Math.clamp((int) ((210 + striations * 1.5) * irisShade * 1.4), 0, 255);
                                }

                                // Check pupil
                                if (irisDistSq <= pupilRadius * pupilRadius) {
                                    r = 8;
                                    g = 8;
                                    b = 10;

                                    // Crisp specular reflection highlight dot for glossy realism
                                    if (x < irisCenterX - pupilRadius * 0.28 && y < irisCenterY - pupilRadius * 0.28) {
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

                // Render Eyelids
                if (eye.blinkProgress > 0.0) {
                    final var lidHeight = (int) (eyeRadius * eye.blinkProgress);
                    final var skinColor = 0x1A181C; // Dark shadowed socket lid

                    // Upper eyelid sweep
                    for (var y = 0; y < centerY - eyeRadius + lidHeight; y++) {
                        final var rowOffset = y * width;
                        for (var x = 0; x < width; x++) {
                            final var dx = x - centerX;
                            final var dy = y - centerY;
                            if (dx * dx + dy * dy <= eyeRadius * eyeRadius + 250) {
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
                            if (dx * dx + dy * dy <= eyeRadius * eyeRadius + 250) {
                                pixels[rowOffset + x] = skinColor;
                            }
                        }
                    }
                }

                try {
                    frameQueue.put(activeBuffer);
                    activeBuffer = (activeBuffer == bufferA) ? bufferB : bufferA;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, threadName + "-Worker");

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
     * Entry point for CLI execution via Picocli, spawning parallel threads for both eyes sharing a single synchronized eye state.
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

        // Primary Display (Eye 1) created via Base
        final AbstractColorDisplay display1 = getDisplay();

        // Secondary Display (Eye 2) initialized using the correct 6-argument Gc9a01 constructor
        final AbstractColorDisplay display2 = new Gc9a01(
                device2,
                0,
                spiSpeed2,
                getGpioDevice(),
                dcPin2,
                rstPin2
        );

        // Single shared eye state for conjugate gaze and synchronized blinking
        final EyeState sharedEyeState = new EyeState(0.0, 0.0, 0.0, 0.0, 0.0, 0, false);

        try {
            final Thread eye1Thread = new Thread(() -> runEyeDemo(display1, sharedEyeState, "Eye1"), "Eye1-Thread");
            final Thread eye2Thread = new Thread(() -> runEyeDemo(display2, sharedEyeState, "Eye2"), "Eye2-Thread");

            eye1Thread.start();
            eye2Thread.start();

            try {
                while (isRunning() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            eye1Thread.interrupt();
            eye2Thread.interrupt();
            try {
                eye1Thread.join();
                eye2Thread.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } finally {
            try {
                display2.close();
            } catch (final Exception e) {
                log.error("Error closing display 2", e);
            }
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
        System.exit(new CommandLine(new Eyes()).execute(args));
    }
}
