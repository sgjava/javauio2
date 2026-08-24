/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import java.awt.Color;
import java.awt.Font;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Display Demo using BufferedImage and FFM.
 * <p>
 * Demonstrates basic Java 2D rendering (shapes and text) across different color display hardware. Uses the Foreign Function &
 * Memory API (FFM) via the unified display driver.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "BufImage", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Basic color display demo using BufferedImage and FFM")
public class BufImage extends Base {

    /**
     * Demo execution logic.
     * <p>
     * Initializes hardware via {@code super.call()}, performs standard Java 2D drawing on a cached {@code BufferedImage}, and
     * pushes the result to the display hardware.
     * </p>
     *
     * @return Exit code.
     * @throws Exception Hardware or timing exception.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();

        log.info("Starting Display FFM Demo");

        final var w = getWidth();
        final var h = getHeight();
        final var g = getG2d();

        // Clear background to black
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);

        // Draw a dynamic white border at the edge of the resolution
        g.setColor(Color.WHITE);
        g.drawRect(0, 0, w - 1, h - 1);

        // Calculate dynamic font size based on display height (e.g., ~15% of height, min 10px)
        final var fontSize = Math.max(10, h / 6);
        g.setFont(new Font("Monospaced", Font.BOLD, fontSize));

        // Center text horizontally using FontMetrics
        final var text = "Java 2D";
        final var metrics = g.getFontMetrics();
        final var textWidth = metrics.stringWidth(text);
        final var textX = (w - textWidth) / 2;

        // Position text in the upper half of the display
        final var textY = h / 3;

        g.setColor(Color.YELLOW);
        g.drawString(text, textX, textY);

        // Draw a centered circle in the lower portion of the display
        final var circleDiameter = Math.min(w, h) / 3;
        final var circleX = (w - circleDiameter) / 2;
        final var circleY = (h / 2) + (h / 12);

        g.setColor(Color.CYAN);
        g.drawOval(circleX, circleY, circleDiameter, circleDiameter);

        log.info("Rendering {}x{} frame via FFM memory segment...", w, h);

        // Push the BufferedImage to the hardware via the unified display driver
        getDisplay().drawImage(getImage());

        // Wait based on user-provided sleep option before closing
        TimeUnit.MILLISECONDS.sleep(getSleep());

        // Graceful shutdown of resources
        done();

        return 0;
    }

    /**
     * Main entry point using picocli for command-line argument parsing.
     *
     * @param args Argument list.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new BufImage()).execute(args));
    }
}
