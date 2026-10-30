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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Touch-controlled Frogger arcade demo with moving traffic, river hazards, home slots, and optional PWM sound.
 *
 * <p>
 * Tap the directional pad at the bottom of the screen to hop. Reach an unoccupied home slot at the top without being hit by traffic
 * or carried off the river. Sound is disabled unless --enable-sound is supplied.</p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.2-SNAPSHOT
 * @since 1.0.0
 */
@Command(name = "TouchFrogger", mixinStandardHelpOptions = true, version = "1.0.2-SNAPSHOT",
        description = "Touch Frogger game demo with optional sound")
@Slf4j
public class TouchFrogger extends Base implements Callable<Integer> {

    /**
     * Number of bytes allocated per pixel for RGB565 format.
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Number of board columns.
     */
    private static final int COLS = 20;

    /**
     * Initial player lives count.
     */
    private static final int INITIAL_LIVES = 3;

    /**
     * Initial timer countdown per round.
     */
    private static final int INITIAL_TIME = 45;

    /**
     * Height reserved for the top HUD display strip.
     */
    private static final int HUD_HEIGHT = 13;

    /**
     * Cooldown period in milliseconds between frog hops.
     */
    private static final long HOP_COOLDOWN_MS = 110L;

    /**
     * Game over/win screen display duration timeout in milliseconds (5 seconds).
     */
    private static final long END_STATE_TIMEOUT_MS = 5000L;

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
    @Option(names = {"-sd", "--sound-device"}, description = "Sound PWM chip index or GPIO chip path.",
            defaultValue = "/dev/gpiochip0")
    private String soundDevice;

    /**
     * Sound PWM channel or GPIO line index.
     */
    @Option(names = {"-sc", "--sound-channel"}, description = "Sound PWM channel or GPIO line index.",
            defaultValue = "80")
    private int soundChannel;

    /**
     * Random number generator for lane obstacle creation.
     */
    private final Random random = new Random();

    /**
     * List of moving obstacles (cars, trucks, logs, turtles).
     */
    private final List<MovingObject> objects = new ArrayList<>();

    /**
     * Array representing the state of home slots at the top.
     */
    private final boolean[] homes = new boolean[5];

    /**
     * Raw byte buffer for transferring pixel data to display hardware.
     */
    private byte[] frameBuffer;

    /**
     * Grid cell dimension in pixels.
     */
    private int cell;

    /**
     * Height of each lane row in pixels.
     */
    private int laneHeight;

    /**
     * Bottom pixel coordinate of the playfield board.
     */
    private int boardBottom;

    /**
     * Current grid column position of the frog.
     */
    private int frogCol;

    /**
     * Current grid row position of the frog.
     */
    private int frogRow;

    /**
     * Horizontal ride offset when carried by logs/turtles.
     */
    private double frogRideOffset;

    /**
     * Remaining lives count.
     */
    private int lives;

    /**
     * Current score.
     */
    private int score;

    /**
     * Difficulty level.
     */
    private int level;

    /**
     * Remaining time countdown.
     */
    private int timeLeft;

    /**
     * Number of successfully completed home slots.
     */
    private int completedHomes;

    /**
     * Game over status flag.
     */
    private boolean gameOver;

    /**
     * Round won status flag.
     */
    private boolean roundWon;

    /**
     * Timestamp in nanoseconds of the previous rendered frame.
     */
    private long lastFrameNanos;

    /**
     * Timestamp in nanoseconds marking round start.
     */
    private long roundStartNanos;

    /**
     * Timestamp in milliseconds of the last frog hop.
     */
    private long lastHopMillis;

    /**
     * Timestamp in milliseconds when game over or win state started.
     */
    private long endStateMillis;

    /**
     * Sound manager instance for audio feedback.
     */
    private SoundManager soundManager;

    /**
     * Kind enumeration for obstacle types.
     */
    private enum Kind {
        CAR, TRUCK, LOG, TURTLE
    }

    /**
     * Represents a moving obstacle item in river or road lanes.
     */
    private static final class MovingObject {

        double x;
        final int row;
        final double speed;
        final int length;
        final Kind kind;

