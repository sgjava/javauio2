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
 * Centipede Color Logic Ported to High-Performance Monochrome U8g2 FFM API.
 * <p>
 * A high-fidelity port of the Atari classic game engine logic, optimized for monochrome displays using Java's Foreign Function &
 * Memory (FFM) API. This implementation incorporates multi-chain centipede segment splits, player area bounding, automatic wave
 * scaling, and dynamic spider gardening AI.
 * </p>
 * <p>
 * Projectile assets feature rigid frame-pass boundary isolation. When any lethal intersection occurs against any target type, the
 * projectile state is instantly neutralized, bailing out of the sequence execution to guarantee a clear visual update frame before
 * a new shot is prepared on the subsequent cycle.
 * </p>
 * <p>
 * This class safely intercepts SIGINT via a coordinated shutdown hook that toggles execution flags instead of executing native
 * device sequences on concurrent background threads, completely avoiding native SIGSEGV crashes during terminal shutdown.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Centipede", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Centipede - Multi-Chain U8g2 FFM Port")
public class Centipede extends Base {

    /**
     * Target Frames Per Second for the game loop.
     */
    @Option(names = {"-f", "--fps"}, description = "Target FPS", defaultValue = "60")
    private int targetFps;

    /**
     * Random number generator for game logic.
     */
    private final Random random = new Random();

    /**
     * Enum identifying structural game states.
     */
    private enum GameState {
        PLAYING, GAME_OVER
    }

    /**
     * Current operational state of the loop.
     */
    private GameState currentState = GameState.PLAYING;

    /**
     * Control flag for the main game loop. Marked volatile to guarantee cross-thread visibility during signal interception.
     */
    private volatile boolean running = true;

    /**
     * Object used to synchronize orderly shutdown between the main thread and shutdown hook.
     */
    private final Object shutdownLock = new Object();

    /**
     * Top boundary constraint of the designated player area.
     */
    private int playerAreaTop = 40;

    /**
     * Bottom boundary constraint for physics updates.
     */
    private int bottomRow = 58;

    /**
     * Base velocity tracking for segment movement.
     */
    private float baseCentipedeSpeed = 30.0f;

    /**
     * Interpolated floating-point position for player ship horizontal tracking.
     */
    private float playerX;

    /**
     * Fixed vertical coordinate for the player's ship asset.
     */
    private float playerY;

    /**
     * Horizontal velocity track for player transitions.
     */
    private float playerVX = 0;

    /**
     * Handle to an active projectile instance, null if available for reload.
     */
    private Projectile playerShot = null;

    /**
     * Pre-allocated list tracking structural mushroom targets.
     */
    private final List<Mushroom> mushrooms = new ArrayList<>();

    /**
     * Multi-chain tracking array grouping segmented centipede lines.
     */
    private final List<List<Segment>> centipedeChains = new ArrayList<>();

    /**
     * Handle to an active spider instance, null if clear.
     */
    private Spider spider = null;

    /**
     * Total calculated player score tracking.
     */
    private int score = 0;

    /**
     * Total user resource pool lives remaining.
     */
    private int lives = 5;

    /**
     * Current sequential operational wave scale.
     */
    private int wave = 1;

    /**
     * Penalty counter for segments dropping below threshold boundaries.
     */
    private int segmentsReachedBottom = 0;

    /**
     * Structural data representations for entities.
     */
    public static class Mushroom {

        public int x, y, health = 3;

        public Mushroom(final int x, final int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Tracking entity for unique segmented parts of a chain group.
     */
    public static class Segment {

        public float x, y;
        public int dx = 1;
        public boolean isHead;
        public boolean isRising = false;

        public Segment(final float x, final float y, final boolean isHead) {
            this.x = x;
            this.y = y;
            this.isHead = isHead;
        }
    }

    /**
     * Threat agent updating with independent vectors.
     */
    public static class Spider {

        public float x, y, vx, vy, changeTimer;

        public Spider(final float x, final float y, final float vx, final float vy) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.changeTimer = 0.4f;
        }
    }

