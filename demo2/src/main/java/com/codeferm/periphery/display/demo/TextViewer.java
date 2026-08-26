/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance text viewer demo with dynamic font scaling, word wrapping, and smooth vertical scrolling.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "TextViewer",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Dynamic text viewer with auto-scaling font, word wrapping, and scrolling."
)
public class TextViewer extends Base {

    /**
     * Text content to display.
     */
    @Option(
            names = {"--text"},
            description = "Text content to display.",
            defaultValue = "Java UIO 2 brings the Foreign Function & Memory (FFM) API to Linux embedded systems. "
            + "Engineered for JDK 25, it provides unmatched speed and hardware accuracy. "
            + "This text viewer automatically scales fonts, wraps words, and scrolls smoothly "
            + "across any abstract color display format!"
    )
    private String textContent;

    /**
     * Optional manual font size override (0 to auto-scale based on width).
     */
    @Option(
            names = {"--font-size"},
            description = "Font size override (0 for auto-scaling).",
            defaultValue = "0"
    )
    private int fontSizeOverride;

    /**
     * Primary loop for the demonstration.
     *
     * @param display The hardware color display device instance.
     * @throws Exception If communication fails.
     */
    public final void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Starting Text Viewer Demo...");

        final var width = display.getWidth();
        final var height = display.getHeight();

        // 1. Create a system RAM buffer for Java2D text rendering
        final var bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final var g2d = bi.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 2. Dynamically scale font size (larger scale factor for small screens) or use override
        final var fontSize = (fontSizeOverride > 0) ? fontSizeOverride : Math.max(14, width / 8);
        final var font = new Font("SansSerif", Font.BOLD, fontSize);
        g2d.setFont(font);

        final var fontMetrics = g2d.getFontMetrics();
        final var lineHeight = fontMetrics.getHeight();

        // 3. Perform dynamic word wrapping
        final var lines = wrapText(textContent, fontMetrics, width - 10);

        var scrollY = (float) height; // Start just below the screen

        final var bufferSize = width * height * 2; // RGB565
        final var rgb565Buffer = new byte[bufferSize];
        final var frameSeg = MemorySegment.ofArray(rgb565Buffer);

        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var startTime = System.currentTimeMillis();

            // Clear background (Black)
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, width, height);

            // Draw text lines at current scroll offset
            g2d.setColor(Color.GREEN);
            var currentY = scrollY;

            for (final var line : lines) {
                if (currentY > -lineHeight && currentY < height + lineHeight) {
                    g2d.drawString(line, 5, (int) currentY);
                }
                currentY += lineHeight;
            }

            // Reset scroll if fully scrolled past top
            if (currentY < 0) {
                scrollY = height;
            } else {
                scrollY -= 1.0f; // Scroll speed
            }

            // 4. Convert BufferedImage (INT_ARGB) to RGB565 byte array efficiently
            convertArgbToRgb565(bi, rgb565Buffer, width, height);

            // 5. Blast frame buffer to hardware display via FFM
            display.setWindow(0, 0, width, height);
            MemorySegment.copy(frameSeg, ValueLayout.JAVA_BYTE, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, bufferSize);
            display.writeData(display.getImageSegment());

            // 6. Frame rate throttling
            final var elapsedTime = System.currentTimeMillis() - startTime;
            final var targetDelay = Math.max(1, 1000 / getFps());

            if (elapsedTime < targetDelay) {
                try {
                    TimeUnit.MILLISECONDS.sleep(targetDelay - elapsedTime);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("Interrupted during sleep, exiting loop.");
                    break;
                }
            }
        }
        g2d.dispose();
    }

    /**
     * Wraps raw text string into a list of lines fitting within the max pixel width.
     *
     * @param text Raw input string.
     * @param metrics Font metrics for character measurement.
     * @param maxWidth Max pixel width allowed per line.
     * @return List of wrapped text lines.
     */
    private List<String> wrapText(final String text, final java.awt.FontMetrics metrics, final int maxWidth) {
        final var wrappedLines = new ArrayList<String>();
        final var paragraphs = text.split("\n");

        for (final var paragraph : paragraphs) {
            final var words = paragraph.split(" ");
            var currentLine = new StringBuilder();

            for (final var word : words) {
                final var testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
                if (metrics.stringWidth(testLine) > maxWidth) {
                    if (currentLine.length() > 0) {
                        wrappedLines.add(currentLine.toString());
                        currentLine = new StringBuilder(word);
                    } else {
                        // Word itself is longer than line width, add raw word
                        wrappedLines.add(word);
                    }
                } else {
                    currentLine = new StringBuilder(testLine);
                }
            }
            if (currentLine.length() > 0) {
                wrappedLines.add(currentLine.toString());
            }
        }
        return wrappedLines;
    }

    /**
     * Converts an ARGB BufferedImage into an RGB565 byte array.
     *
     * @param bi Source image.
     * @param dest Destination byte array.
     * @param width Image width.
     * @param height Image height.
     */
    private void convertArgbToRgb565(final BufferedImage bi, final byte[] dest, final int width, final int height) {
        var index = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                final var rgb = bi.getRGB(x, y);
                final var r = (rgb >> 16) & 0xFF;
                final var g = (rgb >> 8) & 0xFF;
                final var b = rgb & 0xFF;

                final var rgb565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);

                dest[index++] = (byte) (rgb565 >> 8);
                dest[index++] = (byte) (rgb565 & 0xFF);
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
            runDemo((AbstractColorDisplay) getDisplay());
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
        System.exit(new CommandLine(new TextViewer()).execute(args));
    }
}