        MovingObject(final double x, final int row, final double speed, final int length, final Kind kind) {
            this.x = x;
            this.row = row;
            this.speed = speed;
            this.length = length;
            this.kind = kind;
        }
    }

    /**
     * Calculates the vertical center Y coordinate for a given grid row.
     *
     * @param row Grid row index.
     * @return Y pixel coordinate.
     */
    private int rowY(final int row) {
        return HUD_HEIGHT + row * laneHeight + laneHeight / 2;
    }

    /**
     * Initializes or resets the game state, layout metrics, and lanes.
     *
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void initGame(final int width, final int height) {
        cell = Math.max(8, width / COLS);
        laneHeight = Math.max(10, cell);
        boardBottom = height - 35;
        frogCol = COLS / 2;
        frogRow = 14;
        frogRideOffset = 0;
        lives = INITIAL_LIVES;
        score = 0;
        level = 1;
        timeLeft = INITIAL_TIME;
        completedHomes = 0;
        gameOver = false;
        roundWon = false;
        endStateMillis = 0L;
        for (var i = 0; i < homes.length; i++) {
            homes[i] = false;
        }
        objects.clear();
        buildLanes(width, height);
        roundStartNanos = System.nanoTime();
        lastHopMillis = 0;
        if (soundManager != null) {
            soundManager.playGameStart();
        }
    }

    /**
     * Builds and populates moving obstacle lanes for the river and roadways.
     *
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void buildLanes(final int width, final int height) {
        objects.clear();
        for (var row = 2; row <= 6; row++) {
            final var river = row <= 4;
            final var kind = river ? (row % 2 == 0 ? Kind.LOG : Kind.TURTLE)
                    : (row % 2 == 0 ? Kind.TURTLE : Kind.LOG);
            final var direction = row % 2 == 0 ? 1.0 : -1.0;
            final var count = row == 3 || row == 5 ? 3 : 2;
            final var spacing = (double) width / count;
            final var length = kind == Kind.LOG ? 4 : 3;
            for (var i = 0; i < count; i++) {
                final var x = i * spacing + random.nextDouble() * Math.min(12, spacing * 0.15);
                final var speed = (river ? 13.0 : 10.0) * direction * (1.0 + level * 0.08)
                        * (kind == Kind.LOG ? 1.0 : 1.15);
                objects.add(new MovingObject(x, row, speed, length, kind));
            }
        }
        for (var row = 9; row <= 12; row++) {
            final var count = row % 2 == 0 ? 3 : 2;
            final var spacing = (double) width / count;
            final var kind = row % 3 == 0 ? Kind.TRUCK : Kind.CAR;
            final var length = kind == Kind.TRUCK ? 3 : 2;
            final var direction = row % 2 == 0 ? -1.0 : 1.0;
            for (var i = 0; i < count; i++) {
                final var x = i * spacing + random.nextDouble() * Math.min(10, spacing * 0.15);
                final var speed = (kind == Kind.TRUCK ? 23.0 : 29.0) * direction * (1.0 + level * 0.10);
                objects.add(new MovingObject(x, row, speed, length, kind));
            }
        }
    }

    /**
     * Plays the hop sound effect.
     */
    private void playHop() {
        if (soundManager != null) {
            soundManager.playFire();
        }
    }

    /**
     * Handles life loss, explosions, and game-over state transitions.
     */
    private void loseLife() {
        if (gameOver || roundWon) {
            return;
        }
        lives--;
        if (soundManager != null) {
            soundManager.playExplosion();
        }
        if (lives <= 0) {
            gameOver = true;
            endStateMillis = System.currentTimeMillis();
            if (soundManager != null) {
                soundManager.playGameOver();
            }
        } else {
            frogCol = COLS / 2;
            frogRow = 14;
            frogRideOffset = 0;
            roundStartNanos = System.nanoTime();
        }
    }

