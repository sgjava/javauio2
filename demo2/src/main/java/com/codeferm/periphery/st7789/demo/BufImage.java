/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.st7789.demo;

import java.awt.Color;
import java.awt.Font;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * ST7789 Demo using BufferedImage and FFM.
 * <p>
 * This class demonstrates basic Java 2D rendering (shapes and text) on the ST7789 LCD. It uses the Foreign Function & Memory API
 * (FFM) via the {@code St7789} driver to transfer the image buffer to the Raspberry Pi hardware.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "BufImage", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Basic ST7789 demo using BufferedImage and FFM")
public class BufImage extends Base {

    /**
     * Demo execution logic.
     * <p>
     * Initializes hardware via {@code super.call()}, performs standard Java 2D drawing on a cached {@code BufferedImage}, and
     * pushes the result to the LCD display.
     * </p>
     *
     * @return Exit code.
     * @throws Exception Hardware or timing exception.
     */
    @Override
    public Integer call() throws Exception {
        // super.call() initializes the hardware, caches width/height, and prepares the buffer
        super.call();
        log.info("Starting ST7789 FFM Demo");
        // Accessing cached dimensions and pre-allocated graphics context from Base
        final var w = getWidth();
        final var h = getHeight();
        final var g = getG2d();
        // Clear background to black
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        // Draw a dynamic white border at the edge of the resolution
        g.setColor(Color.WHITE);
        g.drawRect(0, 0, w - 1, h - 1);
        // Render stylized text using Monospaced font
        final var font = new Font("Monospaced", Font.BOLD, 24);
        g.setFont(font);
        // Calculate text bounds to center it horizontally and vertically in the upper section
        final var metrics = g.getFontMetrics(font);
        final var text = "Java 2D ST7789";
        final var textWidth = metrics.stringWidth(text);
        final var textX = (w - textWidth) / 2;
        final var textY = 100; // Adjust vertical baseline as needed
        g.setColor(Color.YELLOW);
        g.drawString(text, textX, textY);
        // Draw a simple graphic to demonstrate color depth centered on screen
        g.setColor(Color.CYAN);
        g.drawOval(w / 2 - 40, h / 2, 80, 80);
        log.info("Rendering {}x{} frame via FFM memory segment...", w, h);
        // Push the BufferedImage to the hardware via FFM driver
        // The driver handles the conversion to the 16-bit (5-6-5) format required by the Pi
        getLcd().drawImage(getImage());
        // Wait based on user-provided sleep option before closing
        TimeUnit.MILLISECONDS.sleep(getSleep());
        // Graceful shutdown of GPIO and SPI resources
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
