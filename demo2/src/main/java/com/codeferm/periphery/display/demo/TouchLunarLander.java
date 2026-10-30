/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import com.codeferm.periphery.device.PassiveSpeaker;
import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.device.Xpt2046;
import com.codeferm.periphery.sound.SoundManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Touch-controlled Lunar Lander demo with optional PWM sound support.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.1
 * @since 1.0.0
 */
@Command(name = "TouchLunarLander", mixinStandardHelpOptions = true, version = "1.0.1-SNAPSHOT",
        description = "Touch Lunar Lander game demo with optional sound")
@Slf4j
public class TouchLunarLander extends Base implements Callable<Integer> {

    /**
     * Number of bytes allocated per pixel for RGB565 format.
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Gravitational pull acceleration constant.
     */
    private static final double GRAVITY = 22.0;

    /**
     * Impulse velocity magnitude applied on each engine thrust tap.
     */
    private static final double THRUST_TAP_IMPULSE = 7.0;

    /**
     * Angular rotation step size per touch input in radians.
     */
    private static final double ROTATION_STEP = Math.toRadians(12.0);

    /**
     * Maximum safe vertical touchdown speed for a successful landing.
     */
    private static final double MAX_SAFE_VERTICAL_SPEED = 15.0;

    /**
     * Maximum safe horizontal touchdown speed for a successful landing.
     */
    private static final double MAX_SAFE_HORIZONTAL_SPEED = 12.0;

    /**
     * Maximum safe landing angle tilt limit in radians.
     */
    private static final double MAX_SAFE_ANGLE = Math.toRadians(18.0);

    /**
     * Collision bounding radius of the lunar module.
     */
    private static final double SHIP_RADIUS = 5.0;

    /**
     * Starting fuel reserve amount.
     */
    private static final double INITIAL_FUEL = 100.0;

    /**
     * Fuel consumption cost per engine thrust activation.
     */
    private static final double FUEL_PER_THRUST = 4.0;

    /**
     * Flag indicating whether sound effects are enabled.
     */
    @Option(names = {"-es", "--enable-sound"}, description = "Enable sound effects.", defaultValue = "false")
    private boolean enableSound;

    /**
     * PWM sound mode configuration (HW or SW).
     */
    @Option(names = {"-sm", "--sound-mode"}, description = "Sound PWM Mode: HW or SW.", defaultValue = "SW")
    private String soundMode;

    /**
     * Sound device chip index or GPIO chip path.
     */
    @Option(names = {"-sd", "--sound-device"}, description = "Sound PWM chip index or GPIO chip path.", defaultValue
            = "/dev/gpiochip0")
    private String soundDevice;

    /**
     * Sound PWM channel or GPIO line index.
     */
    @Option(names = {"-sc", "--sound-channel"}, description = "Sound PWM channel or GPIO line index.", defaultValue = "80")
    private int soundChannel;

    /**
     * Random number generator for placement and star fields.
     */
    private final Random random = new Random();

    /**
     * Raw byte buffer for transferring pixel data to display hardware.
     */
    private byte[] frameBuffer;

    /**
     * X coordinates of rendered background stars.
     */
    private int[] starX;

    /**
     * Y coordinates of rendered background stars.
     */
    private int[] starY;

    /**
     * Current horizontal and vertical position, velocities, angle, fuel level, and score of the ship.
     */
    private double shipX, shipY, velocityX, velocityY, angle, fuel, score;

    /**
     * Game state flags for completion, landing success, and thruster animation flash.
     */
    private boolean gameOver, landed, thrustFlash;

    /**
     * Timestamp in nanoseconds of the previous rendered frame.
     */
    private long lastFrameNanos;

    /**
     * Sound manager instance for handling audio feedback.
     */
    private SoundManager soundManager;

    /**
     * Landing pad left boundary, right boundary, and vertical coordinate.
     */
    private int padLeft, padRight, padY;

