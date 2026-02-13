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
 * Space Invaders demo with AI-driven player and resolution-aware scaling.
 * <p>
 * Optimized for Foreign Function & Memory (FFM) API:
 * - Uses {@link U8g2Factory} for dynamic font lookup to avoid jextract layout errors.
 * - Uses pre-allocated {@link MemorySegment} buffers for UI strings to ensure zero-allocation during the render loop.
 * - Follows strict final/var standards.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SpaceInvaders", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Space Invaders - Dynamic Resolution Demo")
public class SpaceInvaders extends Base {

    /**
     * Target frames per second.
     */
    @Option(names = {"-f", "--fps"}, description = "Frames per second", defaultValue = "60")
    private int fps;

    /**
     * Random generator for AI and effects.
     */
    private final Random random = new Random();

    /**
     * Game loop control flag.
     */
    private boolean running = true;

    /**
     * Internal game states.
     */
    public enum State {
        PLAYING, EXPLODING, GAME_OVER
    }

    private State gameState = State.PLAYING;
    private int score = 0;
    private int lives = 3;
    private int playerExplosionTimer = 0;
    private boolean hitLock = false;
    private int playerXScaled;
    private static final int PLAYER_SPEED_SCALED = 200;
    private int aiDecisionTimer = 0;
    private int aiTargetX = 0;

    private Projectile playerShot = null;
    private final List<Projectile> alienMissiles = new ArrayList<>();
    private final List<Invader> invaders = new ArrayList<>();
    private final List<Explosion> explosions = new ArrayList<>();
    private int rackX;
    private int rackY;
    private int rackDir = 2;
    private int moveTimer = 0;
    private final int[][] bunkers = new int[3][3];
    private int saucerX = -20;
    private int saucerTimer = 0;
    private boolean saucerActive = false;

    // FFM Shared Resources
    private final Arena persistentArena = Arena.ofShared();
    private MemorySegment fontMain;
    private MemorySegment fontGameOver;
    private MemorySegment scoreSegment;
    private MemorySegment livesSegment;
    private MemorySegment gameOverSegment;

    /**
     * Represents a bullet or missile.
     * * @param x X coordinate.
     * @param y Y coordinate.
     */
    public static record Projectile(int x, int y) {}

    /**
     * Represents an alien invader.
     */
    public static class Invader {
        public int x, y, type;
        public boolean active;

        public Invader(final int x, final int y, final int type, final boolean active) {
            this.x = x;
            this.y = y;
            this.type = type;
            this.active = active;
        }
    }

    /**
     * Represents a visual explosion effect.
     */
    public static class Explosion {
        public int x, y, timer;

        public Explosion(final int x, final int y) {
            this.x = x;
            this.y = y;
            this.timer = 6;
        }
    }

    private static final int[] SAUCER_BITS = {0x0F0, 0x3FC, 0x7FE, 0xAA8, 0x444};
    private static final int[] EXPLOSION_BITS = {0x24, 0x50, 0x18, 0x50, 0x24};

    /**
     * Pre-allocates native segments for fonts and UI labels to avoid allocation during render.
     */
    private void setupNativeBuffers() {
        fontMain = U8g2Factory.getFont("5x7_tf");
        fontGameOver = U8g2Factory.getFont("6x12_tf");

        // "S:00000" + null terminator
        scoreSegment = persistentArena.allocateFrom("S:00000");
        // "P:0" + null terminator
        livesSegment = persistentArena.allocateFrom("P:0");
        // Constant label
        gameOverSegment = persistentArena.allocateFrom("GAME OVER");
    }

    /**
     * Updates the score segment by copying bytes directly into native memory.
     * * @param value The current score.
     */
    private void updateScoreSegment(final int value) {
        final var s = String.format("%05d", value);
        final var bytes = s.getBytes();
        // Copy bytes to segment after "S:" offset (index 2)
        MemorySegment.copy(bytes, 0, scoreSegment, ValueLayout.JAVA_BYTE, 2, bytes.length);
    }

    /**
     * Updates the lives segment by setting the byte at the specific offset.
     * * @param value The current lives.
     */
    private void updateLivesSegment(final int value) {
        final var b = (byte) ('0' + (value % 10));
        livesSegment.set(ValueLayout.JAVA_BYTE, 2, b);
    }

