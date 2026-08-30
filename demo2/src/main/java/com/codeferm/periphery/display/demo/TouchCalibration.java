/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Interactive 4-point touch screen calibration utility for display and touch controllers. Saves averaged calibration parameters to
 * a touch.properties file to counter drift and skew.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Command(
        name = "TouchCalibration",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Interactive 4-point touch screen calibration utility"
)
@Slf4j
public class TouchCalibration extends Base {

    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Software Framebuffer (RGB565 byte array for display FFM transmission).
     */
    private byte[] frameBuffer;

    /**
     * 4-point calibration states.
     */
    private enum CalibState {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_RIGHT,
        BOTTOM_LEFT
    }

    private CalibState currentState = CalibState.TOP_LEFT;

    private double tlRawX = 0;
    private double tlRawY = 0;
    private double trRawX = 0;
    private double trRawY = 0;
    private double brRawX = 0;
    private double brRawY = 0;
    private double blRawX = 0;
    private double blRawY = 0;

    /**
     * Converts the internal ARGB BufferedImage into an RGB565 byte array buffer.
     *
     * @param bi Source BufferedImage.
     * @param dest Destination byte array buffer.
     * @param width Display width.
     * @param height Display height.
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
     * Draw calibration screen instruction UI and crosshair target.
     *
     * @param display The unified display driver instance.
     * @param message Instruction message.
     * @param targetX Target crosshair X coordinate.
     * @param targetY Target crosshair Y coordinate.
     */
    private void drawCalibrationUI(final AbstractColorDisplay display, final String message, final int targetX,
            final int targetY) {
        final var width = display.getWidth();
        final var height = display.getHeight();

        if (frameBuffer == null) {
            frameBuffer = new byte[width * height * BYTES_PER_PIXEL];
        }

        final var g = getG2d();
        synchronized (this) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);

            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.drawString(message, 15, height / 2 - 20);

            // Draw target crosshair
            g.setColor(Color.RED);
            g.drawLine(targetX - 15, targetY, targetX + 15, targetY);
            g.drawLine(targetX, targetY - 15, targetX, targetY + 15);
            g.drawRect(targetX - 5, targetY - 5, 10, 10);

            convertArgbToRgb565(getImage(), frameBuffer, width, height);
        }

        display.setWindow(0, 0, width, height);
        MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
        display.writeData(display.getImageSegment());
    }

    /**
     * Save averaged calibration properties to disk to eliminate skew and tilt.
     *
     * @throws Exception If writing file fails.
     */
    private void saveProperties() throws Exception {
        final var calculatedMinX = (tlRawX + blRawX) / 2.0;
        final var calculatedMaxX = (trRawX + brRawX) / 2.0;
        final var calculatedMinY = (tlRawY + trRawY) / 2.0;
        final var calculatedMaxY = (blRawY + brRawY) / 2.0;

        final var props = new Properties();
        props.setProperty("min.raw.x", String.valueOf(calculatedMinX));
        props.setProperty("max.raw.x", String.valueOf(calculatedMaxX));
        props.setProperty("min.raw.y", String.valueOf(calculatedMinY));
        props.setProperty("max.raw.y", String.valueOf(calculatedMaxY));

        final var fileName = "touch.properties";
        try (final var out = new FileOutputStream(fileName)) {
            props.store(out, "4-Point Touch Calibration Bounds");
            log.info("4-point calibration parameters saved successfully to {} (minX={}, maxX={}, minY={}, maxY={})",
                    fileName, calculatedMinX, calculatedMaxX, calculatedMinY, calculatedMaxY);
        }
    }

    /**
     * Main execution loop for 4-point calibration demo.
     *
     * @param display The unified display driver instance.
     * @param touch The unified touch driver instance.
     * @throws Exception If hardware communication fails.
     */
    private void runDemo(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        log.info("4-point touch calibration utility started...");
        final var width = display.getWidth();
        final var height = display.getHeight();

        // Step 1: Top-Left Calibration
        drawCalibrationUI(display, "Tap Top-Left Crosshair (1/4)", 30, 30);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.TOP_LEFT) {
            if (touch != null && touch.isPressed()) {
                final var rawPoint = touch.readCoordinates();
                tlRawX = rawPoint.x();
                tlRawY = rawPoint.y();
                log.info("Top-Left Recorded -> Raw X: {}, Raw Y: {}", tlRawX, tlRawY);
                currentState = CalibState.TOP_RIGHT;
                TimeUnit.MILLISECONDS.sleep(500); // Debounce delay
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Step 2: Top-Right Calibration
        drawCalibrationUI(display, "Tap Top-Right Crosshair (2/4)", width - 30, 30);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.TOP_RIGHT) {
            if (touch != null && touch.isPressed()) {
                final var rawPoint = touch.readCoordinates();
                trRawX = rawPoint.x();
                trRawY = rawPoint.y();
                log.info("Top-Right Recorded -> Raw X: {}, Raw Y: {}", trRawX, trRawY);
                currentState = CalibState.BOTTOM_RIGHT;
                TimeUnit.MILLISECONDS.sleep(500); // Debounce delay
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Step 3: Bottom-Right Calibration
        drawCalibrationUI(display, "Tap Bottom-Right Crosshair (3/4)", width - 30, height - 30);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.BOTTOM_RIGHT) {
            if (touch != null && touch.isPressed()) {
                final var rawPoint = touch.readCoordinates();
                brRawX = rawPoint.x();
                brRawY = rawPoint.y();
                log.info("Bottom-Right Recorded -> Raw X: {}, Raw Y: {}", brRawX, brRawY);
                currentState = CalibState.BOTTOM_LEFT;
                TimeUnit.MILLISECONDS.sleep(500); // Debounce delay
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Step 4: Bottom-Left Calibration
        drawCalibrationUI(display, "Tap Bottom-Left Crosshair (4/4)", 30, height - 30);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.BOTTOM_LEFT) {
            if (touch != null && touch.isPressed()) {
                final var rawPoint = touch.readCoordinates();
                blRawX = rawPoint.x();
                blRawY = rawPoint.y();
                log.info("Bottom-Left Recorded -> Raw X: {}, Raw Y: {}", blRawX, blRawY);
                saveProperties();
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        log.info("4-point calibration complete. Exiting...");
    }

    /**
     * Entry point for the Command Line Interface.
     *
     * @return Exit code.
     * @throws Exception on execution failure.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo(getDisplay(), getTouch());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Application entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new TouchCalibration()).execute(args));
    }
}
