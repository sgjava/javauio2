/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Lava Lamp demo with independent blob physics and randomized thermal properties.
 * <p>
 * Simulates a variety of blob sizes and speeds to mimic natural convection. Uses a pre-allocated {@code int[]} buffer.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "LavaLamp", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class LavaLamp extends Base {

    /**
     * Increased blob count for more independent movement.
     */
    private static final int NUM_BLOBS = 8;

    /**
     * Image buffer for the SSD1331 96x64 display.
     */
    private final BufferedImage image;

    /**
     * Direct pixel access for optimized rendering.
     */
    private final int[] pixels;

    /**
     * Blob data: [0]=x, [1]=y, [2]=velocity, [3]=heat, [4]=magnitude, [5]=buoyancy factor.
     */
    private final float[][] blobs = new float[NUM_BLOBS][6];

    /**
     * Initialize simulation with randomized independent properties.
     */
    public LavaLamp() {
        this.image = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final var rand = new Random();
        for (var i = 0; i < NUM_BLOBS; i++) {
            // Random horizontal placement across the 96px width
            blobs[i][0] = 10 + rand.nextInt(76);
            blobs[i][1] = rand.nextInt(64);
            blobs[i][2] = 0.0f;
            // Random starting heat for asynchronous rising/falling
            blobs[i][3] = rand.nextFloat();
            // Varied sizes: 25.0 (small) to 75.0 (medium), prevents screen flooding
            blobs[i][4] = 25.0f + rand.nextFloat() * 50.0f;
            // Buoyancy factor: some blobs are naturally "lighter" than others
            blobs[i][5] = 0.05f + rand.nextFloat() * 0.15f;
        }
    }

    /**
     * Updates independent blob physics with convection and randomized speed.
     *
     * @param h Screen height.
     */
    private void updatePhysics(final int h) {
        final var rand = new Random();
        for (var i = 0; i < NUM_BLOBS; i++) {
            final var yPos = blobs[i][1];
            // 1. Individual Thermal Exchange
            if (yPos > h * 0.75f) {
                blobs[i][3] += 0.01f + (rand.nextFloat() * 0.02f);
            } else if (yPos < h * 0.25f) {
                blobs[i][3] -= 0.005f + (rand.nextFloat() * 0.01f);
            }
            blobs[i][3] = Math.max(0.1f, Math.min(1.0f, blobs[i][3]));
            // 2. Localized Buoyancy: Uses the unique factor for that blob
            final var buoyancy = (blobs[i][3] - 0.5f) * blobs[i][5];
            blobs[i][2] += buoyancy;
            // 3. Horizontal Sway: Adds randomness so they don't move in a straight line
            blobs[i][0] += (rand.nextFloat() - 0.5f) * 0.4f;
            if (blobs[i][0] < 10) {
                blobs[i][0] = 10;
            }
            if (blobs[i][0] > 86) {
                blobs[i][0] = 86;
            }
            // 4. Viscosity and movement
            blobs[i][2] *= 0.94f;
            blobs[i][1] -= blobs[i][2];
            // Boundary constraints
            if (blobs[i][1] < 4) {
                blobs[i][1] = 4;
                blobs[i][2] *= -0.2f;
            }
            if (blobs[i][1] > h - 4) {
                blobs[i][1] = h - 4;
                blobs[i][2] *= -0.2f;
            }
        }
    }

    /**
     * Renders metaballs with thresholding for the "wax" look.
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
                    final var dy = y - blobs[i][1];
                    final var d2 = dx * dx + dy * dy;
                    if (d2 > 0.5f) {
                        sum += blobs[i][4] / d2;
                    }
                }
                if (sum > 0.85f) {
                    // Vivid Green blobs from the photo
                    final var g = Math.min(255, (int) (sum * 130));
                    pixels[offset + x] = (g << 8);
                } else {
                    // Deep Cyan/Blue background
                    pixels[offset + x] = 0x000033;
                }
            }
        }
    }

    /**
     * Executes the lava lamp demo loop.
     *
     * @param oled SSD1331 driver instance.
     */
    public final void demo(final Ssd1331 oled) {
        log.info("Starting Randomized Convection Demo...");
        final var w = oled.getWidth();
        final var h = oled.getHeight();
        final var start = System.currentTimeMillis();
        // Use a buffer for frame timing to keep it smooth
        while (System.currentTimeMillis() - start < 60000) {
            updatePhysics(h);
            render(w, h);
            oled.drawImage(image);
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
            demo(getOled());
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
    public static void main(final String[] args) {
        System.exit(new CommandLine(new LavaLamp()).execute(args));
    }
}
