/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.st7789.demo;

import com.codeferm.periphery.device.St7789;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Lava Lamp demo with independent blob physics, thermal convection, and splitting/stretching behavior for ST7789.
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "LavaLamp", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Lava lamp simulation with splitting metaballs for ST7789")
public class LavaLamp extends Base {

    /**
     * Increased blob count with smaller individual magnitudes to encourage breaking apart.
     */
    private static final int NUM_BLOBS = 18;

    /**
     * Image buffer for the ST7789 240x320 display.
     */
    private final BufferedImage image;

    /**
     * Direct pixel access for optimized rendering.
     */
    private final int[] pixels;

    /**
     * Blob data: [0]=x, [1]=y, [2]=velocity, [3]=heat, [4]=base magnitude, [5]=buoyancy factor, [6]=stretch factor.
     */
    private final float[][] blobs = new float[NUM_BLOBS][7];

    /**
     * Initialize simulation with randomized independent properties scaled for 240x320.
     */
    public LavaLamp() {
        this.image = new BufferedImage(240, 320, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final var rand = new Random();
        for (var i = 0; i < NUM_BLOBS; i++) {
            resetBlob(i, rand);
        }
    }

    /**
     * Resets or spawns a blob at the bottom thermal zone.
     *
     * @param i Index of the blob.
     * @param rand Random instance.
     */
    private void resetBlob(final int i, final Random rand) {
        blobs[i][0] = 30 + rand.nextInt(180);
        // Start clustered near the bottom heater
        blobs[i][1] = 280 + rand.nextInt(30);
        blobs[i][2] = 0.0f;
        blobs[i][3] = 0.8f + (rand.nextFloat() * 0.2f); // Hot start
        // Smaller base magnitude prevents massive single-blob merging
        blobs[i][4] = 250.0f + rand.nextFloat() * 350.0f;
        blobs[i][5] = 0.04f + rand.nextFloat() * 0.12f;
        blobs[i][6] = 1.0f; // Normal shape
    }

    /**
     * Updates independent blob physics with convection, thermal exchange, and stretching/breaking apart.
     *
     * @param h Screen height.
     */
    private void updatePhysics(final int h) {
        final var rand = new Random();
        for (var i = 0; i < NUM_BLOBS; i++) {
            final var yPos = blobs[i][1];

            // 1. Thermal Exchange based on height position
            if (yPos > h * 0.8f) {
                blobs[i][3] += 0.02f; // Heating up at bottom
            } else if (yPos < h * 0.2f) {
                blobs[i][3] -= 0.01f; // Cooling down at top
            }
            blobs[i][3] = Math.max(0.05f, Math.min(1.0f, blobs[i][3]));

            // 2. Buoyancy & Acceleration
            final var buoyancy = (blobs[i][3] - 0.45f) * blobs[i][5];
            blobs[i][2] += buoyancy;

            // 3. Horizontal Drift / Sway
            blobs[i][0] += (rand.nextFloat() - 0.5f) * 1.2f;
            if (blobs[i][0] < 20) {
                blobs[i][0] = 20;
            }
            if (blobs[i][0] > 220) {
                blobs[i][0] = 220;
            }

            // 4. Viscosity
            blobs[i][2] *= 0.92f;
            blobs[i][1] -= blobs[i][2];

            // 5. Stretching & Breaking Apart dynamics based on speed
            final var speed = Math.abs(blobs[i][2]);
            // Fast movement stretches the blob vertically, effectively thinning it out
            blobs[i][6] = 1.0f + (speed * 0.4f);

            // Boundary constraints & recycling
            if (blobs[i][1] < 15) {
                // Cool down and fall back down
                blobs[i][1] = 15;
                blobs[i][2] *= -0.3f;
                blobs[i][3] = 0.1f;
            }
            if (blobs[i][1] > h - 15) {
                // Re-heat at the bottom floor
                blobs[i][1] = h - 15;
                blobs[i][2] *= -0.2f;
                blobs[i][3] = 0.9f;
            }
        }
    }

    /**
     * Renders metaballs with higher thresholding to maintain discrete, organic wax blobs.
     *
     * @param w Screen width.
     * @param h Screen height.
     */
    private void render(final int w, final int h) {
        for (var y = 0; y < h; y++) {
            final var offset = y * w;
            for (var x = 0; x < w; x++) {
                var sum = 0.0f;
                for (var i = 0; i < NUM_BLOBS; i++) {
                    final var dx = x - blobs[i][0];
                    // Stretch vertical distance calculation based on speed stretch factor
                    final var dy = (y - blobs[i][1]) / blobs[i][6];
                    final var d2 = dx * dx + dy * dy;
                    if (d2 > 0.8f) {
                        sum += blobs[i][4] / d2;
                    }
                }
                // Higher threshold ensures blobs pinch off and separate instead of merging into a solid wall
                if (sum > 1.2f) {
                    final var g = Math.min(255, (int) (sum * 70));
                    pixels[offset + x] = (g << 8);
                } else {
                    // Deep Cyan/Blue background
                    pixels[offset + x] = 0x000033;
                }
            }
        }
    }

    /**
     * Executes the lava lamp demo loop running against the ST7789 display.
     *
     * @param lcd ST7789 driver instance.
     */
    public final void demo(final St7789 lcd) {
        log.info("Starting Splitting Lava Lamp Demo for ST7789...");
        final var w = lcd.getWidth();
        final var h = lcd.getHeight();

        while (isRunning()) {
            updatePhysics(h);
            render(w, h);
            lcd.drawImage(image);
        }
    }

    /**
     * Execution logic for lava lamp demo.
     *
     * @return Exit code.
     * @throws Exception Possible hardware exception.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            demo(getLcd());
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
        System.exit(new CommandLine(new LavaLamp()).execute(args));
    }
}