    /**
     * Executes a frog hop movement step given column and row increments.
     *
     * @param dc Column increment.
     * @param dr Row increment.
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void hop(final int dc, final int dr, final int width, final int height) {
        if (gameOver || roundWon) {
            initGame(width, height);
            return;
        }
        final var now = System.nanoTime() / 1_000_000L;
        if (now - lastHopMillis < HOP_COOLDOWN_MS) {
            return;
        }
        lastHopMillis = now;
        frogCol = Math.max(0, Math.min(COLS - 1, frogCol + dc));
        frogRow = Math.max(0, Math.min(14, frogRow + dr));
        frogRideOffset = 0;
        playHop();
        if (frogRow == 0) {
            final var home = Math.max(0, Math.min(4, (int) ((frogCol + 0.5) * 5 / COLS)));
            if (homes[home]) {
                loseLife();
            } else {
                homes[home] = true;
                completedHomes++;
                score += Math.max(50, timeLeft * 10) + level * 25;
                if (soundManager != null) {
                    soundManager.playGameStart();
                }
                if (completedHomes == homes.length) {
                    roundWon = true;
                    endStateMillis = System.currentTimeMillis();
                } else {
                    frogCol = COLS / 2;
                    frogRow = 14;
                    frogRideOffset = 0;
                    timeLeft = INITIAL_TIME;
                    roundStartNanos = System.nanoTime();
                }
            }
        }
    }

    /**
     * Checks if a given row lies within the river zone.
     *
     * @param row Grid row index.
     * @return True if in river zone.
     */
    private boolean onRiver(final int row) {
        return row >= 2 && row <= 6;
    }

    /**
     * Updates obstacle positions, timer countdowns, and collision hazards per time step.
     *
     * @param dt Delta time elapsed in seconds.
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void update(final double dt, final int width, final int height) {
        if (gameOver || roundWon) {
            // Check if 5-second timeout has elapsed on game over/win screen
            if (System.currentTimeMillis() - endStateMillis > END_STATE_TIMEOUT_MS) {
                initGame(width, height);
            }
            return;
        }

        final var elapsed = (System.nanoTime() - roundStartNanos) / 1_000_000_000L;
        timeLeft = Math.max(0, INITIAL_TIME - (int) elapsed);
        if (timeLeft == 0) {
            loseLife();
            return;
        }

        for (final var object : objects) {
            object.x += object.speed * dt;
            final var objectWidth = object.length * cell;
            if (object.speed > 0 && object.x > width + objectWidth) {
                object.x = -objectWidth;
            } else if (object.speed < 0 && object.x < -objectWidth) {
                object.x = width;
            }
        }

        final var frogX = (frogCol + 0.5) * width / COLS + frogRideOffset;
        if (frogRow >= 9 && frogRow <= 12) {
            for (final var object : objects) {
                if (object.row != frogRow || (object.kind != Kind.CAR && object.kind != Kind.TRUCK)) {
                    continue;
                }
                final var left = object.x;
                final var right = object.x + object.length * cell;
                if (frogX >= left - cell * 0.25 && frogX <= right + cell * 0.25) {
                    loseLife();
                    return;
                }
            }
        } else if (onRiver(frogRow)) {
            MovingObject support = null;
            for (final var object : objects) {
                if (object.row != frogRow || (object.kind != Kind.LOG && object.kind != Kind.TURTLE)) {
                    continue;
                }
                final var left = object.x;
                final var right = object.x + object.length * cell;
                if (frogX >= left && frogX <= right) {
                    support = object;
                    break;
                }
            }
            if (support == null) {
                loseLife();
                return;
            }
            frogRideOffset += support.speed * dt;
            final var carriedX = (frogCol + 0.5) * width / COLS + frogRideOffset;
            if (carriedX < cell / 2.0 || carriedX > width - cell / 2.0) {
                loseLife();
                return;
            }
        }
    }

    /**
     * Draws the player frog on the screen.
     *
     * @param g Graphics2D rendering context.
     * @param width Display width in pixels.
     */
    private void drawFrog(final Graphics2D g, final int width) {
        final var x = (int) Math.round((frogCol + 0.5) * width / COLS + frogRideOffset);
        final var y = rowY(frogRow);
        g.setColor(new Color(40, 240, 70));
        g.fillOval(x - cell / 2 + 1, y - cell / 2 + 1, cell - 2, cell - 2);
        g.setColor(Color.BLACK);
        g.fillOval(x - cell / 4, y - cell / 5, Math.max(2, cell / 7), Math.max(2, cell / 7));
        g.fillOval(x + cell / 8, y - cell / 5, Math.max(2, cell / 7), Math.max(2, cell / 7));
        g.setColor(Color.WHITE);
        g.fillOval(x - cell / 4, y - cell / 5, Math.max(1, cell / 14), Math.max(1, cell / 14));
        g.fillOval(x + cell / 8, y - cell / 5, Math.max(1, cell / 14), Math.max(1, cell / 14));
    }