    /**
     * High-speed projectile container tracking.
     */
    public static class Projectile {

        public float x, y;

        public Projectile(final float x, final float y) {
            this.x = x;
            this.y = y;
        }
    }

    // Binary bitmask translations of color sprites (6x6 and 8x8 metrics)
    private static final int[] MUSH_FULL = {0x1E, 0x33, 0x3F, 0x3F, 0x0C, 0x0C};
    private static final int[] MUSH_DMG1 = {0x16, 0x33, 0x2F, 0x3B, 0x0C, 0x0C};
    private static final int[] MUSH_DMG2 = {0x06, 0x23, 0x0E, 0x33, 0x04, 0x0C};
    private static final int[] HEAD_BITS = {0x1E, 0x2D, 0x3F, 0x3F, 0x1E, 0x2D};
    private static final int[] BODY_BITS = {0x1E, 0x3F, 0x3F, 0x3F, 0x1E, 0x00};
    private static final int[] SHIP_BITS = {0x0C, 0x1E, 0x33, 0x3F, 0x3F, 0x2D};
    private static final int[] SPID_BITS = {0x81, 0x42, 0xFF, 0x7E, 0x7E, 0xFF, 0x42, 0x81};

    /**
     * Managed FFM native arena configuration container.
     */
    private final Arena persistentArena = Arena.ofShared();
    private MemorySegment fontMain;
    private MemorySegment uiStatusSegment;
    private MemorySegment gameOverSegment;

    /**
     * Allocates standard flat native spaces once to minimize run loop allocations.
     */
    private void setupNativeBuffers() {
        fontMain = U8g2Factory.getFont("4x6_tf");
        uiStatusSegment = persistentArena.allocateFrom("00000 L:5 W:1");
        gameOverSegment = persistentArena.allocateFrom("GAME OVER");
    }

    /**
     * Formats state strings natively without garbage collection tracking overhead.
     */
    private void updateUI() {
        final var uiStr = String.format("%05d L:%01d W:%d", score, Math.max(0, lives), wave);
        final var bytes = uiStr.getBytes();
        MemorySegment.copy(bytes, 0, uiStatusSegment, ValueLayout.JAVA_BYTE, 0, Math.min(bytes.length, 13));
    }

    /**
     * Moves all centipede chain clusters, adjusting for bounding box edges and mushroom impacts.
     *
     * @param dt Frame delta tracking scale.
     */
    private void updateCentipedes(final float dt) {
        for (int i = 0; i < centipedeChains.size(); i++) {
            final var chain = centipedeChains.get(i);
            for (int j = 0; j < chain.size(); j++) {
                final var s = chain.get(j);
                final var nextX = s.x + (s.dx * baseCentipedeSpeed * dt);
                var turn = (nextX <= 0 && s.dx < 0) || (nextX >= getWidth() - 6 && s.dx > 0);

                if (!turn) {
                    for (int k = 0; k < mushrooms.size(); k++) {
                        final var m = mushrooms.get(k);
                        if (Math.abs(nextX - m.x) < 5 && Math.abs(s.y - m.y) < 5) {
                            turn = true;
                            break;
                        }
                    }
                }

                if (turn) {
                    s.dx = -s.dx;
                    if (s.isRising) {
                        s.y -= 6;
                        if (s.y <= playerAreaTop) {
                            s.isRising = false;
                        }
                    } else {
                        s.y += 6;
                        if (s.y >= bottomRow) {
                            s.isRising = true;
                            segmentsReachedBottom++;
                        }
                    }
                } else {
                    s.x = nextX;
                }
            }
        }
    }

    /**
     * Drops single detached high-speed heads directly within the active action bounds.
     */
    private void spawnExtraHead() {
        final var headChain = new ArrayList<Segment>();
        final var s = new Segment(random.nextInt(getWidth() - 6), bottomRow, true);
        s.isRising = true;
        headChain.add(s);
        centipedeChains.add(headChain);
    }

