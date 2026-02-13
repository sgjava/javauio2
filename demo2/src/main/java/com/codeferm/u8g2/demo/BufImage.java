/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;

/**
 * This version uses pre-allocated buffers and direct raster bit-scraping.
 * <p>
 * Optimized for FFM: Uses a persistent Java byte array for bit-translation and MemorySegment.copy() to move data to the native
 * buffer.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@CommandLine.Command(name = "BufImage", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Optimized BufferedImage demo")
public class BufImage extends Base {

    /**
     * Java-side canvas for 2D drawing.
     */
    private BufferedImage canvas;

    /**
     * Graphics context for the canvas.
     */
    private Graphics2D g2d;

    /**
     * Intermediate buffer to hold translated bits before native copy.
     */
    private byte[] localBuffer;

    /**
     * Direct access to the BufferedImage's internal byte array.
     */
    private byte[] canvasPixels;

    /**
     * Size of the display buffer in bytes.
     */
    private int bufferSize;

    /**
     * Initialize the buffers once. This is key for performance.
     * <p>
     * Reinterprets the raw native pointer to a bounded MemorySegment to prevent NegativeArraySizeException.
     * </p>
     *
     * @param u8g2 Native MemorySegment of the u8g2_t structure.
     */
    private void initBuffers(final MemorySegment u8g2) {
        final var w = getWidth();
        final var h = getHeight();

        // Setup the Java Canvas (1-bit monochrome)
        this.canvas = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        this.g2d = canvas.createGraphics();

        // Extract the underlying byte array from the BufferedImage raster
        final var raster = canvas.getRaster();
        this.canvasPixels = ((DataBufferByte) raster.getDataBuffer()).getData();

        // Calculate expected buffer size: (Width * Height) / 8
        final var calculatedSize = (long) (w * h) / 8;

        // FFM Fix: Raw pointers have no size (-1). We must reinterpret to the known buffer size.
        final var rawBuffer = U8g2.u8g2_GetBufferPtr_Java(u8g2);
        final var nativeBuffer = rawBuffer.reinterpret(calculatedSize);

        this.bufferSize = (int) nativeBuffer.byteSize();
        this.localBuffer = new byte[bufferSize];

        // Default Graphics settings
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2d.setFont(new Font("Arial", Font.BOLD, 14));
    }

    /**
     * High-speed translation of BufferedImage bits to U8g2 memory layout.
     *
     * @param u8g2 Native MemorySegment of the u8g2_t structure.
     */
    public void sendBufferedImage(final MemorySegment u8g2) {
        final var h = getHeight();
        final var w = getWidth();

        // Clear the local transfer buffer (Zero allocation)
        Arrays.fill(localBuffer, (byte) 0);

        // Java Raster: Horizontal rows, 8 pixels per byte, MSB first.
        // U8g2 Raster: Vertical pages (8px high), LSB first.                 
        for (var y = 0; y < h; y++) {
            final var rowOffset = y * ((w + 7) / 8);
            final var pageOffset = (y / 8) * w;
            final var u8g2Bit = (1 << (y % 8));

            for (var x = 0; x < w; x++) {
                final var javaByte = canvasPixels[rowOffset + (x / 8)] & 0xFF;
                if ((javaByte & (1 << (7 - (x % 8)))) != 0) {
                    localBuffer[pageOffset + x] |= u8g2Bit;
                }
            }
        }

        // FFM Standard: Copy the translated Java byte array to the native buffer.
        // We reinterpret here as well to ensure the destination segment is bounded for the copy.
        final var nativeBuffer = U8g2.u8g2_GetBufferPtr_Java(u8g2).reinterpret(bufferSize);
        MemorySegment.copy(localBuffer, 0, nativeBuffer, ValueLayout.JAVA_BYTE, 0, bufferSize);

        // Send to hardware
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Renders a frame using standard Java2D calls.
     *
     * @param u8g2 Native MemorySegment.
     * @param message String to display.
     */
    public void renderFrame(final MemorySegment u8g2, final String message) {
        // Clear Java canvas (Black)
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, getWidth(), getHeight());

        // Draw Content (White)
        g2d.setColor(Color.WHITE);
        final var fm = g2d.getFontMetrics();
        final var x = (getWidth() - fm.stringWidth(message)) / 2;
        final var y = ((getHeight() - fm.getHeight()) / 2) + fm.getAscent();

        g2d.drawString(message, x, y);
        g2d.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

        // Translate and push to hardware
        sendBufferedImage(u8g2);
    }

    /**
     * Execution logic for the demo.
     *
     * @param u8g2 MemorySegment handle to the u8g2 structure.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        initBuffers(u8g2);
        renderFrame(u8g2, "Java 2D FFM");
        try {
            Thread.sleep(getSleep());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Main parsing, error handling and handling user requests for usage help or version help are done with one line of code.
     *
     * @param args Argument list.
     */
    public static void main(String... args) {
        System.exit(new CommandLine(new BufImage()).execute(args));
    }
}
