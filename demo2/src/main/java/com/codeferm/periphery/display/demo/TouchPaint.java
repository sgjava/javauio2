/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import com.codeferm.periphery.device.Xpt2046;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Interactive touch paint demo using hardware IRQ edge detection via encapsulated Xpt2046 polling.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Command(
        name = "TouchPaint",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Interactive touch paint demo with direct GPIO IRQ handling"
)
@Slf4j
public class TouchPaint extends Base {

    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Software Framebuffer (RGB565 byte array for display FFM transmission).
     */
    private byte[] frameBuffer;

    /**
     * Persistent drawing canvas image.
     */
    private BufferedImage canvasImage;
    private Graphics2D canvasG2d;

    /**
     * Current drawing color.
     */
    private Color currentColor = Color.RED;

    /**
     * Current brush size.
     */
    private int brushSize = 4;

    /**
     * Last X coordinate for line interpolation.
     */
    private int lastX = -1;

    /**
     * Last Y coordinate for line interpolation.
     */
    private int lastY = -1;

    /**
     * Calibration raw bounds loaded from properties.
     */
    private double minRawX = 0;
    private double maxRawX = 0;
    private double minRawY = 0;
    private double maxRawY = 0;

    /**
     * Define toolbar button representation.
     */
    private record ToolButton(String label, Rectangle bounds, Color color, String actionType) {

    }

    /**
     * Array of toolbar action buttons.
     */
    private ToolButton[] buttons;

    /**
     * Load calibration properties from disk.
     *
     * @throws Exception If file reading fails or properties are missing.
     */
    private void loadProperties() throws Exception {
        final var props = new Properties();
        final var fileName = "touch.properties";
        try (final var in = new FileInputStream(fileName)) {
            props.load(in);
            minRawX = Double.parseDouble(props.getProperty("min.raw.x", "0"));
            maxRawX = Double.parseDouble(props.getProperty("max.raw.x", "1000"));
            minRawY = Double.parseDouble(props.getProperty("min.raw.y", "0"));
            maxRawY = Double.parseDouble(props.getProperty("max.raw.y", "1000"));
            log.info("Loaded touch calibration from {}: minX={}, maxX={}, minY={}, maxY={}",
                    fileName, minRawX, maxRawX, minRawY, maxRawY);
        } catch (final Exception e) {
            log.error("Failed to load {}. Please run touch-calibrate first.", fileName);
            throw e;
        }
    }

    /**
     * Initialize toolbar buttons (colors, sizes, clear).
     */
    private void initToolbar() {
        final var width = getWidth();
        final var toolbarHeight = 50;
        final var btnWidth = width / 9;

        final var labels = new String[]{"R", "G", "B", "W", "S1", "S2", "S3", "ER", "CLR"};
        final var colors = new Color[]{
            Color.RED, Color.GREEN, Color.BLUE, Color.WHITE,
            Color.DARK_GRAY, Color.DARK_GRAY, Color.DARK_GRAY,
            Color.BLACK, Color.GRAY
        };
        final var types = new String[]{"COLOR", "COLOR", "COLOR", "COLOR", "SIZE", "SIZE", "SIZE", "ERASE", "CLEAR"};

        buttons = new ToolButton[labels.length];
        for (var i = 0; i < labels.length; i++) {
            final var x = i * btnWidth;
            final var bounds = new Rectangle(x, 0, btnWidth, toolbarHeight);
            buttons[i] = new ToolButton(labels[i], bounds, colors[i], types[i]);
        }
    }

    /**
     * Initialize persistent drawing canvas.
     *
     * @param width Display width.
     * @param height Display height.
     */
    private void initCanvas(final int width, final int height) {
        canvasImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        canvasG2d = canvasImage.createGraphics();
        canvasG2d.setColor(Color.BLACK);
        canvasG2d.fillRect(0, 0, width, height);
    }

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
     * Draw toolbar UI and composite with the canvas, then flush to display.
     *
     * @param display The unified display driver instance.
     */
    private void drawUI(final AbstractColorDisplay display) {
        final var width = display.getWidth();
        final var height = display.getHeight();

        if (frameBuffer == null) {
            frameBuffer = new byte[width * height * BYTES_PER_PIXEL];
        }

        final var g = getG2d();
        synchronized (this) {
            g.drawImage(canvasImage, 0, 0, null);

            g.setColor(new Color(40, 40, 40));
            g.fillRect(0, 0, width, 50);
            g.setColor(Color.DARK_GRAY);
            g.drawLine(0, 50, width, 50);

            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            for (final var btn : buttons) {
                g.setColor(btn.color());
                g.fillRect(btn.bounds().x + 2, btn.bounds().y + 2, btn.bounds().width - 4, btn.bounds().height - 4);
                g.setColor(Color.WHITE);

                final var bm = g.getFontMetrics();
                final var bx = btn.bounds().x + (btn.bounds().width - bm.stringWidth(btn.label())) / 2;
                final var by = btn.bounds().y + (btn.bounds().height + bm.getAscent()) / 2 - 4;
                g.drawString(btn.label(), bx, by);
            }

            convertArgbToRgb565(getImage(), frameBuffer, width, height);
        }

        display.setWindow(0, 0, width, height);
        MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
        display.writeData(display.getImageSegment());
    }