    /**
     * Tracks weapon intersections and coordinates damage.
     *
     * @param dt Frame timing coefficient variable.
     */
    private void updateCombat(final float dt) {
        if (playerShot == null) {
            return;
        }
        playerShot.y -= 280.0f * dt;

        if (playerShot.y < 0) {
            playerShot = null;
            return;
        }

        if (spider != null && Math.abs(playerShot.x - (spider.x + 4)) < 6 && Math.abs(playerShot.y - (spider.y + 4)) < 6) {
            spider = null;
            score += 600;
            playerShot = null;
            return;
        }

        for (int i = 0; i < mushrooms.size(); i++) {
            final var m = mushrooms.get(i);
            if (playerShot.x >= m.x - 1 && playerShot.x <= m.x + 6 && playerShot.y >= m.y && playerShot.y <= m.y + 6) {
                m.health--;
                score += 5;
                if (m.health < 0) {
                    mushrooms.remove(i);
                }
                playerShot = null;
                return;
            }
        }

        final var newChains = new ArrayList<List<Segment>>();
        var chainHitOccurred = false;

        for (int i = 0; i < centipedeChains.size(); i++) {
            final var chain = centipedeChains.get(i);
            for (int j = 0; j < chain.size(); j++) {
                final var s = chain.get(j);
                if (Math.abs(playerShot.x - (s.x + 3)) < 6 && Math.abs(playerShot.y - (s.y + 3)) < 6) {
                    if (random.nextInt(3) == 0) {
                        mushrooms.add(new Mushroom((int) s.x, (int) s.y));
                    }
                    score += 100;
                    chainHitOccurred = true;

                    if (j + 1 < chain.size()) {
                        final var split = new ArrayList<>(chain.subList(j + 1, chain.size()));
                        split.get(0).isHead = true;
                        newChains.add(split);
                    }
                    chain.subList(j, chain.size()).clear();
                    break;
                }
            }

            if (chain.isEmpty()) {
                centipedeChains.remove(i);
                i--;
            }
            if (chainHitOccurred) {
                break;
            }
        }

        if (!newChains.isEmpty()) {
            centipedeChains.addAll(newChains);
        }

        if (chainHitOccurred) {
            playerShot = null;
        }
    }

    /**
     * Smoothly sweeps the movement position vector toward calculated logic points.
     *
     * @param targetX Destination tracking focus line.
     * @param dt Delta scale.
     */
    private void movePlayer(final float targetX, final float dt) {
        final var diff = targetX - playerX;
        playerVX = (diff > 0) ? 80.0f : -80.0f;
        if (Math.abs(diff) < 2) {
            playerVX = 0;
        }
        playerX += playerVX * dt;
        playerX = Math.max(2, Math.min(getWidth() - 8, playerX));
        playerY = getHeight() - 8;
    }

    /**
     * Initializes a 12-segment line block centered at game initiation.
     */
    private void spawnCentipede() {
        final var startChain = new ArrayList<Segment>();
        final var startX = getWidth() / 2.0f;
        for (int i = 0; i < 12; i++) {
            startChain.add(new Segment(startX - (i * 6), 10, i == 0));
        }
        centipedeChains.add(startChain);
    }