    /**
     * Initializes or resets the game state, ship parameters, landing pad, and starfield.
     *
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void initGame(final int width, final int height) {
        shipX = width * (0.18 + random.nextDouble() * 0.64);
        shipY = Math.max(24, height * 0.20);
        velocityX = (random.nextDouble() - 0.5) * 5.0;
        velocityY = 0;
        angle = 0;
        fuel = INITIAL_FUEL;
        score = 0;
        gameOver = false;
        landed = false;
        thrustFlash = false;
        padLeft = (int) (width * 0.39);
        padRight = (int) (width * 0.61);
        padY = (int) (height * 0.82);
        starX = new int[Math.max(18, width * height / 850)];
        starY = new int[starX.length];
        for (var i = 0; i < starX.length; i++) {
            starX[i] = random.nextInt(width);
            starY[i] = 14 + random.nextInt(Math.max(1, padY - 20));
        }
        if (soundManager != null) {
            soundManager.playGameStart();
        }
    }

    /**
     * Handles touch input zones to rotate the ship, apply thruster impulse, or restart the game.
     *
     * @param x Touch X coordinate on screen.
     * @param width Display width in pixels.
     */
    private void handleTouch(final double x, final int width) {
        if (gameOver || landed) {
            initGame(width, getHeight());
        } else if (x < width / 3.0) {
            angle -= ROTATION_STEP;
        } else if (x >= width * 2.0 / 3.0) {
            angle += ROTATION_STEP;
        } else if (fuel > 0) {
            // A tap produces a short engine pulse; repeated taps provide fine control.
            velocityX += Math.sin(angle) * THRUST_TAP_IMPULSE;
            velocityY -= Math.cos(angle) * THRUST_TAP_IMPULSE;
            fuel = Math.max(0, fuel - FUEL_PER_THRUST);
            thrustFlash = true;
            if (soundManager != null) {
                soundManager.playFire();
            }
        }
        angle = Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    /**
     * Calculates the surface terrain elevation Y coordinate for a given horizontal position.
     *
     * @param x Horizontal position coordinate.
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     * @return Surface Y coordinate value.
     */
    private double terrainY(final double x, final int width, final int height) {
        if (x >= padLeft && x <= padRight) {
            return padY;
        }
        final var phase = x / Math.max(1.0, width) * Math.PI * 5.0;
        return height * 0.82 + 9.0 * Math.sin(phase) + 4.0 * Math.sin(phase * 2.7);
    }

    /**
     * Updates physics, trajectory, collision detection, and game states per time step.
     *
     * @param dt Delta time elapsed in seconds.
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void update(final double dt, final int width, final int height) {
        if (gameOver || landed) {
            return;
        }
        velocityY += GRAVITY * dt;
        shipX += velocityX * dt;
        shipY += velocityY * dt;
        if (shipX < -SHIP_RADIUS) {
            shipX = width + SHIP_RADIUS;
        } else if (shipX > width + SHIP_RADIUS) {
            shipX = -SHIP_RADIUS;
        }

        final var ground = terrainY(shipX, width, height);
        if (shipY + SHIP_RADIUS >= ground) {
            shipY = ground - SHIP_RADIUS;
            final var onPad = shipX >= padLeft + 5 && shipX <= padRight - 5;
            if (onPad && Math.abs(velocityY) <= MAX_SAFE_VERTICAL_SPEED
                    && Math.abs(velocityX) <= MAX_SAFE_HORIZONTAL_SPEED && Math.abs(angle) <= MAX_SAFE_ANGLE) {
                landed = true;
                score = Math.max(100, (int) (fuel * 10));
                if (soundManager != null) {
                    soundManager.playGameStart();
                }
            } else {
                gameOver = true;
                playCrashSounds();
            }
        } else if (shipY > height + 10) {
            gameOver = true;
            playCrashSounds();
        }
    }

    /**
     * Triggers explosion and game-over sound effects upon crashing.
     */
    private void playCrashSounds() {
        if (soundManager != null) {
            soundManager.playExplosion();
            soundManager.playGameOver();
        }
    }

    /**
     * Draws the lunar lander spacecraft polygon and optional exhaust flame flash.
     *
     * @param g Graphics2D rendering context.
     */
    private void drawShip(final Graphics2D g) {
        final double[][] points = {{0, -7}, {-5, 5}, {0, 3}, {5, 5}};
        final var xs = new int[points.length];
        final var ys = new int[points.length];
        final var sin = Math.sin(angle);
        final var cos = Math.cos(angle);
        for (var i = 0; i < points.length; i++) {
            final var lx = points[i][0];
            final var ly = points[i][1];
            xs[i] = (int) Math.round(shipX + lx * cos + ly * sin);
            ys[i] = (int) Math.round(shipY - lx * sin + ly * cos);
        }
        g.setColor(Color.WHITE);
        g.drawPolygon(xs, ys, points.length);
        if (thrustFlash && fuel > 0 && !landed && !gameOver) {
            g.setColor(Color.ORANGE);
            g.drawLine((int) (shipX - sin * 2), (int) (shipY + cos * 5),
                    (int) (shipX - sin * 7 + random.nextInt(3) - 1),
                    (int) (shipY + cos * 11 + random.nextInt(3) - 1));
        }
    }

    /**
     * Draws horizontally centered text strings on the screen.
     *
     * @param g Graphics2D rendering context.
     * @param text Text string to render.
     * @param width Display width in pixels.
     * @param y Vertical baseline coordinate.
     */
    private void drawCentered(final Graphics2D g, final String text, final int width, final int y) {
        final var metrics = g.getFontMetrics();
        g.drawString(text, (width - metrics.stringWidth(text)) / 2, y);
    }

    /**
     * Renders the heads-up display (HUD), telemetry metrics, and status overlays.
     *
     * @param g Graphics2D rendering context.
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void drawHud(final Graphics2D g, final int width, final int height) {
        g.setFont(new Font("Monospaced", Font.BOLD, 9));
        g.setColor(Color.GREEN);
        g.drawString("FUEL " + (int) fuel, 3, 10);
        g.setColor(Color.YELLOW);
        g.drawString(String.format(java.util.Locale.ROOT, "V:%.1f", velocityY), 3, 21);
        g.drawString(String.format(java.util.Locale.ROOT, "H:%.1f", velocityX), Math.max(3, width - 54), 10);
        g.setColor(Color.WHITE);
        g.drawString("S:" + (int) score, Math.max(3, width - 54), 21);
        final var stripY = height - 13;
        g.setColor(Color.CYAN);
        g.drawString("ROT-L", 3, stripY);
        g.setColor(Color.ORANGE);
        g.drawString("THRUST", Math.max(3, width / 2 - 17), stripY);
        g.setColor(Color.CYAN);
        g.drawString("ROT-R", Math.max(3, width - 34), stripY);
        if (landed) {
            g.setColor(Color.GREEN);
            g.setFont(new Font("Monospaced", Font.BOLD, 14));
            drawCentered(g, "LANDED!", width, height / 2 - 3);
            g.setFont(new Font("Monospaced", Font.PLAIN, 8));
            drawCentered(g, "TOUCH TO RETRY", width, height / 2 + 12);
        } else if (gameOver) {
            g.setColor(Color.RED);
            g.setFont(new Font("Monospaced", Font.BOLD, 15));
            drawCentered(g, "CRASH!", width, height / 2 - 3);
            g.setColor(Color.YELLOW);
            g.setFont(new Font("Monospaced", Font.PLAIN, 8));
            drawCentered(g, "TOUCH TO RETRY", width, height / 2 + 12);
        }
    }

    /**
     * Converts an ARGB image buffer into RGB565 format bytes for display transmission.
     *
     * @param image Source BufferedImage in ARGB format.
     * @param dest Destination byte array for RGB565 data.
     */
    private void convertArgbToRgb565(final BufferedImage image, final byte[] dest) {
        final var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        var out = 0;
        for (final var pixel : pixels) {
            final var r = (pixel >> 16) & 0xff;
            final var green = (pixel >> 8) & 0xff;
            final var b = pixel & 0xff;
            final var rgb565 = ((r & 0xf8) << 8) | ((green & 0xfc) << 3) | (b >> 3);
            dest[out++] = (byte) (rgb565 >> 8);
            dest[out++] = (byte) rgb565;
        }
    }

    /**
     * Renders a complete graphics frame and transfers it to the physical display device.
     *
     * @param display Abstract color display driver instance.
     */
    private void renderFrame(final AbstractColorDisplay display) {
        final var width = getWidth();
        final var height = getHeight();
        if (frameBuffer == null || frameBuffer.length != width * height * BYTES_PER_PIXEL) {
            frameBuffer = new byte[width * height * BYTES_PER_PIXEL];
        }
        final var g = getG2d();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        for (var i = 0; i < starX.length; i++) {
            g.setColor(i % 5 == 0 ? Color.CYAN : Color.GRAY);
            g.fillRect(starX[i], starY[i], 1, 1);
        }
        g.setColor(Color.GRAY);
        var previousX = 0;
        var previousY = (int) terrainY(0, width, height);
        for (var x = 1; x < width; x++) {
            final var y = (int) terrainY(x, width, height);
            g.drawLine(previousX, previousY, x, y);
            previousX = x;
            previousY = y;
        }
        g.setColor(Color.GREEN);
        g.drawLine(padLeft, padY, padRight, padY);
        g.drawLine(padLeft, padY - 2, padLeft, padY + 2);
        g.drawLine(padRight, padY - 2, padRight, padY + 2);
        drawShip(g);
        drawHud(g, width, height);
        thrustFlash = false;
        convertArgbToRgb565(getImage(), frameBuffer);
        display.setWindow(0, 0, width, height);
        MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
        display.writeData(display.getImageSegment());
    }

    /**
     * Clears the display screen to pitch black upon application exit for clean shutdown.
     *
     * @param display Abstract color display driver instance.
     */
    private void clearScreen(final AbstractColorDisplay display) {
        final var width = getWidth();
        final var height = getHeight();
        final var blackBuffer = new byte[width * height * BYTES_PER_PIXEL];
        try {
            display.setWindow(0, 0, width, height);
            MemorySegment.copy(blackBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, blackBuffer.length);
            display.writeData(display.getImageSegment());
            log.info("Display screen cleared successfully.");
        } catch (final Exception e) {
            log.error("Error clearing display screen during shutdown", e);
        }
    }

    /**
     * Executes the main real-time game loop handling touch events, physics updates, and frame rendering.
     *
     * @param display Abstract color display driver instance.
     * @param touch Abstract touch input driver instance.
     * @throws Exception if an interruption or hardware error occurs.
     */
    private void runGameLoop(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        final var width = getWidth();
        final var height = getHeight();
        initGame(width, height);
        renderFrame(display);
        final var xpt = (Xpt2046) touch;
        lastFrameNanos = System.nanoTime();
        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            final var now = System.nanoTime();
            var dt = (now - lastFrameNanos) / 1_000_000_000.0;
            lastFrameNanos = now;
            dt = Math.min(dt, 0.050);
            if (xpt.pollEvent(1)) {
                final var edge = xpt.getEdge();
                if (edge == Periphery.GPIO_EDGE_FALLING() && xpt.isPressed()) {
                    final var rawPoint = xpt.readCoordinates();
                    final var point = mapTouchToScreen(rawPoint.x(), rawPoint.y());
                    final var touchX = Math.max(0, Math.min(point.x, width - 1));
                    handleTouch(touchX, width);
                }
            }
            update(dt, width, height);
            renderFrame(display);
        }
    }

