/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.Ssd1331;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * High-performance, zero-allocation Defender-style scroller for the SSD1331 OLED.
 * <p>
 * This implementation leverages the hardware Graphic Acceleration Command (GAC) engine to perform BitBLT scrolls and line-based
 * rendering. It features a terrain-following physics model using linear interpolation (Lerp) for smooth vertical movement.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "DefenderScroller",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "GAC-based bi-directional terrain scroller with ship."
)
public class DefenderScroller extends Base {

    /**
     * Random number generator for terrain generation and direction logic.
     */
    private final Random random = new Random();
    /**
     * Height map of the terrain (96 columns).
     */
    private final int[] terrain = new int[96];
    /**
     * Scroll direction: -1 for Left (Ship faces Right), 1 for Right (Ship faces Left).
     */
    private int velocity = -1;

    /**
     * Fixed horizontal anchor point for the ship.
     */
    private final int shipX = 48;
    /**
     * Buffer width for erasing the ship's trail (includes ship and flame).
     */
    private final int clearWidth = 16;
    /**
     * Vertical position of the ship in sub-pixel coordinates.
     */
    private double shipY = 25.0;
    /**
     * Desired distance between the ship and the peak of the hills.
     */
    private final int hoverHeight = 15;
    /**
     * Smoothing factor for vertical tracking (0.15 = 15% move toward target per frame).
     */
    private final double lerpFactor = 0.15;

    /**
     * Initializes the terrain array using a random walk algorithm. Constraints ensure the terrain stays within visible bounds (5 to
     * 40 pixels).
     */
    private void initTerrain() {
        var h = 20;
        for (var i = 0; i < 96; i++) {
            h += (random.nextInt(3) - 1);
            terrain[i] = Math.clamp(h, 5, 40);
        }
    }

    /**
     * Renders a single vertical slice of the world (Sky, Hill, and Peak).
     *
     * @param oled The SSD1331 hardware device.
     * @param x The horizontal column index to draw.
     */
    private void drawColumn(final Ssd1331 oled, final int x) {
        if (x < 0 || x >= 96) {
            return;
        }
        final var screenH = oled.getHeight();
        final var hillHeight = terrain[x];
        final var hillTopY = (screenH - 1) - hillHeight;
        // 1. Black Sky
        if (hillTopY > 0) {
            oled.drawLine(x, 0, x, hillTopY - 1, 0, 0, 0);
        }
        // 2. Green Hill
        oled.drawLine(x, screenH - hillHeight, x, screenH - 1, 0, 45, 0);
        // 3. Peak (White/Yellow point)
        oled.drawLine(x, screenH - hillHeight, x, screenH - hillHeight, 31, 63, 31);
    }

    /**
     * Handles hardware BitBLT scrolling and new column injection.
     *
     * @param oled The SSD1331 hardware device.
     * @param dir The current scroll direction.
     */
    private void scroll(final Ssd1331 oled, final int dir) {
        final var screenH = oled.getHeight();
        final var screenW = oled.getWidth();

        if (dir < 0) {
            // SCROLL LEFT: Move block [1 to 95] into [0 to 94]
            for (var x = 0; x < screenW - 1; x++) {
                oled.copy(x + 1, 0, x + 1, screenH - 1, x, 0);
                terrain[x] = terrain[x + 1];
            }
            // Inject new terrain at the right edge
            terrain[screenW - 1] = Math.clamp(terrain[screenW - 2] + (random.nextInt(5) - 2), 5, 40);
            drawColumn(oled, screenW - 1);
        } else {
            // SCROLL RIGHT: Move block [0 to 94] into [1 to 95]
            for (var x = screenW - 1; x > 0; x--) {
                oled.copy(x - 1, 0, x - 1, screenH - 1, x, 0);
                terrain[x] = terrain[x - 1];
            }
            // Inject new terrain at the left edge
            terrain[0] = Math.clamp(terrain[1] + (random.nextInt(5) - 2), 5, 40);
            drawColumn(oled, 0);
        }
    }

    /**
     * Draws the ship sprite and its flickering engine flame.
     *
     * @param oled The SSD1331 hardware device.
     * @param x The horizontal center of the ship.
     * @param y The vertical center of the ship.
     * @param dir Direction to determine which way the ship faces.
     */
    private void drawShip(final Ssd1331 oled, final int x, final int y, final int dir) {
        final var r = 0;
        final var g = 63;
        final var b = 63; // Cyan ship
        if (dir < 0) {
            // Face Right
            oled.drawLine(x - 6, y - 3, x + 6, y, r, g, b);
            oled.drawLine(x + 6, y, x - 6, y + 3, r, g, b);
            oled.drawLine(x - 6, y + 3, x - 6, y - 3, r, g, b);
            if (random.nextBoolean()) {
                oled.drawLine(x - 7, y, x - 12, y, 63, 15, 0); // Orange flame
            }
        } else {
            // Face Left
            oled.drawLine(x + 6, y - 3, x - 6, y, r, g, b);
            oled.drawLine(x - 6, y, x + 6, y + 3, r, g, b);
            oled.drawLine(x + 6, y + 3, x + 6, y - 3, r, g, b);
            if (random.nextBoolean()) {
                oled.drawLine(x + 7, y, x + 12, y, 63, 15, 0); // Orange flame
            }
        }
    }

    /**
     * Primary loop for the demonstration.
     *
     * @param oled The SSD1331 hardware device.
     * @throws Exception If SPI communication fails.
     */
    public final void runDemo(final Ssd1331 oled) throws Exception {
        log.info("Starting Defender Scroller...");
        oled.setup();
        // Hardware remap for RGB mode and normal display
        oled.writeCommand(new byte[]{(byte) 0xA4, Ssd1331.SET_REMAP, (byte) 0x72});
        oled.clear();
        initTerrain();

        // Perform initial draw of all columns
        for (var x = 0; x < 96; x++) {
            drawColumn(oled, x);
        }

        // Use isRunning() from Base and check thread interrupt status
        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            // 1. Hardware world scroll
            scroll(oled, velocity);

            // 2. Erase ship area using background data (prevents smearing)
            for (var x = shipX - clearWidth; x <= shipX + clearWidth; x++) {
                drawColumn(oled, x);
            }

            // 3. Terrain following physics (Lerp)
            final var targetAltitudeY = 63 - terrain[shipX] - hoverHeight;
            shipY += (targetAltitudeY - shipY) * lerpFactor;

            // 4. Render ship at calculated position
            drawShip(oled, shipX, (int) shipY, velocity);

            // 5. Randomized direction change
            if (random.nextInt(500) == 0) {
                velocity *= -1;
            }

            // 6. Throttle loop to maintain ~50 FPS
            final var elapsedTime = System.currentTimeMillis() - startTime;
            final var targetDelay = 1000 / getFps(); // Use Base FPS

            if (elapsedTime < targetDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(targetDelay - elapsedTime);
                } catch (final InterruptedException e) {
                    // Restore interrupt status so the loop condition fails
                    Thread.currentThread().interrupt();
                    log.debug("Interrupted during sleep, exiting loop.");
                    break;
                }
            }
        }
    }

    /**
     * Command entry point.
     *
     * @return Process exit code.
     * @throws Exception If an error occurs during runtime.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo(getOled());
        } finally {
            done(); // Cleanup resources without allocation
        }
        return 0;
    }

    /**
     * Main method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new DefenderScroller()).execute(args));
    }
}