    /**
     * Initializes level data and pre-allocates buffers if full reset.
     * * @param fullReset True to reset score/lives and reload fonts.
     */
    public void initLevel(final boolean fullReset) {
        if (fullReset) {
            lives = 3;
            score = 0;
            setupNativeBuffers();
        }
        final var w = getWidth();
        final var h = getHeight();

        playerXScaled = (w / 2) * 100;
        aiTargetX = w / 2;
        playerShot = null;
        alienMissiles.clear();
        invaders.clear();
        explosions.clear();
        hitLock = false;
        saucerActive = false;
        saucerTimer = 0;

        final var cols = Math.max(5, (w * 7 / 10) / 9);
        final var rows = Math.max(1, (h * 4 / 10) / 7);
        rackX = (w - (cols * 9)) / 2;
        rackY = h / 6;
        for (var row = 0; row < rows; row++) {
            for (var col = 0; col < cols; col++) {
                final var type = (row == 0) ? 0 : (row < rows / 2 ? 1 : 2);
                invaders.add(new Invader(col * 9, row * 7, type, true));
            }
        }
        for (var i = 0; i < 3; i++) {
            bunkers[i][0] = 0x3E;
            bunkers[i][1] = 0x7F;
            bunkers[i][2] = 0x63;
        }
    }

    private void registerHit(final boolean isLanding) {
        if (!hitLock) {
            hitLock = true;
            lives = isLanding ? 0 : lives - 1;
            playerExplosionTimer = 90;
            gameState = State.EXPLODING;
            playerShot = null;
            alienMissiles.clear();
        }
    }

    /**
     * Updates game logic based on current state.
     */
    public void update() {
        if (gameState == State.EXPLODING) {
            if (--playerExplosionTimer <= 0) {
                if (lives > 0) {
                    initLevel(false);
                    gameState = State.PLAYING;
                } else {
                    gameState = State.GAME_OVER;
                }
            }
            return;
        }
        if (gameState == State.PLAYING) {
            updateAI();
            updateSaucer();
            updateInvaders();
            updateCombat();
            explosions.removeIf(e -> --e.timer <= 0);
        }
    }

    private void updateSaucer() {
        if (!saucerActive) {
            if (++saucerTimer > (400 + random.nextInt(800))) {
                saucerActive = true;
                saucerX = -20;
            }
        } else {
            saucerX += 1;
            if (saucerX > getWidth()) {
                saucerActive = false;
                saucerTimer = 0;
            }
        }
    }

    private void updateAI() {
        final var h = getHeight();
        if (--aiDecisionTimer <= 0) {
            aiDecisionTimer = 8;
            final var currentX = playerXScaled / 100;
            var newTargetX = currentX;
            Projectile threat = null;
            for (final var m : alienMissiles) {
                if (Math.abs(m.x - currentX) < 12 && m.y > h - 35) {
                    if (threat == null || m.y > threat.y) {
                        threat = m;
                    }
                }
            }
            if (threat != null) {
                final var dodgeDist = 18 + random.nextInt(8);
                newTargetX = (threat.x < currentX) ? currentX + dodgeDist : currentX - dodgeDist;
            } else {
                Invader target = null;
                for (final var inv : invaders) {
                    if (inv.active && (target == null || inv.y > target.y)) {
                        target = inv;
                    }
                }
                if (target != null) {
                    newTargetX = target.x + rackX + 3 + (random.nextInt(5) - 2);
                }
            }
            aiTargetX = newTargetX;
        }
        final var targetXScaled = aiTargetX * 100;
        if (playerXScaled < targetXScaled) {
            playerXScaled += Math.min(PLAYER_SPEED_SCALED, targetXScaled - playerXScaled);
        } else if (playerXScaled > targetXScaled) {
            playerXScaled -= Math.min(PLAYER_SPEED_SCALED, playerXScaled - targetXScaled);
        }
        playerXScaled = Math.max(800, Math.min((getWidth() - 8) * 100, playerXScaled));
    }