    /**
     * Draws a moving obstacle object.
     *
     * @param g Graphics2D rendering context.
     * @param object MovingObject to render.
     * @param width Display width in pixels.
     */
    private void drawObject(final Graphics2D g, final MovingObject object, final int width) {
        final var x = (int) Math.round(object.x);
        final var y = rowY(object.row);
        final var w = object.length * cell;
        switch (object.kind) {
            case CAR -> {
                g.setColor(object.speed < 0 ? Color.RED : Color.ORANGE);
                g.fillRoundRect(x, y - cell / 3, w, 2 * cell / 3, 3, 3);
                g.setColor(Color.WHITE);
                g.fillRect(x + w / 4, y - cell / 4, Math.max(2, w / 4), Math.max(2, cell / 5));
            }
            case TRUCK -> {
                g.setColor(object.speed < 0 ? new Color(180, 70, 255) : Color.YELLOW);
                g.fillRoundRect(x, y - cell / 3, w, 2 * cell / 3, 2, 2);
                g.setColor(Color.LIGHT_GRAY);
                g.fillRect(x + w - cell, y - cell / 4, Math.max(2, cell / 2), Math.max(2, cell / 2));
            }
            case LOG -> {
                g.setColor(new Color(145, 85, 35));
                g.fillRoundRect(x, y - cell / 3, w, 2 * cell / 3, 4, 4);
                g.setColor(new Color(205, 150, 75));
                for (var i = 1; i < object.length; i++) {
                    g.drawLine(x + i * cell, y - cell / 4, x + i * cell, y + cell / 4);
                }
            }
            case TURTLE -> {
                g.setColor(new Color(20, 145, 130));
                for (var i = 0; i < object.length; i++) {
                    final var tx = x + i * cell;
                    g.fillOval(tx + 1, y - cell / 3, cell - 2, 2 * cell / 3);
                    g.setColor(new Color(80, 205, 150));
                    g.drawOval(tx + 2, y - cell / 4, Math.max(2, cell - 5), Math.max(2, cell / 2));
                    g.setColor(new Color(20, 145, 130));
                }
            }
        }
    }

    /**
     * Draws the top HUD telemetry strip.
     *
     * @param g Graphics2D rendering context.
     * @param width Display width in pixels.
     */
    private void drawHud(final Graphics2D g, final int width) {
        g.setFont(new Font("Monospaced", Font.BOLD, 9));
        g.setColor(Color.WHITE);
        g.drawString("S:" + score, 3, 10);
        g.setColor(Color.YELLOW);
        g.drawString("TIME:" + timeLeft, Math.max(3, width / 2 - 24), 10);
        g.setColor(Color.GREEN);
        g.drawString("L:" + lives + " W:" + level, Math.max(3, width - 48), 10);
    }