    /**
     * Dynamically map raw touch coordinates to screen pixel coordinates using calibration values.
     *
     * @param rawX Raw X coordinate.
     * @param rawY Raw Y coordinate.
     * @return Screen point.
     */
    private java.awt.Point mapTouchToScreen(final int rawX, final int rawY) {
        final var screenX = (int) ((rawX - minRawX) / (maxRawX - minRawX) * getWidth());
        final var screenY = (int) ((rawY - minRawY) / (maxRawY - minRawY) * getHeight());
        return new java.awt.Point(Math.clamp(screenX, 0, getWidth() - 1), Math.clamp(screenY, 0, getHeight() - 1));
    }

    /**
     * Handle toolbar button actions.
     *
     * @param btn ToolButton pressed.
     */
    private void handleToolbarAction(final ToolButton btn) {
        switch (btn.label()) {
            case "R" ->
                currentColor = Color.RED;
            case "G" ->
                currentColor = Color.GREEN;
            case "B" ->
                currentColor = Color.BLUE;
            case "W" ->
                currentColor = Color.WHITE;
            case "S1" ->
                brushSize = 2;
            case "S2" ->
                brushSize = 6;
            case "S3" ->
                brushSize = 12;
            case "ER" ->
                currentColor = Color.BLACK;
            case "CLR" -> {
                canvasG2d.setColor(Color.BLACK);
                canvasG2d.fillRect(0, 0, getWidth(), getHeight());
            }
            default -> {
            }
        }
        log.info("Toolbar action: label={}, color={}, brushSize={}", btn.label(), currentColor, brushSize);
    }

    /**
     * Main execution loop for the paint demo using encapsulated touch IRQ polling.
     *
     * @param display The unified display driver instance.
     * @param touch The unified touch driver instance.
     * @throws Exception If hardware communication fails.
     */
    private void runDemo(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        loadProperties();
        log.info("Paint demo with direct IRQ started on device {} line {}...", getGpioDevice(), getTouchIrqLine());

        final var width = display.getWidth();
        final var height = display.getHeight();

        initCanvas(width, height);
        initToolbar();
        drawUI(display);

        final var xpt = (Xpt2046) touch;

        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            if (xpt != null && xpt.pollEvent(1000)) {
                final var edge = xpt.getEdge();

                // Falling edge indicates touch down event
                if (edge == Periphery.GPIO_EDGE_FALLING()) {
                    while (xpt.isPressed()) {
                        final var rawPoint = xpt.readCoordinates();
                        final var screenPoint = mapTouchToScreen(rawPoint.x(), rawPoint.y());

                        if (screenPoint.y < 50) {
                            lastX = -1;
                            lastY = -1;
                            for (final var btn : buttons) {
                                if (btn.bounds().contains(screenPoint)) {
                                    handleToolbarAction(btn);
                                    drawUI(display);
                                    TimeUnit.MILLISECONDS.sleep(200);
                                    break;
                                }
                            }
                        } else {
                            canvasG2d.setColor(currentColor);
                            canvasG2d.setStroke(new BasicStroke(brushSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                            if (lastX == -1 || lastY == -1) {
                                canvasG2d.drawLine(screenPoint.x, screenPoint.y, screenPoint.x, screenPoint.y);
                            } else {
                                canvasG2d.drawLine(lastX, lastY, screenPoint.x, screenPoint.y);
                            }
                            lastX = screenPoint.x;
                            lastY = screenPoint.y;

                            drawUI(display);
                        }
                        TimeUnit.MILLISECONDS.sleep(5);
                    }
                }
            }
            lastX = -1;
            lastY = -1;
        }
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
        System.exit(new CommandLine(new TouchPaint()).execute(args));
    }
}
