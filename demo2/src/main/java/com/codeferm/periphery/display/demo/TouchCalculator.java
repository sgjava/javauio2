/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Interactive touch calculator demo for display and touch controllers using properties calibration, leveraging base class touch and
 * rotation abstractions.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Command(
        name = "TouchCalculator",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Interactive touch calculator demo"
)
@Slf4j
public class TouchCalculator extends Base {

    /**
     * Bytes per pixel (RGB565).
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Software Framebuffer (RGB565 byte array for display FFM transmission).
     */
    private byte[] frameBuffer;

    private String displayExpression = "0";
    private boolean evaluateNext = false;

    /**
     * Define button layout representation implementing Base.TouchableElement.
     */
    private record CalcButton(String label, Rectangle bounds, Color color) implements TouchableElement {

        @Override
        public Rectangle getBounds() {
            return bounds;
        }
    }

    private CalcButton[] buttons;

    /**
     * Initialize calculator button layout dynamically based on live display dimensions and orientation.
     */
    private void initButtons() {
        final var displayWidth = getDisplay().getWidth();
        final var displayHeight = getDisplay().getHeight();
        final var startY = displayHeight > displayWidth ? 100 : 50;
        final var btnWidth = displayWidth / 4;
        final var btnHeight = (displayHeight - startY) / 5;

        final String[] labels = {
            "C", "±", "%", "÷",
            "7", "8", "9", "×",
            "4", "5", "6", "-",
            "1", "2", "3", "+",
            "0", ".", "⌫", "="
        };

        buttons = new CalcButton[labels.length];
        for (var i = 0; i < labels.length; i++) {
            final var col = i % 4;
            final var row = i / 4;
            final var x = col * btnWidth;
            final var y = startY + (row * btnHeight);

            var color = new Color(60, 60, 60);
            if ("÷×-+=".contains(labels[i])) {
                color = new Color(255, 149, 0); // Orange for operators
            } else if ("C±%⌫".contains(labels[i])) {
                color = new Color(150, 150, 150); // Gray for utility
            }
            buttons[i] = new CalcButton(labels[i], new Rectangle(x, y, btnWidth, btnHeight), color);
        }
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
     * Draw calculator UI to the off-screen buffer and flush to display.
     *
     * @param display The unified display driver instance.
     */
    private void drawUI(final AbstractColorDisplay display) {
        final var displayWidth = display.getWidth();
        final var displayHeight = display.getHeight();
        final var startY = displayHeight > displayWidth ? 100 : 50;

        if (frameBuffer == null || frameBuffer.length != displayWidth * displayHeight * BYTES_PER_PIXEL) {
            frameBuffer = new byte[displayWidth * displayHeight * BYTES_PER_PIXEL];
        }

        final var g = getG2d();
        synchronized (this) {
            // Background
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, displayWidth, displayHeight);

            // Screen / Display readout area
            g.setColor(new Color(30, 30, 30));
            g.fillRect(10, 10, displayWidth - 20, startY - 20);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Monospaced", Font.BOLD, displayHeight > displayWidth ? 28 : 22));

            // Right align text in readout (shrink font if expression is too long)
            var metrics = g.getFontMetrics();
            var textWidth = metrics.stringWidth(displayExpression);
            if (textWidth > displayWidth - 40) {
                g.setFont(new Font("Monospaced", Font.BOLD, displayHeight > displayWidth ? 20 : 16));
                metrics = g.getFontMetrics();
                textWidth = metrics.stringWidth(displayExpression);
            }
            g.drawString(displayExpression, displayWidth - textWidth - 20, startY - 25);

            // Buttons
            g.setFont(new Font("SansSerif", Font.BOLD, displayHeight > displayWidth ? 24 : 18));
            for (final var btn : buttons) {
                g.setColor(btn.color);
                g.fillRect(btn.bounds.x + 2, btn.bounds.y + 2, btn.bounds.width - 4, btn.bounds.height - 4);
                g.setColor(Color.WHITE);

                final var bm = g.getFontMetrics();
                final var bx = btn.bounds.x + (btn.bounds.width - bm.stringWidth(btn.label)) / 2;
                final var by = btn.bounds.y + (btn.bounds.height + bm.getAscent()) / 2 - 4;
                g.drawString(btn.label, bx, by);
            }

            convertArgbToRgb565(getImage(), frameBuffer, displayWidth, displayHeight);
        }

        display.setWindow(0, 0, displayWidth, displayHeight);
        MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
        display.writeData(display.getImageSegment());
    }