    /**
     * Refreshes automated threat vectors for erratic side actions.
     *
     * @param dt Scale component tracking loop execution times.
     */
    private void updateSpider(final float dt) {
        if (spider == null) {
            if (random.nextInt(180) == 0) {
                final var sx = random.nextBoolean() ? -10 : getWidth() + 10;
                spider = new Spider(sx, (float) random.nextInt(20) + playerAreaTop - 8, (sx < 0 ? 1 : -1), 1);
            }
            return;
        }
        spider.changeTimer -= dt;
        if (spider.changeTimer <= 0) {
            spider.vy = random.nextBoolean() ? 1.0f : -1.0f;
            spider.changeTimer = 0.5f;
        }
        spider.x += spider.vx * 40.0f * dt;
        spider.y += spider.vy * 30.0f * dt;
        if (spider.y < playerAreaTop - 8) {
            spider.vy = 1;
        }
        if (spider.y > getHeight() - 8) {
            spider.vy = -1;
        }

        for (int i = 0; i < mushrooms.size(); i++) {
            final var m = mushrooms.get(i);
            if (Math.abs(spider.x - m.x) < 7 && Math.abs(spider.y - m.y) < 7) {
                mushrooms.remove(i);
                i--;
            }
        }

        if (spider.x < -40 || spider.x > getWidth() + 40) {
            spider = null;
        }
    }

    /**
     * Decrements resources and transitions state flags accordingly.
     *
     * @param u8g2 Pointer handle map indicating targeted hardware tracking lines.
     */
    private void loseLife(final MemorySegment u8g2) {
        lives--;
        if (lives <= 0) {
            currentState = GameState.GAME_OVER;
        } else {
            initLevel(false, u8g2);
        }
    }

    /**
     * Sets structural bounds dynamically based on display dimensions, clearing target components and applying positions.
     *
     * @param fullReset Enforces reset to score variables if true.
     * @param u8g2 Pointer handle map indicating targeted hardware tracking lines.
     */
    public void initLevel(final boolean fullReset, final MemorySegment u8g2) {
        // Dynamically compute layout bounds from screen height/width using Base class accessors
        final int displayHeight = getHeight();
        playerAreaTop = displayHeight - (displayHeight / 3);
        bottomRow = displayHeight - 10;

        if (fullReset) {
            score = 0;
            lives = 5;
            wave = 1;
            baseCentipedeSpeed = 30.0f;
            currentState = GameState.PLAYING;
            running = true;
            setupNativeBuffers();
            mushrooms.clear();
        }
        centipedeChains.clear();
        spawnCentipede();
        spider = null;
        playerShot = null;
        segmentsReachedBottom = 0;
        playerX = getWidth() / 2.0f;
        playerY = displayHeight - 8.0f;

        if (mushrooms.isEmpty()) {
            final int cols = getWidth() / 8;
            final int rows = (displayHeight / 2) / 6;
            for (int i = 0; i < 18; i++) {
                mushrooms.add(new Mushroom((random.nextInt(cols) * 6) + 6, (random.nextInt(rows) * 6) + 12));
            }
        }
    }

