/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import com.codeferm.u8g2.U8g2Factory;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Centipede with Smooth Interpolation.
 * <p>
 * A high-performance implementation of the classic arcade game Centipede, optimized for low-resolution displays (128x64 or
 * 128x128). This version utilizes Java's Foreign Function & Memory (FFM) API for zero-allocation rendering loops and Delta-Time
 * scaling with LERP smoothing to eliminate jerkiness at low frame rates (e.g., 14 FPS on ARM hardware).
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Centipede", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Centipede - Pro FFM Implementation")
public class Centipede extends Base {

    /**
     * Target Frames Per Second for the game loop.
     */
    @Option(names = {"-f", "--fps"}, description = "Target FPS", defaultValue = "60")
    private int targetFps;

    /**
     * Random number generator for game logic and spawning.
     */
    private final Random random = new Random();

    /**
     * Control flag for the main game loop.
     */
    private boolean running = true;

    /**
     * Speed of the centipede in pixels per second.
     */
    private static final float CENTIPEDE_SPEED = 42.0f;

    /**
     * Speed of the spider in pixels per second.
     */
    private static final float SPIDER_SPEED = 65.0f;

    /**
     * Speed of the player's projectile in pixels per second.
     */
    private static final float SHOT_SPEED = 145.0f;

    /**
     * Smoothing factor for LERP movement (8.0f is ideal for ~14 FPS).
     */
    private static final float PLAYER_LERP_FACTOR = 8.0f;

    /**
     * Collection of active mushrooms on the field.
     */
    private final List<Mushroom> mushrooms = new ArrayList<>();

    /**
     * Collection of active centipede segments.
     */
    private final List<Segment> centipede = new ArrayList<>();

    /**
     * Current spider enemy, if any.
     */
    private Spider spider = null;

    /**
     * Current player projectile, if any.
     */
    private Projectile playerShot = null;

    /**
     * Current player X position (floating point for smooth LERP).
     */
    private float playerX;

    /**
     * Current player Y position (fixed in player zone).
     */
    private float playerY;

    /**
     * Current player score.
     */
    private int score = 0;

    /**
     * Current player lives remaining.
     */
    private int lives = 3;

    /**
     * Flag indicating if the game has ended.
     */
    private boolean gameOver = false;

    /**
     * Organic mushroom destruction bitmaps (5x5).
     */
    private static final int[][] MUSH_STAGES = {
        {0, 0, 0, 0, 0},
        {0x00, 0x00, 0x04, 0x04, 0x04},
        {0x04, 0x0E, 0x0E, 0x04, 0x04},
        {0x0E, 0x1F, 0x1F, 0x04, 0x04}
    };

    /**
     * Sprite bits for segments and ship.
     */
    private static final int[] SEG_BITS = {0x0E, 0x1F, 0x15, 0x1F, 0x0E};
    private static final int[] SHIP_BITS = {0x04, 0x0E, 0x0E, 0x1F, 0x1F};

    /**
     * 3-Frame Spider Animation bitmaps (7x7).
     */
    private static final int[][] SPID_ANIM = {
        {0x49, 0x2A, 0x1C, 0x3E, 0x1C, 0x2A, 0x49},
        {0x00, 0x49, 0x2A, 0x3E, 0x2A, 0x49, 0x00},
        {0x08, 0x14, 0x22, 0x7F, 0x22, 0x14, 0x08}
    };

    /**
     * Native memory arena for long-lived segments.
     */
    private final Arena persistentArena = Arena.ofShared();
    private MemorySegment fontMain;
    private MemorySegment scoreSegment;
    private MemorySegment livesSegment;
    private MemorySegment gameOverSegment;

    /**
     * Entity class for Mushrooms.
     */
    public static class Mushroom {

        public int x, y, health;

        public Mushroom(final int x, final int y) {
            this.x = x;
            this.y = y;
            this.health = 3;
        }
    }

    /**
     * Entity class for Centipede segments.
     */
    public static class Segment {

        public float x, y;
        public int dx = 1;

        public Segment(final float x, final float y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Entity class for Spider with vector movement.
     */
    public static class Spider {

        public float x, y, vx, vy;
        public float timer, animTimer, lifeTimer;
        public int animFrame;

        public Spider(final float x, final float y, final float vx, final float vy) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.timer = 0.5f;
            this.animTimer = 0.1f;
            this.lifeTimer = 10.0f;
        }
    }

    /**
     * Entity class for Player projectiles.
     */
    public static class Projectile {

        public float x, y;

