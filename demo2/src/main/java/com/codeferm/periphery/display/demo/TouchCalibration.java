/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import java.awt.Color;
import java.awt.Font;
import java.io.FileOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Interactive 4-point touch screen calibration utility using multi-sample burst averaging and absolute edge extrapolation to
 * eliminate bezel interference and rotation skew.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Command(
        name = "TouchCalibration",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Interactive 4-point touch screen calibration utility with absolute edge extrapolation"
)
@Slf4j
public class TouchCalibration extends Base {

    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Number of raw samples to capture and average per calibration point.
     */
    private static final int SAMPLES_COUNT = 20;

    /**
     * Inset distance from physical edges for crosshair targets to avoid bezels.
     */
    private static final int INSET = 15;

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
    private void convertArgbToRgb565(final java.awt.image.BufferedImage bi, final byte[] dest, final int width, final int height) {
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
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString(message, 15, height / 2 - 20);

            // Draw target crosshair
            g.setColor(Color.RED);
            g.drawLine(targetX - 10, targetY, targetX + 10, targetY);
            g.drawLine(targetX, targetY - 10, targetX, targetY + 10);
            g.drawRect(targetX - 4, targetY - 4, 8, 8);

            convertArgbToRgb565(getImage(), frameBuffer, width, height);
        }