    private void updateInvaders() {
        var activeCount = 0;
        var minX = 1000;
        var maxX = -1000;
        final var w = getWidth();
        final var h = getHeight();

        for (final var inv : invaders) {
            if (inv.active) {
                activeCount++;
                final var curX = inv.x + rackX;
                if (curX < minX) minX = curX;
                if (curX + 7 > maxX) maxX = curX + 7;
                if (inv.y + rackY + 5 >= h - 12) {
                    registerHit(true);
                    return;
                }
            }
        }
        if (activeCount == 0) {
            initLevel(false);
            return;
        }
        if (++moveTimer >= Math.max(2, activeCount / 6)) {
            if (rackDir > 0 && maxX >= w - 2) {
                rackDir = -2;
                rackY += 3;
            } else if (rackDir < 0 && minX <= 2) {
                rackDir = 2;
                rackY += 3;
            } else {
                rackX += rackDir;
            }
            moveTimer = 0;
        }
    }

    private void updateCombat() {
        final var h = getHeight();
        final var px = playerXScaled / 100;
        final var saucerY = h / 6;
        if (playerShot == null) {
            playerShot = new Projectile(px, h - 10);
        } else {
            final var nx = playerShot.x;
            final var ny = playerShot.y - 4;

            if (saucerActive && nx >= saucerX && nx <= saucerX + 11 && ny >= saucerY && ny <= saucerY + 6) {
                score += 150;
                saucerActive = false;
                playerShot = null;
                explosions.add(new Explosion(saucerX + 2, saucerY));
            } else if (ny < 10 || checkBunkerCollision(nx, ny)) {
                playerShot = null;
            } else {
                playerShot = new Projectile(nx, ny);
                for (final var inv : invaders) {
                    if (inv.active && nx >= inv.x + rackX && nx <= inv.x + rackX + 7 && ny >= inv.y + rackY && ny <= inv.y + rackY + 5) {
                        inv.active = false;
                        score += 20;
                        playerShot = null;
                        explosions.add(new Explosion(inv.x + rackX, inv.y + rackY));
                        break;
                    }
                }
            }
        }

        if (random.nextInt(45) == 0 && alienMissiles.size() < 3) {
            final var activeOnes = invaders.stream().filter(i -> i.active).toList();
            if (!activeOnes.isEmpty()) {
                final var s = activeOnes.get(random.nextInt(activeOnes.size()));
                alienMissiles.add(new Projectile(s.x + rackX + 3, s.y + rackY + 6));
            }
        }
        for (int i = 0; i < alienMissiles.size(); i++) {
            final var m = alienMissiles.get(i);
            final var ny = m.y + 2;
            if (ny > h || checkBunkerCollision(m.x, ny)) {
                alienMissiles.set(i, null);
            } else if (ny > h - 10 && Math.abs(m.x - px) < 5) {
                registerHit(false);
                return;
            } else {
                alienMissiles.set(i, new Projectile(m.x, ny));
            }
        }
        alienMissiles.removeIf(m -> m == null);
    }