    /**
     * Translates bitmask logic data directly out to the hardware frame buffer segment.
     *
     * @param u8g2 Pointer handle map indicating targeted hardware tracking lines.
     */
    private void render(final MemorySegment u8g2) {
        U8g2.u8g2_ClearBuffer(u8g2);
        U8g2.u8g2_SetFont(u8g2, fontMain);

        if (currentState == GameState.GAME_OVER) {
            U8g2.u8g2_DrawStr(u8g2, (short) (getWidth() / 2 - 20), (short) (getHeight() / 2), gameOverSegment);
        } else {
            for (int i = 0; i < mushrooms.size(); i++) {
                final var m = mushrooms.get(i);
                final var mask = (m.health >= 3) ? MUSH_FULL : (m.health == 2 ? MUSH_DMG1 : MUSH_DMG2);
                drawBitmap(u8g2, m.x, m.y, mask, 6);
            }

            for (int i = 0; i < centipedeChains.size(); i++) {
                final var chain = centipedeChains.get(i);
                for (int j = 0; j < chain.size(); j++) {
                    final var s = chain.get(j);
                    drawBitmap(u8g2, (int) s.x, (int) s.y, s.isHead ? HEAD_BITS : BODY_BITS, 6);
                }
            }

            if (spider != null) {
                drawBitmap(u8g2, (int) spider.x, (int) spider.y, SPID_BITS, 8);
            }

            drawBitmap(u8g2, (int) playerX, (int) playerY, SHIP_BITS, 6);

            if (playerShot != null) {
                U8g2.u8g2_DrawVLine(u8g2, (short) playerShot.x, (short) playerShot.y, (short) 3);
            }

            updateUI();
            U8g2.u8g2_DrawStr(u8g2, (short) 2, (short) 6, uiStatusSegment);
        }
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Converts low-level matrix integers directly to individual buffer bits.
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
     * Evaluates geometric overlaps to enforce mortality mechanics.
     *
     * @param u8g2 Pointer handle map indicating targeted hardware tracking lines.
     */
    private void checkCollisions(final MemorySegment u8g2) {
        if (spider != null && Math.abs(spider.x - playerX) < 5 && Math.abs(spider.y - playerY) < 5) {
            loseLife(u8g2);
        }
        for (int i = 0; i < centipedeChains.size(); i++) {
            final var chain = centipedeChains.get(i);
            for (int j = 0; j < chain.size(); j++) {
                final var s = chain.get(j);
                if (Math.abs(s.x - playerX) < 4 && Math.abs(s.y - playerY) < 4) {
                    loseLife(u8g2);
                    return;
                }
            }
        }
    }

    /**
     * Coordinates system physical updates.
     *
     * @param dt Segment frame time delta calculations.
     * @param u8g2 Pointer handle map indicating targeted hardware tracking lines.
     */
    private void update(final float dt, final MemorySegment u8g2) {
        if (currentState == GameState.GAME_OVER) {
            return;
        }

        if (centipedeChains.isEmpty()) {
            wave++;
            baseCentipedeSpeed += 4.0f;
            spawnCentipede();
            for (int i = 0; i < segmentsReachedBottom; i++) {
                spawnExtraHead();
            }
            segmentsReachedBottom = 0;
        }

        if (playerShot == null) {
            playerShot = new Projectile(playerX + 2, playerY - 2);
        }

        updateCombat(dt);
        updateCentipedes(dt);
        updateSpider(dt);

        var targetX = getWidth() / 2.0f;
        if (spider != null) {
            targetX = spider.x + 4;
        } else if (!centipedeChains.isEmpty()) {
            Segment lowest = null;
            for (int i = 0; i < centipedeChains.size(); i++) {
                final var chain = centipedeChains.get(i);
                for (int j = 0; j < chain.size(); j++) {
                    final var s = chain.get(j);
                    if (lowest == null || s.y > lowest.y) {
                        lowest = s;
                    }
                }
            }
            if (lowest != null) {
                targetX = lowest.x;
            }
        }

        movePlayer(targetX, dt);
        checkCollisions(u8g2);
    }

    /**
     * Oversees the frame pacing block.
     *
     * @param u8g2 Main display surface memory pointer segment.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        final var hook = new Thread(() -> {
            running = false;
            synchronized (shutdownLock) {
                log.debug("Shutdown signal acknowledged by hook thread.");
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);

        try (persistentArena) {
            initLevel(true, u8g2);
            var lastTime = System.nanoTime();

            while (running && !Thread.currentThread().isInterrupted()) {
                final var currentTime = System.nanoTime();
                var deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;

                if (deltaTime > 0.05f) {
                    deltaTime = 0.05f;
                }

                update(deltaTime, u8g2);
                render(u8g2);

                if (currentState == GameState.GAME_OVER) {
                    try {
                        Thread.sleep(3000);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    running = false;
                    break;
                }

                try {
                    Thread.sleep(1000 / targetFps);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            synchronized (shutdownLock) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (final IllegalStateException e) {
                    // Swallow exception if JVM is already shutting down anyway
                }
                log.debug("Main loop terminated sequentially. Handing off to base hardware cleanup.");
            }
        }
    }

    /**
     * Entrypoint configuration.
     *
     * @param args System line runtime variables.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Centipede()).execute(args));
    }
}