        public Projectile(final float x, final float y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Pre-allocates native memory segments once to prevent garbage collection during the high-frequency game loop.
     */
    private void setupNativeBuffers() {
        fontMain = U8g2Factory.getFont("4x6_tf");
        scoreSegment = persistentArena.allocateFrom("S:00000");
        livesSegment = persistentArena.allocateFrom("P:3");
        gameOverSegment = persistentArena.allocateFrom("GAME OVER");
    }

    /**
     * Synchronizes Java game state (score, lives) with native memory segments used for u8g2 rendering.
     */
    private void updateUI() {
        final var sStr = String.format("%05d", score);
        MemorySegment.copy(sStr.getBytes(), 0, scoreSegment, ValueLayout.JAVA_BYTE, 2, 5);
        livesSegment.set(ValueLayout.JAVA_BYTE, 2, (byte) ('0' + Math.max(0, lives)));
    }

    /**
     * Initializes or resets the game level.
     *
     * @param fullReset If true, resets player score and lives to defaults.
     */
    public void initLevel(final boolean fullReset) {
        if (fullReset) {
            score = 0;
            lives = 3;
            setupNativeBuffers();
            gameOver = false;
        }
        mushrooms.clear();
        centipede.clear();
        spider = null;
        playerShot = null;

        final var w = getWidth();
        final var h = getHeight();
        playerX = w / 2.0f;
        playerY = h - 7.0f;

        for (int i = 0; i < 15; i++) {
            mushrooms.add(new Mushroom((random.nextInt((w - 12) / 6) * 6) + 6, (random.nextInt((h - 35) / 6) * 6) + 10));
        }
        for (int i = 0; i < 10; i++) {
            centipede.add(new Segment((w / 2.0f) - (i * 6), 8));
        }
    }

    /**
     * Updates game physics and AI based on delta-time.
     *
     * @param dt Elapsed seconds since the last update.
     */
    private void update(final float dt) {
        if (gameOver) {
            return;
        }
        if (centipede.isEmpty()) {
            initLevel(false);
            return;
        }

        updateCombat(dt);
        updateCentipede(dt);
        updateSpider(dt);

        // Apply LERP for smooth player movement at low FPS
        final var targetX = (spider != null) ? spider.x : (centipede.isEmpty() ? playerX : centipede.get(0).x);
        playerX += (targetX - playerX) * PLAYER_LERP_FACTOR * dt;

        playerX = Math.max(0, Math.min(getWidth() - 5, playerX));
        if (playerShot == null) {
            playerShot = new Projectile(playerX + 2, playerY);
        }

        checkCollisions();
    }

    /**
     * Handles projectile physics and hit detection against all enemies.
     *
     * @param dt Elapsed seconds.
     */
    private void updateCombat(final float dt) {
        if (playerShot != null) {
            playerShot.y -= SHOT_SPEED * dt;
            var hit = false;

            if (spider != null && Math.abs(playerShot.x - spider.x) < 5 && Math.abs(playerShot.y - spider.y) < 5) {
                spider = null;
                score += 600;
                hit = true;
            }
            if (!hit) {
                for (final var m : mushrooms) {
                    if (Math.abs(playerShot.x - m.x) < 4 && Math.abs(playerShot.y - m.y) < 4) {
                        m.health--;
                        score += 5;
                        hit = true;
                        break;
                    }
                }
                mushrooms.removeIf(m -> m.health <= 0);
            }
            if (!hit) {
                for (int i = 0; i < centipede.size(); i++) {
                    final var s = centipede.get(i);
                    if (Math.abs(playerShot.x - s.x) < 4 && Math.abs(playerShot.y - s.y) < 4) {
                        mushrooms.add(new Mushroom((int) s.x, (int) s.y));
                        centipede.remove(i);
                        score += 100;
                        hit = true;
                        break;
                    }
                }
            }
            if (playerShot.y < 6 || hit) {
                playerShot = null;
            }
        }
    }

    /**
     * Updates centipede segment positions and handles mushroom-induced turns.
     *
     * @param dt Elapsed seconds.
     */
    private void updateCentipede(final float dt) {
        for (final var s : centipede) {
            final var nextX = s.x + (s.dx * CENTIPEDE_SPEED * dt);
            var turn = (nextX <= 0 || nextX >= getWidth() - 5);
            if (!turn) {
                for (final var m : mushrooms) {
                    if (nextX < m.x + 5 && nextX + 5 > m.x && s.y < m.y + 5 && s.y + 5 > m.y) {
                        turn = true;
                        break;
                    }
                }
            }
            if (turn) {
                s.dx = -s.dx;
                s.y += 5;
                if (s.y > getHeight() - 5) {
                    s.y = 8;
                }
            } else {
                s.x = nextX;
            }
        }
    }

    /**
     * Logic for spider AI, including random vector changes and targeted hunting.
     *
     * @param dt Elapsed seconds.
     */
    private void updateSpider(final float dt) {
        if (spider == null) {
            if (random.nextInt(300) == 0) {
                final var sx = random.nextBoolean() ? -5 : getWidth() + 5;
                final var sy = getHeight() - 30;
                final var svx = (sx < 0) ? 1.0f : -1.0f;
                spider = new Spider(sx, sy, svx, -0.5f);
            }
        } else {
            spider.x += spider.vx * SPIDER_SPEED * dt;
            spider.y += spider.vy * SPIDER_SPEED * dt;
            spider.lifeTimer -= dt;
            spider.animTimer -= dt;

            if (spider.animTimer <= 0) {
                spider.animFrame = (spider.animFrame + 1) % 3;
                spider.animTimer = 0.1f;
            }

            if (spider.x < 0 || spider.x > getWidth() - 7) {
                spider.vx = -spider.vx;
                spider.x = Math.max(0, Math.min(getWidth() - 7, spider.x));
            }
            final var spiderCeiling = getHeight() - 40;
            if (spider.y < spiderCeiling || spider.y > getHeight() - 7) {
                spider.vy = -spider.vy;
                spider.y = Math.max(spiderCeiling, Math.min(getHeight() - 7, spider.y));
            }

            spider.timer -= dt;
            if (spider.timer <= 0) {
                if (random.nextBoolean()) {
                    spider.vx = (playerX > spider.x) ? 1.0f : -1.0f;
                } else {
                    spider.vx = (random.nextFloat() * 2) - 1;
                }
                spider.vy = (random.nextFloat() * 2) - 1;
                spider.timer = 0.4f + random.nextFloat();
            }

            if (spider.lifeTimer <= 0 && (spider.x <= 0 || spider.x >= getWidth() - 7)) {
                spider = null;
            }
        }
    }

    /**
     * Validates if lethal collisions have occurred between player and enemies.
     */
    private void checkCollisions() {
        if (spider != null && Math.abs(spider.x - playerX) < 5 && Math.abs(spider.y - playerY) < 5) {
            loseLife();
        }
        for (final var s : centipede) {
            if (Math.abs(s.x - playerX) < 4 && Math.abs(s.y - playerY) < 4) {
                loseLife();
                break;
            }
        }
    }

    /**
     * Handles life reduction and determines if the game should continue or end.
     */
    private void loseLife() {
        lives--;
        if (lives <= 0) {
            gameOver = true;
        } else {
            initLevel(false);
        }
    }

    /**
     * Renders all game entities to the u8g2 frame buffer.
     *
     * @param u8g2 Native pointer to the u8g2 instance.
     */
    private void render(final MemorySegment u8g2) {
        U8g2.u8g2_ClearBuffer(u8g2);
        U8g2.u8g2_SetFont(u8g2, fontMain);
        updateUI();
        U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) 6, scoreSegment);
        U8g2.u8g2_DrawStr(u8g2, (short) (getWidth() - 15), (short) 6, livesSegment);