        display.setWindow(0, 0, width, height);
        MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
        display.writeData(display.getImageSegment());
    }

    /**
     * Captures a burst of raw touch points while the user holds the crosshair and computes the average.
     *
     * @param touch The unified touch driver instance.
     * @return Point2D.Double containing the averaged raw X and Y coordinates.
     * @throws Exception If thread interruption occurs.
     */
    private java.awt.geom.Point2D.Double captureAveragedTouch(final AbstractTouch touch) throws Exception {
        var sumX = 0.0;
        var sumY = 0.0;
        var validSamples = 0;

        log.info("Hold crosshair... capturing {} samples for averaging.", SAMPLES_COUNT);

        while (validSamples < SAMPLES_COUNT && !Thread.currentThread().isInterrupted() && isRunning()) {
            if (touch != null && touch.isPressed()) {
                final var rawPoint = touch.readCoordinates();
                sumX += rawPoint.x();
                sumY += rawPoint.y();
                validSamples++;
                TimeUnit.MILLISECONDS.sleep(15);
            } else {
                if (validSamples > 0 && validSamples < SAMPLES_COUNT) {
                    log.warn("Touch released prematurely. Please hold finger on crosshair until complete.");
                    sumX = 0.0;
                    sumY = 0.0;
                    validSamples = 0;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }

        while (touch != null && touch.isPressed() && !Thread.currentThread().isInterrupted()) {
            TimeUnit.MILLISECONDS.sleep(50);
        }

        final var avgX = sumX / SAMPLES_COUNT;
        final var avgY = sumY / SAMPLES_COUNT;
        return new java.awt.geom.Point2D.Double(avgX, avgY);
    }

    /**
     * Mathematically extrapolates measured inset calibration points to the absolute screen boundaries (0 and width/height) and
     * saves to disk.
     *
     * @param width Display width.
     * @param height Display height.
     * @throws Exception If writing file fails.
     */
    private void saveProperties(final int width, final int height) throws Exception {
        final var drawableWidth = width - (2.0 * INSET);
        final var drawableHeight = height - (2.0 * INSET);

        final var measuredMinX = (tlRawX + blRawX) / 2.0;
        final var measuredMaxX = (trRawX + brRawX) / 2.0;
        final var measuredTopY = (tlRawY + trRawY) / 2.0;
        final var measuredBottomY = (blRawY + brRawY) / 2.0;

        // Extrapolate X bounds to absolute screen edges (0 and width)
        final var rawPerPixelX = (measuredMaxX - measuredMinX) / drawableWidth;
        final var trueMinX = measuredMinX - (INSET * rawPerPixelX);
        final var trueMaxX = measuredMaxX + (INSET * rawPerPixelX);

        // Extrapolate Y bounds to absolute screen edges (0 and height) - accounting for Y inversion
        final var rawPerPixelY = (measuredTopY - measuredBottomY) / drawableHeight;
        final var trueMinY = measuredTopY + (INSET * rawPerPixelY);
        final var trueMaxY = measuredBottomY - (INSET * rawPerPixelY);

        final var props = new Properties();
        props.setProperty("min.raw.x", String.valueOf(trueMinX));
        props.setProperty("max.raw.x", String.valueOf(trueMaxX));
        props.setProperty("min.raw.y", String.valueOf(trueMinY));
        props.setProperty("max.raw.y", String.valueOf(trueMaxY));

        final var fileName = getTouchPropertiesFile();
        try (final var out = new FileOutputStream(fileName)) {
            props.store(out, "4-Point Multi-Sample Absolute Extrapolated Calibration Bounds");
            log.info("Absolute calibration saved successfully to {} (minX={}, maxX={}, minY={}, maxY={})",
                    fileName, trueMinX, trueMaxX, trueMinY, trueMaxY);
        }
    }

    /**
     * Main execution loop for 4-point averaged calibration demo.
     *
     * @param display The unified display driver instance.
     * @param touch The unified touch driver instance.
     * @throws Exception If hardware communication fails.
     */
    private void runDemo(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        log.info("Absolute extrapolated touch calibration utility started using properties file: {}...", getTouchPropertiesFile());
        final var width = display.getWidth();
        final var height = display.getHeight();

        // Step 1: Top-Left Calibration
        drawCalibrationUI(display, "Hold Top-Left Crosshair (1/4)", INSET, INSET);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.TOP_LEFT) {
            if (touch != null && touch.isPressed()) {
                final var avgPoint = captureAveragedTouch(touch);
                tlRawX = avgPoint.x;
                tlRawY = avgPoint.y;
                log.info("Top-Left Averaged -> Raw X: {}, Raw Y: {}", tlRawX, tlRawY);
                currentState = CalibState.TOP_RIGHT;
                TimeUnit.MILLISECONDS.sleep(300);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Step 2: Top-Right Calibration
        drawCalibrationUI(display, "Hold Top-Right Crosshair (2/4)", width - INSET, INSET);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.TOP_RIGHT) {
            if (touch != null && touch.isPressed()) {
                final var avgPoint = captureAveragedTouch(touch);
                trRawX = avgPoint.x;
                trRawY = avgPoint.y;
                log.info("Top-Right Averaged -> Raw X: {}, Raw Y: {}", trRawX, trRawY);
                currentState = CalibState.BOTTOM_RIGHT;
                TimeUnit.MILLISECONDS.sleep(300);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Step 3: Bottom-Right Calibration
        drawCalibrationUI(display, "Hold Bottom-Right Crosshair (3/4)", width - INSET, height - INSET);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.BOTTOM_RIGHT) {
            if (touch != null && touch.isPressed()) {
                final var avgPoint = captureAveragedTouch(touch);
                brRawX = avgPoint.x;
                brRawY = avgPoint.y;
                log.info("Bottom-Right Averaged -> Raw X: {}, Raw Y: {}", brRawX, brRawY);
                currentState = CalibState.BOTTOM_LEFT;
                TimeUnit.MILLISECONDS.sleep(300);
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        // Step 4: Bottom-Left Calibration
        drawCalibrationUI(display, "Hold Bottom-Left Crosshair (4/4)", INSET, height - INSET);
        while (!Thread.currentThread().isInterrupted() && isRunning() && currentState == CalibState.BOTTOM_LEFT) {
            if (touch != null && touch.isPressed()) {
                final var avgPoint = captureAveragedTouch(touch);
                blRawX = avgPoint.x;
                blRawY = avgPoint.y;
                log.info("Bottom-Left Averaged -> Raw X: {}, Raw Y: {}", blRawX, blRawY);
                saveProperties(width, height);
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        log.info("Absolute calibration complete. Exiting...");
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