    /**
     * Draws touch directional control buttons at the bottom.
     *
     * @param g Graphics2D rendering context.
     * @param width Display width in pixels.
     * @param height Display height in pixels.
     */
    private void drawControls(final Graphics2D g, final int width, final int height) {
        final var top = height - 33;
        g.setColor(new Color(25, 25, 40));
        g.fillRect(0, top, width, 33);
        final var buttonW = width / 4;
        final String[] labels = {"LEFT", "UP", "DOWN", "RIGHT"};
        for (var i = 0; i < labels.length; i++) {
            final var x = i * buttonW;
            g.setColor(i == 1 ? new Color(20, 100, 30) : new Color(40, 55, 70));
            g.fillRoundRect(x + 2, top + 3, buttonW - 4, 27, 5, 5);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Monospaced", Font.BOLD, 9));
            final var metrics = g.getFontMetrics();
            g.drawString(labels[i], x + (buttonW - metrics.stringWidth(labels[i])) / 2, top + 20);
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
        final var hudHeight = HUD_HEIGHT;
        final var boardTop = hudHeight;
        final var rowH = Math.max(10, (height - hudHeight - 35) / 15);
        laneHeight = rowH;
        boardBottom = hudHeight + 15 * rowH;

        g.setColor(new Color(20, 80, 30));
        g.fillRect(0, boardTop, width, rowH * 2);
        final var homeW = width / 5;
        for (var i = 0; i < homes.length; i++) {
            final var x = i * homeW;
            g.setColor(homes[i] ? new Color(15, 35, 20) : new Color(0, 45, 20));
            g.fillRoundRect(x + 2, boardTop + 2, homeW - 4, rowH - 4, 4, 4);
            if (homes[i]) {
                g.setColor(Color.GREEN);
                g.fillOval(x + homeW / 2 - cell / 3, boardTop + rowH / 2 - cell / 3, 2 * cell / 3, 2 * cell / 3);
            } else {
                g.setColor(Color.YELLOW);
                g.drawString("HOME", x + 3, boardTop + rowH / 2 + 3);
            }
        }

        g.setColor(new Color(25, 85, 30));
        g.fillRect(0, rowH * 7 + hudHeight, width, rowH);
        g.fillRect(0, rowH * 13 + hudHeight, width, rowH * 2);
        g.setColor(new Color(20, 45, 55));
        g.fillRect(0, rowH * 2 + hudHeight, width, rowH * 5);
        g.setColor(new Color(45, 45, 48));
        g.fillRect(0, rowH * 8 + hudHeight, width, rowH * 5);
        g.setColor(new Color(220, 205, 120));
        for (var row = 9; row <= 12; row++) {
            final var y = hudHeight + row * rowH;
            for (var x = 0; x < width; x += 18) {
                g.drawLine(x, y, x + 7, y);
            }
        }

        for (final var object : objects) {
            drawObject(g, object, width);
        }
        drawFrog(g, width);
        drawHud(g, width);
        drawControls(g, width, height);

        if (gameOver || roundWon) {
            g.setColor(new Color(0, 0, 0, 190));
            g.fillRoundRect(20, height / 2 - 24, width - 40, 48, 8, 8);
            g.setColor(gameOver ? Color.RED : Color.GREEN);
            g.setFont(new Font("Monospaced", Font.BOLD, 14));
            final var title = gameOver ? "GAME OVER" : "YOU WIN!";
            g.drawString(title, (width - g.getFontMetrics().stringWidth(title)) / 2, height / 2 - 3);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Monospaced", Font.PLAIN, 8));
            final var sub = "TOUCH OR 5S TIMEOUT";
            g.drawString(sub, (width - g.getFontMetrics().stringWidth(sub)) / 2, height / 2 + 12);
        }

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
     * Executes the main real-time game loop handling touch events, updates, and frame rendering.
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
                    final var raw = xpt.readCoordinates();
                    final var point = mapTouchToScreen(raw.x(), raw.y());
                    final var x = Math.max(0, Math.min(width - 1, point.x));
                    final var y = Math.max(0, Math.min(height - 1, point.y));
                    if (gameOver || roundWon) {
                        initGame(width, height);
                    } else if (y >= height - 35) {
                        final var section = Math.min(3, x * 4 / width);
                        switch (section) {
                            case 0 ->
                                hop(-1, 0, width, height);
                            case 1 ->
                                hop(0, -1, width, height);
                            case 2 ->
                                hop(0, 1, width, height);
                            case 3 ->
                                hop(1, 0, width, height);
                            default -> {
                            }
                        }
                    } else {
                        final var frogX = (frogCol + 0.5) * width / COLS;
                        final var frogY = rowY(frogRow);
                        if (Math.abs(x - frogX) > Math.abs(y - frogY)) {
                            hop(x < frogX ? -1 : 1, 0, width, height);
                        } else {
                            hop(0, y < frogY ? -1 : 1, width, height);
                        }
                    }
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
        log.info("Frogger demo started on device {} line {} (sound enabled: {})...",
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
        System.exit(new CommandLine(new TouchFrogger()).execute(args));
    }
}