    /**
     * Initializes optional audio managers and starts the game loop execution.
     *
     * @param display Abstract color display driver instance.
     * @param touch Abstract touch input driver instance.
     * @throws Exception if an interruption or hardware error occurs.
     */
    private void runDemo(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        log.info("Lunar Lander demo started on device {} line {} (sound enabled: {})...",
                getGpioDevice(), getTouchIrqLine(), enableSound);
        if (enableSound) {
            try (final var speaker = new PassiveSpeaker(PwmDeviceFactory.create(soundMode, soundDevice, soundChannel)); final var manager
                    = new SoundManager(speaker)) {
                soundManager = manager;
                runGameLoop(display, touch);
            } finally {
                soundManager = null;
            }
        } else {
            soundManager = null;
            runGameLoop(display, touch);
        }
    }

    /**
     * Entry point for Picocli command execution.
     *
     * @return Exit code integer value (0 for success).
     * @throws Exception if initialization or execution fails.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo(getDisplay(), getTouch());
        } finally {
            try {
                clearScreen(getDisplay());
            } catch (final Exception e) {
                log.error("Error during display cleanup", e);
            }
            done();
        }
        return 0;
    }

    /**
     * Main application execution entry point.
     *
     * @param args Command-line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new TouchLunarLander()).execute(args));
    }
}