    private boolean checkBunkerCollision(final int x, final int y) {
        final var h = getHeight();
        final var w = getWidth();
        final var bunkerYStart = h - 24;
        if (y < bunkerYStart || y > bunkerYStart + 5) {
            return false;
        }
        final var spacing = w / 3;
        for (var i = 0; i < 3; i++) {
            final var bx = (spacing / 2) + (i * spacing) - 4;
            if (x >= bx && x < bx + 7) {
                final var row = (y - bunkerYStart) / 2;
                if (row >= 0 && row < 3 && ((bunkers[i][row] >> (6 - (x - bx))) & 1) == 1) {
                    bunkers[i][row] &= ~(1 << (6 - (x - bx)));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Main game loop.
     * * @param u8g2 Native u8g2_t pointer.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        initLevel(true);
        final long frameDelay = 1000L / fps;
        while (running) {
            update();
            render(u8g2);
            try {
                Thread.sleep(frameDelay);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (gameState == State.GAME_OVER) {
                try {
                    Thread.sleep(3000);
                } catch (final InterruptedException ignored) {
                }
                running = false;
            }
        }
        persistentArena.close();
    }

    /**
     * Renders UI components and updates native string buffers.
     * * @param u8g2 Native u8g2_t pointer.
     */
    private void render(final MemorySegment u8g2) {
        U8g2.u8g2_ClearBuffer(u8g2);
        final var w = getWidth();
        final var h = getHeight();

        U8g2.u8g2_SetFont(u8g2, fontMain);

        // Update pre-allocated segments with zero heap allocation
        updateScoreSegment(score);
        updateLivesSegment(lives);

        U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) 7, scoreSegment);
        U8g2.u8g2_DrawStr(u8g2, (short) (w - 25), (short) 7, livesSegment);

        final var px = playerXScaled / 100;
        if (gameState == State.EXPLODING) {
            for (var i = 0; i < 20; i++) {
                U8g2.u8g2_DrawPixel(u8g2, (short) (px + random.nextInt(15) - 7), (short) (h - 5 + random.nextInt(10) - 5));
            }
        } else if (gameState != State.GAME_OVER) {
            U8g2.u8g2_DrawBox(u8g2, (short) (px - 4), (short) (h - 5), (short) 9, (short) 4);
            U8g2.u8g2_DrawBox(u8g2, (short) (px - 1), (short) (h - 7), (short) 3, (short) 2);
        }

        renderWorld(u8g2);

        if (gameState == State.GAME_OVER) {
            U8g2.u8g2_SetFont(u8g2, fontGameOver);
            final var tw = (int) U8g2.u8g2_GetStrWidth(u8g2, gameOverSegment);
            final var tx = (w - tw) / 2;
            final var ty = (h / 2) + 4;
            U8g2.u8g2_SetDrawColor(u8g2, (byte) 0);
            U8g2.u8g2_DrawBox(u8g2, (short) (tx - 2), (short) (ty - 10), (short) (tw + 4), (short) 14);
            U8g2.u8g2_SetDrawColor(u8g2, (byte) 1);
            U8g2.u8g2_DrawStr(u8g2, (short) tx, (short) ty, gameOverSegment);
        }
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Renders game entities.
     * * @param u8g2 Native u8g2_t pointer.
     */
    private void renderWorld(final MemorySegment u8g2) {
        final var h = getHeight();
        final var w = getWidth();
        if (saucerActive) {
            final var sy = h / 6;
            for (var i = 0; i < 5; i++) {
                for (var b = 0; b < 12; b++) {
                    if (((SAUCER_BITS[i] >> (11 - b)) & 1) == 1) {
                        U8g2.u8g2_DrawPixel(u8g2, (short) (saucerX + b), (short) (sy + i));
                    }
                }
            }
        }
        final var bunkerYStart = h - 24;
        final var spacing = w / 3;
        for (var i = 0; i < 3; i++) {
            final var bx = (spacing / 2) + (i * spacing) - 4;
            for (var r = 0; r < 3; r++) {
                for (var c = 0; c < 7; c++) {
                    if (((bunkers[i][r] >> (6 - c)) & 1) == 1) {
                        U8g2.u8g2_DrawBox(u8g2, (short) (bx + c), (short) (bunkerYStart + (r * 2)), (short) 1, (short) 2);
                    }
                }
            }
        }
        for (final var inv : invaders) {
            if (inv.active) {
                final int[] bts = (inv.type == 0) ? new int[]{0x10, 0x38, 0x7C, 0x28} : (inv.type == 1)
                        ? new int[]{0x44, 0x38, 0x7C, 0x10} : new int[]{0x38, 0x7C, 0x7C, 0x44};
                for (var i = 0; i < 4; i++) {
                    for (var b = 0; b < 7; b++) {
                        if (((bts[i] >> (6 - b)) & 1) == 1) {
                            U8g2.u8g2_DrawPixel(u8g2, (short) (inv.x + rackX + b), (short) (inv.y + rackY + i));
                        }
                    }
                }
            }
        }
        for (final var exp : explosions) {
            for (var i = 0; i < 5; i++) {
                for (var b = 0; b < 8; b++) {
                    if (((EXPLOSION_BITS[i] >> (7 - b)) & 1) == 1) {
                        U8g2.u8g2_DrawPixel(u8g2, (short) (exp.x + b), (short) (exp.y + i));
                    }
                }
            }
        }
        if (playerShot != null) {
            U8g2.u8g2_DrawVLine(u8g2, (short) playerShot.x, (short) playerShot.y, (short) 3);
        }
        for (final var m : alienMissiles) {
            U8g2.u8g2_DrawVLine(u8g2, (short) m.x, (short) m.y, (short) 3);
        }
    }

    /**
     * Main entry point with automatic type conversion.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SpaceInvaders()).execute(args));
    }    
}
