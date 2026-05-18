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
 * Optimized for Foreign Function & Memory (FFM) API: - Uses {@link U8g2Factory} for dynamic font lookup to avoid jextract layout
 * errors. - Uses pre-allocated {@link MemorySegment} buffers for UI strings to ensure zero-allocation during the render loop. -
 * Coordinated via single-threaded synchronization to guarantee no concurrent access during JVM shutdown.
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
     * Game loop control flag. Marked volatile for proper cross-thread synchronization.
     */
    private volatile boolean running = true;

    /**
     * Internal game states.
     */
    public enum State {
        /**
         * Active playing state.
         */
        PLAYING,
        /**
         * Player hit explosion sequence.
         */
        EXPLODING,
        /**
         * Game over state.
         */
        GAME_OVER
    }

    /**
     * Game lifecycle tracker state.
     */
    private State gameState = State.PLAYING;

    /**
     * Current score counter.
     */
    private int score = 0;

    /**
     * Remaining player inventory count.
     */
    private int lives = 3;

    /**
     * Frame step lifetime tracker for player explosion animation.
     */
    private int playerExplosionTimer = 0;

    /**
     * Debounce switch to prevent multi-frame hit overlap registry.
     */
    private boolean hitLock = false;

    /**
     * Scaled X horizontal axis tracking coordinate (multiplied by 100 for sub-pixel precision).
     */
    private int playerXScaled;

    /**
     * Constant step scalar velocity tracking for internal layout positioning.
     */
    private static final int PLAYER_SPEED_SCALED = 200;

    /**
     * Decision matrix interval count threshold for automated driver.
     */
    private int aiDecisionTimer = 0;

    /**
     * target alignment placement index for moving automated player coordinates.
     */
    private int aiTargetX = 0;

    /**
     * Active player projectile tracking handle reference.
     */
    private Projectile playerShot = null;

    /**
     * Pre-allocated group tracking active enemy missiles.
     */
    private final List<Projectile> alienMissiles = new ArrayList<>();

    /**
     * Grid mapping setup for all surviving active invaders.
     */
    private final List<Invader> invaders = new ArrayList<>();

    /**
     * Visual splash explosion particle lists.
     */
    private final List<Explosion> explosions = new ArrayList<>();

    /**
     * Absolute screen layout root base horizontal X placement tracking for the total alien fleet block.
     */
    private int rackX;

    /**
     * Absolute screen layout root base vertical Y placement tracking for the total alien fleet block.
     */
    private int rackY;

    /**
     * Vector scalar shift value indicating block animation movement velocity.
     */
    private int rackDir = 2;

    /**
     * Internal counter tracking speed ratios relative to alien counts.
     */
    private int moveTimer = 0;

    /**
     * Memory matrix map buffer representing individual damage structural configurations of base shields.
     */
    private final int[][] bunkers = new int[3][3];

    /**
     * Horizontal position tracker for mystery ship asset.
     */
    private int saucerX = -20;

    /**
     * Timing threshold calculation framework tracking randomly selected appearance conditions.
     */
    private int saucerTimer = 0;

    /**
     * Active display control logic flag indicating target configuration viability.
     */
    private boolean saucerActive = false;

    /**
     * Shared persistent lifecycle allocation arena for FFM pointers.
     */
    private final Arena persistentArena = Arena.ofShared();

    /**
     * Cached font data memory reference block mapping regular dashboard readouts.
     */
    private MemorySegment fontMain;

    /**
     * Cached font data memory reference block mapping large center overlay layouts.
     */
    private MemorySegment fontGameOver;

    /**
     * Pre-allocated native string memory pointer block assigned for updating score tracking data without allocations.
     */
    private MemorySegment scoreSegment;

    /**
     * Pre-allocated native string memory pointer block assigned for updating health monitoring variables.
     */
    private MemorySegment livesSegment;

    /**
     * Static string memory layout placeholder tracking final screen text displays.
     */
    private MemorySegment gameOverSegment;

    /**
     * Reusable flat byte array buffer backing score string formatting transforms to eliminate heap operations.
     */
    private byte[] scoreBuffer;

    /**
     * Represents a bullet or missile entity.
     *
     * @param x Horizontal pixel position coordinate.
     * @param y Vertical pixel position coordinate.
     */
    public static record Projectile(int x, int y) {

    }

    /**
     * Represents an alien invader.
     */
    public static class Invader {

        /**
         * Horizontal coordinate placement relative to rack.
         */
        public int x;
        /**
         * Vertical coordinate placement relative to rack.
         */
        public int y;
        /**
         * Invader variant type tracking point scales.
         */
        public int type;
        /**
         * Flag tracking active presence on screen.
         */
        public boolean active;

        /**
         * Full arguments constructor.
         *
         * @param x Horizontal pixel position offset.
         * @param y Vertical pixel position offset.
         * @param type Row item variant type classification.
         * @param active Entity activation switch.
         */
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

        /**
         * Horizontal pixel coordinate of explosion.
         */
        public int x;
        /**
         * Vertical pixel coordinate of explosion.
         */
        public int y;
        /**
         * Display frame lifetime step count tracker.
         */
        public int timer;

        /**
         * Frame-bound constructor coordinates.
         *
         * @param x Horizontal start alignment.
         * @param y Vertical start alignment.
         */
        public Explosion(final int x, final int y) {
            this.x = x;
            this.y = y;
            this.timer = 6;
        }
    }

    /**
     * Bitmask binary map definition driving high-tier floating mystery asset rendering routines.
     */
    private static final int[] SAUCER_BITS = {0x0F0, 0x3FC, 0x7FE, 0xAA8, 0x444};

    /**
     * Bitmask binary map layout data driving small explosion particles rendering configurations.
     */
    private static final int[] EXPLOSION_BITS = {0x24, 0x50, 0x18, 0x50, 0x24};

    /**
     * Pre-allocates native segments for fonts and UI labels to avoid allocation during render.
     */
    private void setupNativeBuffers() {
        this.fontMain = U8g2Factory.getFont("5x7_tf");
        this.fontGameOver = U8g2Factory.getFont("6x12_tf");

        // Format mask structural allocations tracking standard base text buffers
        this.scoreSegment = this.persistentArena.allocateFrom("S:00000");
        this.livesSegment = this.persistentArena.allocateFrom("P:0");
        this.gameOverSegment = this.persistentArena.allocateFrom("GAME OVER");
        this.scoreBuffer = new byte[5];
    }

    /**
     * Updates the score segment by copying bytes directly into native memory space.
     *
     * @param value The current score.
     */
    private void updateScoreSegment(final int value) {
        var temp = value;
        for (var i = 4; i >= 0; i--) {
            this.scoreBuffer[i] = (byte) ('0' + (temp % 10));
            temp /= 10;
        }
        MemorySegment.copy(this.scoreBuffer, 0, this.scoreSegment, ValueLayout.JAVA_BYTE, 2, this.scoreBuffer.length);
    }

    /**
     * Updates the lives segment by setting the byte at the specific layout offset.
     *
     * @param value The current lives.
     */
    private void updateLivesSegment(final int value) {
        final var b = (byte) ('0' + (value % 10));
        this.livesSegment.set(ValueLayout.JAVA_BYTE, 2, b);
    }

    /**
     * Initializes level data and pre-allocates buffers if full reset sequence is specified.
     *
     * @param fullReset True to reset score/lives and reload baseline configurations.
     */
    public void initLevel(final boolean fullReset) {
        if (fullReset) {
            this.lives = 3;
            this.score = 0;
            this.setupNativeBuffers();
        }
        final var w = this.getWidth();
        final var h = this.getHeight();

        this.playerXScaled = (w / 2) * 100;
        this.aiTargetX = w / 2;
        this.playerShot = null;
        this.alienMissiles.clear();
        this.invaders.clear();
        this.explosions.clear();
        this.hitLock = false;
        this.saucerActive = false;
        this.saucerTimer = 0;

        final var cols = Math.max(5, (w * 7 / 10) / 9);
        final var rows = Math.max(1, (h * 4 / 10) / 7);
        this.rackX = (w - (cols * 9)) / 2;
        this.rackY = h / 6;

        for (var row = 0; row < rows; row++) {
            for (var col = 0; col < cols; col++) {
                final var type = (row == 0) ? 0 : (row < rows / 2 ? 1 : 2);
                this.invaders.add(new Invader(col * 9, row * 7, type, true));
            }
        }

        for (var i = 0; i < 3; i++) {
            this.bunkers[i][0] = 0x3E;
            this.bunkers[i][1] = 0x7F;
            this.bunkers[i][2] = 0x63;
        }
    }

    /**
     * Registers player damage tracking sequences.
     *
     * @param isLanding Flag showing if target fleet breach caused condition.
     */
    private void registerHit(final boolean isLanding) {
        if (!this.hitLock) {
            this.hitLock = true;
            this.lives = isLanding ? 0 : this.lives - 1;
            this.playerExplosionTimer = 90;
            this.gameState = State.EXPLODING;
            this.playerShot = null;
            this.alienMissiles.clear();
        }
    }

    /**
     * General game simulation computation tracking tick update.
     */
    public void update() {
        if (this.gameState == State.EXPLODING) {
            if (--this.playerExplosionTimer <= 0) {
                if (this.lives > 0) {
                    this.initLevel(false);
                    this.gameState = State.PLAYING;
                } else {
                    this.gameState = State.GAME_OVER;
                }
            }
            return;
        }
        if (this.gameState == State.PLAYING) {
            this.updateSaucer();
            this.updateAI();
            this.updateInvaders();
            this.updateCombat();
            this.explosions.removeIf(e -> --e.timer <= 0);
        }
    }

    /**
     * Computes positioning updates for target mystery floating asset.
     */
    private void updateSaucer() {
        if (!this.saucerActive) {
            if (++this.saucerTimer > (400 + this.random.nextInt(800))) {
                this.saucerActive = true;
                this.saucerX = -20;
            }
        } else {
            this.saucerX += 1;
            if (this.saucerX > this.getWidth()) {
                this.saucerActive = false;
                this.saucerTimer = 0;
            }
        }
    }

    /**
     * Evaluates tactical threats and updates steering coordinates for algorithmic control.
     */
    private void updateAI() {
        final var h = this.getHeight();
        if (--this.aiDecisionTimer <= 0) {
            this.aiDecisionTimer = 8;
            final var currentX = this.playerXScaled / 100;
            var newTargetX = currentX;
            Projectile threat = null;

            for (final var m : this.alienMissiles) {
                if (Math.abs(m.x - currentX) < 12 && m.y > h - 35) {
                    if (threat == null || m.y > threat.y) {
                        threat = m;
                    }
                }
            }

            if (threat != null) {
                final var dodgeDist = 18 + this.random.nextInt(8);
                newTargetX = (threat.x < currentX) ? currentX + dodgeDist : currentX - dodgeDist;
            } else {
                Invader target = null;
                for (final var inv : this.invaders) {
                    if (inv.active && (target == null || inv.y > target.y)) {
                        target = inv;
                    }
                }
                if (target != null) {
                    newTargetX = target.x + this.rackX + 3 + (this.random.nextInt(5) - 2);
                }
            }
            this.aiTargetX = newTargetX;
        }

        final var targetXScaled = this.aiTargetX * 100;
        if (this.playerXScaled < targetXScaled) {
            this.playerXScaled += Math.min(PLAYER_SPEED_SCALED, targetXScaled - this.playerXScaled);
        } else if (this.playerXScaled > targetXScaled) {
            this.playerXScaled -= Math.min(PLAYER_SPEED_SCALED, this.playerXScaled - targetXScaled);
        }
        this.playerXScaled = Math.max(800, Math.min((this.getWidth() - 8) * 100, this.playerXScaled));
    }

    /**
     * Calculates spatial updates and boundaries limits for the active fleet matrix block.
     */
    private void updateInvaders() {
        var activeCount = 0;
        var minX = 1000;
        var maxX = -1000;
        final var w = this.getWidth();
        final var h = this.getHeight();

        for (final var inv : this.invaders) {
            if (inv.active) {
                activeCount++;
                final var curX = inv.x + this.rackX;
                if (curX < minX) {
                    minX = curX;
                }
                if (curX + 7 > maxX) {
                    maxX = curX + 7;
                }
                if (inv.y + this.rackY + 5 >= h - 12) {
                    this.registerHit(true);
                    return;
                }
            }
        }
        if (activeCount == 0) {
            this.initLevel(false);
            return;
        }
        if (++this.moveTimer >= Math.max(2, activeCount / 6)) {
            if (this.rackDir > 0 && maxX >= w - 2) {
                this.rackDir = -2;
                this.rackY += 3;
            } else if (this.rackDir < 0 && minX <= 2) {
                this.rackDir = 2;
                this.rackY += 3;
            } else {
                this.rackX += this.rackDir;
            }
            this.moveTimer = 0;
        }
    }

    /**
     * Evaluates ballistic translation paths, hit target verification loops, and coordinate damage updates.
     */
    private void updateCombat() {
        final var h = this.getHeight();
        final var px = this.playerXScaled / 100;
        final var saucerY = h / 6;

        if (this.playerShot == null) {
            this.playerShot = new Projectile(px, h - 10);
        } else {
            final var nx = this.playerShot.x;
            final var ny = this.playerShot.y - 4;

            if (this.saucerActive && nx >= this.saucerX && nx <= this.saucerX + 11 && ny >= saucerY && ny <= saucerY + 6) {
                this.score += 150;
                this.saucerActive = false;
                this.playerShot = null;
                this.explosions.add(new Explosion(this.saucerX + 2, saucerY));
            } else if (ny < 10 || this.checkBunkerCollision(nx, ny)) {
                this.playerShot = null;
            } else {
                this.playerShot = new Projectile(nx, ny);
                for (final var inv : this.invaders) {
                    if (inv.active && nx >= inv.x + this.rackX && nx <= inv.x + this.rackX + 7 && ny >= inv.y + this.rackY && ny
                            <= inv.y + this.rackY + 5) {
                        inv.active = false;
                        this.score += 20;
                        this.playerShot = null;
                        this.explosions.add(new Explosion(inv.x + this.rackX, inv.y + this.rackY));
                        break;
                    }
                }
            }
        }

        if (this.random.nextInt(45) == 0 && this.alienMissiles.size() < 3) {
            final var activeOnes = this.invaders.stream().filter(i -> i.active).toList();
            if (!activeOnes.isEmpty()) {
                final var s = activeOnes.get(this.random.nextInt(activeOnes.size()));
                this.alienMissiles.add(new Projectile(s.x + this.rackX + 3, s.y + this.rackY + 6));
            }
        }

        for (var i = 0; i < this.alienMissiles.size(); i++) {
            final var m = this.alienMissiles.get(i);
            final var ny = m.y + 2;
            if (ny > h || this.checkBunkerCollision(m.x, ny)) {
                this.alienMissiles.set(i, null);
            } else if (ny > h - 10 && Math.abs(m.x - px) < 5) {
                this.registerHit(false);
                return;
            } else {
                this.alienMissiles.set(i, new Projectile(m.x, ny));
            }
        }
        this.alienMissiles.removeIf(m -> m == null);
    }

    /**
     * Implements destructive bitmask updates against protective bunker matrix elements.
     *
     * @param x Horizontal pixel verification marker.
     * @param y Vertical pixel verification marker.
     * @return True if structural intersection calculation resolved hit updates.
     */
    private boolean checkBunkerCollision(final int x, final int y) {
        final var h = this.getHeight();
        final var w = this.getWidth();
        final var bunkerYStart = h - 24;
        if (y < bunkerYStart || y > bunkerYStart + 5) {
            return false;
        }
        final var spacing = w / 3;
        for (var i = 0; i < 3; i++) {
            final var bx = (spacing / 2) + (i * spacing) - 4;
            if (x >= bx && x < bx + 7) {
                final var row = (y - bunkerYStart) / 2;
                if (row >= 0 && row < 3 && ((this.bunkers[i][row] >> (6 - (x - bx))) & 1) == 1) {
                    this.bunkers[i][row] &= ~(1 << (6 - (x - bx)));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Main runtime pipeline loop handling interface driving target graphics render maps.
     *
     * @param u8g2 Native initialized u8g2_t storage block reference pointer.
     */
    @Override
    protected void run(final MemorySegment u8g2) {
        this.initLevel(true);
        final var frameDelay = 1000L / this.fps;

        final var gameThread = Thread.currentThread();
        final var shutdownHook = new Thread(() -> {
            log.debug("Interrupt signal caught! Relaying flag updates to gracefully lock down execution...");
            this.running = false;
            gameThread.interrupt();
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            while (this.running) {
                this.update();
                this.render(u8g2);
                Thread.sleep(frameDelay);
                if (this.gameState == State.GAME_OVER) {
                    Thread.sleep(3000);
                    this.running = false;
                }
            }
        } catch (final InterruptedException e) {
            log.debug("Game execution thread context step interrupted clean.");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (final IllegalStateException ignored) {
                // Ignore fallback when system is processing active termination sequences
            }

            log.info("Demo complete.");

            // CRITICAL SYNC POINT: Hardware structural unmapping loops triggered directly from Base 
            // inside this main thread MUST complete sequential dispatch before the persistent arena closes.
            this.persistentArena.close();
        }
    }

    /**
     * Handles display data block clear mappings and executes layout draw logic.
     *
     * @param u8g2 Native hardware state memory pointer handle.
     */
    private void render(final MemorySegment u8g2) {
        U8g2.u8g2_ClearBuffer(u8g2);
        final var w = this.getWidth();
        final var h = this.getHeight();

        U8g2.u8g2_SetFont(u8g2, this.fontMain);

        this.updateScoreSegment(this.score);
        this.updateLivesSegment(this.lives);

        U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) 7, this.scoreSegment);
        U8g2.u8g2_DrawStr(u8g2, (short) (w - 25), (short) 7, this.livesSegment);

        final var px = this.playerXScaled / 100;
        if (this.gameState == State.EXPLODING) {
            for (var i = 0; i < 20; i++) {
                U8g2.u8g2_DrawPixel(u8g2, (short) (px + this.random.nextInt(15) - 7), (short) (h - 5 + this.random.nextInt(10) - 5));
            }
        } else if (this.gameState != State.GAME_OVER) {
            U8g2.u8g2_DrawBox(u8g2, (short) (px - 4), (short) (h - 5), (short) 9, (short) 4);
            U8g2.u8g2_DrawBox(u8g2, (short) (px - 1), (short) (h - 7), (short) 3, (short) 2);
        }

        this.renderWorld(u8g2);

        if (this.gameState == State.GAME_OVER) {
            U8g2.u8g2_SetFont(u8g2, this.fontGameOver);
            final var tw = (int) U8g2.u8g2_GetStrWidth(u8g2, this.gameOverSegment);
            final var tx = (w - tw) / 2;
            final var ty = (h / 2) + 4;
            U8g2.u8g2_SetDrawColor(u8g2, (byte) 0);
            U8g2.u8g2_DrawBox(u8g2, (short) (tx - 2), (short) (ty - 10), (short) (tw + 4), (short) 14);
            U8g2.u8g2_SetDrawColor(u8g2, (byte) 1);
            U8g2.u8g2_DrawStr(u8g2, (short) tx, (short) ty, this.gameOverSegment);
        }
        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Executes localized object rendering matrices across all current active items.
     *
     * @param u8g2 Native state storage tracking layout map.
     */
    private void renderWorld(final MemorySegment u8g2) {
        final var h = this.getHeight();
        final var w = this.getWidth();

        if (this.saucerActive) {
            final var sy = h / 6;
            for (var i = 0; i < 5; i++) {
                for (var b = 0; b < 12; b++) {
                    if (((SAUCER_BITS[i] >> (11 - b)) & 1) == 1) {
                        U8g2.u8g2_DrawPixel(u8g2, (short) (this.saucerX + b), (short) (sy + i));
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
                    if (((this.bunkers[i][r] >> (6 - c)) & 1) == 1) {
                        U8g2.u8g2_DrawBox(u8g2, (short) (bx + c), (short) (bunkerYStart + (r * 2)), (short) 1, (short) 2);
                    }
                }
            }
        }

        for (final var inv : this.invaders) {
            if (inv.active) {
                final int[] bts = (inv.type == 0) ? new int[]{0x10, 0x38, 0x7C, 0x28} : (inv.type == 1)
                        ? new int[]{0x44, 0x38, 0x7C, 0x10} : new int[]{0x38, 0x7C, 0x7C, 0x44};
                for (var i = 0; i < 4; i++) {
                    for (var b = 0; b < 7; b++) {
                        if (((bts[i] >> (6 - b)) & 1) == 1) {
                            U8g2.u8g2_DrawPixel(u8g2, (short) (inv.x + this.rackX + b), (short) (inv.y + this.rackY + i));
                        }
                    }
                }
            }
        }

        for (final var exp : this.explosions) {
            for (var i = 0; i < 5; i++) {
                for (var b = 0; b < 8; b++) {
                    if (((EXPLOSION_BITS[i] >> (7 - b)) & 1) == 1) {
                        U8g2.u8g2_DrawPixel(u8g2, (short) (exp.x + b), (short) (exp.y + i));
                    }
                }
            }
        }

        if (this.playerShot != null) {
            U8g2.u8g2_DrawVLine(u8g2, (short) this.playerShot.x, (short) this.playerShot.y, (short) 3);
        }
        for (final var m : this.alienMissiles) {
            U8g2.u8g2_DrawVLine(u8g2, (short) m.x, (short) m.y, (short) 3);
        }
    }

    /**
     * Main entry point routing for command line launcher components.
     *
     * @param args Command line execution flags.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SpaceInvaders()).execute(args));
    }
}