    /**
     * Evaluate standard left-to-right calculation expression.
     *
     * @param expr Expression string (e.g. 1+2-3).
     * @return Calculated result.
     */
    private double evaluateExpression(final String expr) {
        final var sanitized = expr.replace("×", "*").replace("÷", "/");
        final var tokens = sanitized.split("(?=[+\\-*/])|(?<=[+\\-*/])");
        if (tokens.length == 0) {
            return 0;
        }

        double result = Double.parseDouble(tokens[0]);
        for (var i = 1; i < tokens.length; i += 2) {
            if (i + 1 >= tokens.length) {
                break;
            }
            final var op = tokens[i];
            final var val = Double.parseDouble(tokens[i + 1]);
            result = switch (op) {
                case "+" ->
                    result + val;
                case "-" ->
                    result - val;
                case "*" ->
                    result * val;
                case "/" ->
                    val != 0 ? result / val : 0;
                default ->
                    result;
            };
        }
        return result;
    }

    /**
     * Handle button press logic and build expression.
     *
     * @param label Button label.
     */
    private void handleButtonPress(final String label) {
        switch (label) {
            case "C" -> {
                displayExpression = "0";
                evaluateNext = false;
            }
            case "⌫" -> {
                if (displayExpression.length() > 1) {
                    displayExpression = displayExpression.substring(0, displayExpression.length() - 1);
                } else {
                    displayExpression = "0";
                }
            }
            case "0", "1", "2", "3", "4", "5", "6", "7", "8", "9" -> {
                if (evaluateNext || displayExpression.equals("0")) {
                    displayExpression = label;
                    evaluateNext = false;
                } else {
                    displayExpression += label;
                }
            }
            case "." -> {
                if (evaluateNext) {
                    displayExpression = "0.";
                    evaluateNext = false;
                } else {
                    displayExpression += ".";
                }
            }
            case "+", "-", "×", "÷" -> {
                evaluateNext = false;
                final var lastChar = displayExpression.isEmpty() ? ' ' : displayExpression.charAt(displayExpression.length() - 1);
                if ("+-\\×÷".indexOf(lastChar) >= 0) {
                    displayExpression = displayExpression.substring(0, displayExpression.length() - 1) + label;
                } else {
                    displayExpression += label;
                }
            }
            case "=" -> {
                try {
                    final var result = evaluateExpression(displayExpression);
                    var resStr = String.valueOf(result);
                    if (resStr.endsWith(".0")) {
                        resStr = resStr.substring(0, resStr.length() - 2);
                    }
                    displayExpression = resStr;
                    evaluateNext = true;
                } catch (final Exception e) {
                    displayExpression = "Error";
                    evaluateNext = true;
                }
            }
            default -> {
            }
        }
    }

    /**
     * Main execution loop for the calculator demo.
     *
     * @param display The unified display driver instance.
     * @param touch The unified touch driver instance.
     * @throws Exception If hardware communication fails.
     */
    private void runDemo(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        log.info("Calculator demo started using properties file: {}...", getTouchPropertiesFile());

        initButtons();
        drawUI(display);

        try {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                if (touch != null && touch.isPressed()) {
                    final var rawPoint = touch.readCoordinates();

                    // Utilize base class abstractions for rotation mapping and hit-testing
                    final var hitElement = findTouchedElement(
                            rawPoint.x(), rawPoint.y(),
                            buttons
                    );

                    if (hitElement != null) {
                        final var btn = (CalcButton) hitElement;
                        log.info("Button pressed: {}", btn.label);
                        handleButtonPress(btn.label);
                        drawUI(display);
                        TimeUnit.MILLISECONDS.sleep(250); // Debounce delay
                    }
                }
                TimeUnit.MILLISECONDS.sleep(50);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Calculator demo interrupted.");
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
        System.exit(new CommandLine(new TouchCalculator()).execute(args));
    }
}