        if (gameOver) {
            U8g2.u8g2_DrawStr(u8g2, (short) (getWidth() / 2 - 20), (short) (getHeight() / 2), gameOverSegment);
        } else {
            for (final var m : mushrooms) {
                drawBitmap(u8g2, m.x, m.y, MUSH_STAGES[m.health], 5);
            }
            for (final var s : centipede) {
                drawBitmap(u8g2, (int) s.x, (int) s.y, SEG_BITS, 5);
            }
            if (spider != null) {
                drawBitmap(u8g2, (int) spider.x, (int) spider.y, SPID_ANIM[spider.animFrame], 7);
            }
            drawBitmap(u8g2, (int) playerX, (int) playerY, SHIP_BITS, 5);
            if (playerShot != null) {
                U8g2.u8g2_DrawVLine(u8g2, (short) playerShot.x, (short) playerShot.y, (short) 2);
            }
        }
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Utility method to draw bitmask-based sprites.
     *
     * @param u8 Native u8g2 segment.
     * @param x Screen X.
     * @param y Screen Y.
     * @param bits Bitmask array.
     * @param size Dimensions of sprite.
     */
    private void drawBitmap(final MemorySegment u8, final int x, final int y, final int[] bits, final int size) {
        for (int i = 0; i < size; i++) {
            for (int b = 0; b < size; b++) {
                if (((bits[i] >> (size - 1 - b)) & 1) == 1) {
                    U8g2.u8g2_DrawPixel(u8, (short) (x + b), (short) (y + i));
                }
            }
        }
    }

    /**
     * Orchestrates the high-level loop, timing, and cleanup.
     *
     * * @param u8g2 Native pointer to the u8g2 instance.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        try (persistentArena) {
            initLevel(true);
            var lastTime = System.nanoTime();
            while (running) {
                final var currentTime = System.nanoTime();
                var deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                if (deltaTime > 0.1f) {
                    deltaTime = 0.1f;
                }

                update(deltaTime);
                render(u8g2);

                if (gameOver) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    running = false;
                }
            }
        }
    }

    /**
     * Main parsing, error handling and handling user requests for usage help or version help are done with one line of code.
     *
     * @param args Argument list.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Centipede()).execute(args));
    }
}
