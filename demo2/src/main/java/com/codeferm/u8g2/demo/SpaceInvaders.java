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
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Space Invaders demo with AI-driven player and resolution-aware scaling.
 *
 * <p>
 * The application owns the display lifecycle from the same thread that owns the native u8g2 state. Ctrl+C only requests
 * termination; actual display cleanup occurs in {@link #run(MemorySegment)} finally, before the native memory arena is closed.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(
        name = "SpaceInvaders",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Space Invaders - Dynamic Resolution Demo"
)
public class SpaceInvaders extends Base {

    /**
     * Main method entry point instantiating application context and mapping shutdown hooks.
     *
     * @param args Command line argument array references.
     */
    public static void main(final String... args) {
        final var app = new SpaceInvaders();
        final var mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.atDebug().log("Shutdown signal acknowledged by hook thread.");
            app.running = false;
            if (app.gameThread != null) {
                app.gameThread.interrupt();
            }
            try {
                mainThread.join(2000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        System.exit(new CommandLine(app).execute(args));
    }

    /**
     * Target frames per second integer constraint option configuration.
     */
    @Option(
            names = {"-f", "--fps"},
            description = "Frames per second",
            defaultValue = "60"
    )
    private int fps;

    /**
     * Random generator instance reference for AI trajectory steering and effect calculations.
     */
    private final Random random = new Random();

    /**
     * Game execution loop active control volatile flag.
     */
    protected volatile boolean running = true;

    /**
     * Reference handle to the main active game execution thread.
     */
    private Thread gameThread;

    /**
     * Atomic boolean instance flag preventing duplicate shutdown sequence triggers.
     */
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * Atomic boolean flag tracking completion status of native display cleanup operations.
     */
    private final AtomicBoolean displayCleaned = new AtomicBoolean(false);

    /**
     * Enumerated list defining runtime application states.
     */
    public enum State {
        /**
         * Active playing match state.
         */
        PLAYING,
        /**
         * Player ship destruction animation sequence state.
         */
        EXPLODING,
        /**
         * Terminal game over state view.
         */
        GAME_OVER
    }

    /**
     * Current active game lifecycle state enum pointer.
     */
    private State gameState = State.PLAYING;

    /**
     * Integer holding accumulated player match score metrics.
     */
    private int score;

    /**
     * Integer tracking remaining player lives inventory.
     */
    private int lives = 3;

    /**
     * Active tick counter timer tracking player explosion animation frames.
     */
    private int playerExplosionTimer;

    /**
     * Boolean lock flag preventing recursive or duplicate damage registrations.
     */
    private boolean hitLock;

    /**
     * Precise player horizontal X coordinate scaled explicitly by 100 factor.
     */
    private int playerXScaled;

    /**
     * Constant representing player ship navigation velocity in scaled units.
     */
    private static final int PLAYER_SPEED_SCALED = 200;

    /**
     * Frame countdown timer governing AI navigation decision evaluations.
     */
    private int aiDecisionTimer;

    /**
     * Target coordinate integer mapping desired AI positioning.
     */
    private int aiTargetX;

    /**
     * Record reference tracking active player projectile state.
     */
    private Projectile playerShot;

    /**
     * List tracking active hostile alien missile projectiles.
     */
    private final List<Projectile> alienMissiles = new ArrayList<>();

    /**
     * List containing active alien invader fleet entities.
     */
    private final List<Invader> invaders = new ArrayList<>();

    /**
     * List tracking active visual screen explosion point instances.
     */
    private final List<Explosion> explosions = new ArrayList<>();

    /**
     * Horizontal coordinate offset for the alien fleet rack rendering matrix.
     */
    private int rackX;

    /**
     * Vertical coordinate offset for the alien fleet rack rendering matrix.
     */
    private int rackY;

    /**
     * Horizontal movement velocity direction vector for the alien rack.
     */
    private int rackDir = 2;

    /**
     * Frame timing tick counter managing alien fleet traversal updates.
     */
    private int moveTimer;

    /**
     * 2D integer array storing protective bunker bitmap block mask states.
     */
    private final int[][] bunkers = new int[3][3];

    /**
     * Horizontal coordinate tracking flying saucer position.
     */
    private int saucerX = -20;

    /**
     * Timer tick counter tracking saucer spawn intervals.
     */
    private int saucerTimer;

    /**
     * Boolean flag indicating whether a mystery saucer is currently active.
     */
    private boolean saucerActive;

    /**
     * Shared native memory arena instance holding persistent application segments.
     */
    private final Arena persistentArena = Arena.ofShared();

    /**
     * Memory segment pointer referencing regular primary rendering font.
     */
    private MemorySegment fontMain;

    /**
     * Memory segment pointer referencing large game over display font.
     */
    private MemorySegment fontGameOver;

    /**
     * Memory segment string buffer for score rendering display.
     */
    private MemorySegment scoreSegment;

    /**
     * Memory segment string buffer for lives status display.
     */
    private MemorySegment livesSegment;

    /**
     * Memory segment string buffer for game over announcement text.
     */
    private MemorySegment gameOverSegment;

    /**
     * Reusable byte array buffer supporting score formatting operations.
     */
    private byte[] scoreBuffer;

    /**
     * Reusable integer array storing alien sprite raster masks.
     */
    private final int[] alienBitsBuffer = new int[4];

    /**
     * Boolean indicator tracking whether native text buffers are initialized.
     */
    private boolean nativeBuffersInitialized;

    /**
     * Immutable data record representing project coordinate elements.
     *
     * @param x Horizontal pixel coordinate value.
     * @param y Vertical pixel coordinate value.
     */
    public static record Projectile(int x, int y) {

    }

    /**
     * Class representing an individual alien invader fleet element.
     */
    public static class Invader {

        /**
         * Horizontal X coordinate relative to the alien rack container origin.
         */
        public int x;

        /**
         * Vertical Y coordinate relative to the alien rack container origin.
         */
        public int y;

        /**
         * Classification type index identifier for the invader model.
         */
        public int type;

        /**
         * Boolean flag specifying if the invader is currently active/alive.
         */
        public boolean active;

        /**
         * Constructs a new Invader instance with positional and classification parameters.
         *
         * @param x Horizontal relative coordinate.
         * @param y Vertical relative coordinate.
         * @param type Invader tier classification type.
         * @param active Initial activity status flag.
         */
        public Invader(
                final int x,
                final int y,
                final int type,
                final boolean active
        ) {
            this.x = x;
            this.y = y;
            this.type = type;
            this.active = active;
        }
    }

    /**
     * Class representing a temporary visual screen explosion effect.
     */
    public static class Explosion {

        /**
         * Horizontal screen coordinate position.
         */
        public int x;

        /**
         * Vertical screen coordinate position.
         */
        public int y;

        /**
         * Remaining animation frame countdown timer ticks.
         */
        public int timer;

        /**
         * Constructs a new Explosion effect instance at designated coordinates.
         *
         * @param x Horizontal screen coordinate.
         * @param y Vertical screen coordinate.
         */
        public Explosion(final int x, final int y) {
            this.x = x;
            this.y = y;
            this.timer = 6;
        }
    }

    /**
     * Static bitmap matrix definitions representing bonus saucer graphics.
     */
    private static final int[] SAUCER_BITS = {
        0x0F0,
        0x3FC,
        0x7FE,
        0xAA8,
        0x444
    };

    /**
     * Static bitmap array defining explosion sparkle graphics.
     */
    private static final int[] EXPLOSION_BITS = {
        0x24,
        0x50,
        0x18,
        0x50,
        0x24
    };

    /**
     * Initializes native string and font memory buffers safely.
     *
     * <p>
     * This method is deliberately idempotent because the same native arena remains alive for the complete demo lifetime.
     * </p>
     */
    private void setupNativeBuffers() {
        if (this.nativeBuffersInitialized) {
            return;
        }

        this.fontMain = U8g2Factory.getFont("5x7_tf");
        this.fontGameOver = U8g2Factory.getFont("6x12_tf");

        this.scoreSegment = this.persistentArena.allocateFrom("S:00000");
        this.livesSegment = this.persistentArena.allocateFrom("P:0");
        this.gameOverSegment = this.persistentArena.allocateFrom("GAME OVER");

        this.scoreBuffer = new byte[5];
        this.nativeBuffersInitialized = true;
    }

    /**
     * Updates the score text display segment contents efficiently.
     *
     * @param value Current numeric score integer value to format.
     */
    private void updateScoreSegment(final int value) {
        var temp = Math.max(0, value);

        for (var i = 4; i >= 0; i--) {
            this.scoreBuffer[i] = (byte) ('0' + (temp % 10));
            temp /= 10;
        }

        MemorySegment.copy(
                this.scoreBuffer,
                0,
                this.scoreSegment,
                ValueLayout.JAVA_BYTE,
                2,
                this.scoreBuffer.length
        );
    }

    /**
     * Updates the remaining lives text indicator segment.
     *
     * @param value Current remaining lives integer count.
     */
    private void updateLivesSegment(final int value) {
        final var b = (byte) ('0' + Math.max(0, value) % 10);
        this.livesSegment.set(ValueLayout.JAVA_BYTE, 2, b);
    }

    /**
     * Initializes or fully resets the simulation level components.
     *
     * @param fullReset True to reset score and lives to default starting values.
     */
    public void initLevel(final boolean fullReset) {
        if (fullReset) {
            this.lives = 3;
            this.score = 0;
            this.gameState = State.PLAYING;
            this.setupNativeBuffers();
        }

        final var w = this.getWidth();
        final var h = this.getHeight();

        this.playerXScaled = (w / 2) * 100;
        this.aiTargetX = w / 2;
        this.aiDecisionTimer = 0;

        this.playerShot = null;
        this.alienMissiles.clear();
        this.invaders.clear();
        this.explosions.clear();

        this.hitLock = false;

        this.saucerActive = false;
        this.saucerTimer = 0;
        this.saucerX = -20;

        this.rackDir = 2;
        this.moveTimer = 0;

        final var cols = Math.max(5, (w * 7 / 10) / 9);
        final var rows = Math.max(1, (h * 4 / 10) / 7);

        this.rackX = (w - (cols * 9)) / 2;
        this.rackY = h / 6;

        for (var row = 0; row < rows; row++) {
            for (var col = 0; col < cols; col++) {
                final var type = (row == 0)
                        ? 0
                        : (row < rows / 2 ? 1 : 2);

                this.invaders.add(
                        new Invader(
                                col * 9,
                                row * 7,
                                type,
                                true
                        )
                );
            }
        }

        for (var i = 0; i < 3; i++) {
            this.bunkers[i][0] = 0x3E;
            this.bunkers[i][1] = 0x7F;
            this.bunkers[i][2] = 0x63;
        }
    }

    /**
     * Registers damage or destruction events against the player character.
     *
     * @param isLanding True if hostile alien forces have breached the lower landing threshold.
     */
    private void registerHit(final boolean isLanding) {
        if (!this.hitLock) {
            this.hitLock = true;

            if (isLanding) {
                this.lives = 0;
            } else {
                this.lives--;
            }

            this.playerExplosionTimer = 90;
            this.gameState = State.EXPLODING;
            this.playerShot = null;
            this.alienMissiles.clear();
        }
    }

    /**
     * Executes single-frame updates across simulation entities and state controllers.
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
     * Updates mystery bonus saucer positioning and spawn timers.
     */
    private void updateSaucer() {
        if (!this.saucerActive) {
            if (++this.saucerTimer > 400 + this.random.nextInt(800)) {
                this.saucerActive = true;
                this.saucerX = -20;
            }
        } else {
            this.saucerX++;

            if (this.saucerX > this.getWidth()) {
                this.saucerActive = false;
                this.saucerTimer = 0;
            }
        }
    }

    /**
     * Evaluates tactical maneuvers and updates AI navigation targets.
     */
    private void updateAI() {
        final var h = this.getHeight();

        if (--this.aiDecisionTimer <= 0) {
            this.aiDecisionTimer = 8;

            final var currentX = this.playerXScaled / 100;
            var newTargetX = currentX;
            Projectile threat = null;

            for (final var missile : this.alienMissiles) {
                if (Math.abs(missile.x - currentX) < 12
                        && missile.y > h - 35) {

                    if (threat == null || missile.y > threat.y) {
                        threat = missile;
                    }
                }
            }

            if (threat != null) {
                final var dodgeDist = 18 + this.random.nextInt(8);

                newTargetX = threat.x < currentX
                        ? currentX + dodgeDist
                        : currentX - dodgeDist;
            } else {
                Invader target = null;

                for (final var invader : this.invaders) {
                    if (invader.active
                            && (target == null || invader.y > target.y)) {
                        target = invader;
                    }
                }

                if (target != null) {
                    newTargetX = target.x
                            + this.rackX
                            + 3
                            + (this.random.nextInt(5) - 2);
                }
            }

            this.aiTargetX = newTargetX;
        }

        final var targetXScaled = this.aiTargetX * 100;

        if (this.playerXScaled < targetXScaled) {
            this.playerXScaled += Math.min(
                    PLAYER_SPEED_SCALED,
                    targetXScaled - this.playerXScaled
            );
        } else if (this.playerXScaled > targetXScaled) {
            this.playerXScaled -= Math.min(
                    PLAYER_SPEED_SCALED,
                    this.playerXScaled - targetXScaled
            );
        }

        this.playerXScaled = Math.max(
                800,
                Math.min(
                        (this.getWidth() - 8) * 100,
                        this.playerXScaled
                )
        );
    }

    /**
     * Updates alien fleet rack movement patterns, boundaries, and descent logic.
     */
    private void updateInvaders() {
        var activeCount = 0;
        var minX = 1000;
        var maxX = -1000;

        final var w = this.getWidth();
        final var h = this.getHeight();

        for (final var invader : this.invaders) {
            if (invader.active) {
                activeCount++;

                final var curX = invader.x + this.rackX;

                minX = Math.min(minX, curX);
                maxX = Math.max(maxX, curX + 7);

                if (invader.y + this.rackY + 5 >= h - 12) {
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
     * Manages combat mechanics including project trajectories, firing triggers, and collisions.
     */
    private void updateCombat() {
        final var h = this.getHeight();
        final var px = this.playerXScaled / 100;
        final var saucerY = h / 6;

        if (this.playerShot == null) {
            if (this.random.nextInt(25) == 0) {
                this.playerShot = new Projectile(px, h - 10);
            }
        } else {
            final var nx = this.playerShot.x;
            final var ny = this.playerShot.y - 4;

            if (this.saucerActive
                    && nx >= this.saucerX
                    && nx <= this.saucerX + 11
                    && ny >= saucerY
                    && ny <= saucerY + 6) {

                this.score += 150;
                this.saucerActive = false;
                this.playerShot = null;

                this.explosions.add(
                        new Explosion(this.saucerX + 2, saucerY)
                );

            } else if (ny < 10 || this.checkBunkerCollision(nx, ny)) {
                this.playerShot = null;
            } else {
                this.playerShot = new Projectile(nx, ny);

                for (final var invader : this.invaders) {
                    if (invader.active
                            && nx >= invader.x + this.rackX
                            && nx <= invader.x + this.rackX + 7
                            && ny >= invader.y + this.rackY
                            && ny <= invader.y + this.rackY + 5) {

                        invader.active = false;
                        this.score += 20;
                        this.playerShot = null;

                        this.explosions.add(
                                new Explosion(
                                        invader.x + this.rackX,
                                        invader.y + this.rackY
                                )
                        );

                        break;
                    }
                }
            }
        }

        if (this.random.nextInt(45) == 0
                && this.alienMissiles.size() < 3) {

            final var activeOnes = this.invaders
                    .stream()
                    .filter(i -> i.active)
                    .toList();

            if (!activeOnes.isEmpty()) {
                final var shooter = activeOnes.get(
                        this.random.nextInt(activeOnes.size())
                );

                this.alienMissiles.add(
                        new Projectile(
                                shooter.x + this.rackX + 3,
                                shooter.y + this.rackY + 6
                        )
                );
            }
        }

        for (var i = 0; i < this.alienMissiles.size(); i++) {
            final var missile = this.alienMissiles.get(i);
            final var ny = missile.y + 2;

            if (ny > h || this.checkBunkerCollision(missile.x, ny)) {
                this.alienMissiles.set(i, null);

            } else if (ny > h - 10
                    && Math.abs(missile.x - px) < 5) {

                this.registerHit(false);
                return;

            } else {
                this.alienMissiles.set(
                        i,
                        new Projectile(missile.x, ny)
                );
            }
        }

        this.alienMissiles.removeIf(m -> m == null);
    }

    /**
     * Checks projectile collisions against defensive bunker structures.
     *
     * @param x Horizontal pixel coordinate to evaluate.
     * @param y Vertical pixel coordinate to evaluate.
     * @return True if a collision occurred and destructible pixels were impacted.
     */
    private boolean checkBunkerCollision(
            final int x,
            final int y
    ) {
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

                final var bit = 6 - (x - bx);

                if (row >= 0
                        && row < 3
                        && bit >= 0
                        && bit < 7
                        && ((this.bunkers[i][row] >> bit) & 1) == 1) {

                    this.bunkers[i][row] &= ~(1 << bit);
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
        this.gameThread = Thread.currentThread();
        this.initLevel(true);
        final var frameDelay = 1000L / this.fps;

        try {
            while (this.running) {
                final var frameStart = System.currentTimeMillis();

                this.update();
                this.render(u8g2);

                if (this.gameState == State.GAME_OVER) {
                    for (var i = 0; i < 30 && this.running; i++) {
                        Thread.sleep(100);
                    }
                    this.running = false;
                    break;
                }

                final var elapsed = System.currentTimeMillis() - frameStart;
                final var sleepTime = frameDelay - elapsed;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            }
            log.debug("Exit running state");
        } catch (final InterruptedException e) {
            log.debug("Game execution thread context step interrupted clean.");
        } finally {
            try (this.persistentArena) {
                log.info("Demo complete.");
            }
        }
    }

    /**
     * Renders a complete simulation frame to the display buffer.
     *
     * @param u8g2 Native initialized u8g2 state memory segment.
     */
    private void render(final MemorySegment u8g2) {
        U8g2.u8g2_ClearBuffer(u8g2);

        final var w = this.getWidth();
        final var h = this.getHeight();

        U8g2.u8g2_SetFont(
                u8g2,
                this.fontMain
        );

        this.updateScoreSegment(this.score);
        this.updateLivesSegment(this.lives);

        U8g2.u8g2_DrawStr(
                u8g2,
                (short) 1,
                (short) 7,
                this.scoreSegment
        );

        U8g2.u8g2_DrawStr(
                u8g2,
                (short) (w - 25),
                (short) 7,
                this.livesSegment
        );

        final var px = this.playerXScaled / 100;

        if (this.gameState == State.EXPLODING) {
            for (var i = 0; i < 20; i++) {
                U8g2.u8g2_DrawPixel(
                        u8g2,
                        (short) (px
                        + this.random.nextInt(15)
                        - 7),
                        (short) (h
                        - 5
                        + this.random.nextInt(10)
                        - 5)
                );
            }
        } else if (this.gameState != State.GAME_OVER) {
            U8g2.u8g2_DrawBox(
                    u8g2,
                    (short) (px - 4),
                    (short) (h - 5),
                    (short) 9,
                    (short) 4
            );

            U8g2.u8g2_DrawBox(
                    u8g2,
                    (short) (px - 1),
                    (short) (h - 7),
                    (short) 3,
                    (short) 2
            );
        }

        this.renderWorld(u8g2);

        if (this.playerShot != null) {
            U8g2.u8g2_DrawPixel(u8g2, (short) this.playerShot.x(), (short) this.playerShot.y());
        }

        for (final var missile : this.alienMissiles) {
            if (missile != null) {
                U8g2.u8g2_DrawPixel(u8g2, (short) missile.x(), (short) missile.y());
            }
        }

        if (this.gameState == State.GAME_OVER) {
            U8g2.u8g2_SetFont(
                    u8g2,
                    this.fontGameOver
            );

            final var tw = (int) U8g2.u8g2_GetStrWidth(
                    u8g2,
                    this.gameOverSegment
            );

            final var tx = (w - tw) / 2;
            final var ty = (h / 2) + 4;

            U8g2.u8g2_SetDrawColor(
                    u8g2,
                    (byte) 0
            );

            U8g2.u8g2_DrawBox(
                    u8g2,
                    (short) (tx - 2),
                    (short) (ty - 10),
                    (short) (tw + 4),
                    (short) 14
            );

            U8g2.u8g2_SetDrawColor(
                    u8g2,
                    (byte) 1
            );

            U8g2.u8g2_DrawStr(
                    u8g2,
                    (short) tx,
                    (short) ty,
                    this.gameOverSegment
            );
        }

        U8g2.u8g2_SendBuffer(u8g2);
    }

    /**
     * Renders world game entities including invaders, bunkers, saucers, and explosions.
     *
     * @param u8g2 Native initialized u8g2 state memory segment.
     */
    private void renderWorld(final MemorySegment u8g2) {
        final var h = this.getHeight();
        final var w = this.getWidth();

        if (this.saucerActive) {
            final var sy = h / 6;

            for (var i = 0; i < 5; i++) {
                for (var b = 0; b < 12; b++) {
                    if (((SAUCER_BITS[i] >> (11 - b)) & 1) != 0) {
                        U8g2.u8g2_DrawPixel(
                                u8g2,
                                (short) (this.saucerX + b),
                                (short) (sy + i)
                        );
                    }
                }
            }
        }

        final var bunkerYStart = h - 24;
        final var spacing = w / 3;

        for (var i = 0; i < 3; i++) {
            final var bx = (spacing / 2)
                    + (i * spacing)
                    - 4;

            for (var r = 0; r < 3; r++) {
                for (var c = 0; c < 7; c++) {
                    if (((this.bunkers[i][r] >> (6 - c)) & 1) != 0) {
                        U8g2.u8g2_DrawBox(
                                u8g2,
                                (short) (bx + c),
                                (short) (bunkerYStart
                                + (r * 2)),
                                (short) 1,
                                (short) 2
                        );
                    }
                }
            }
        }

        for (final var inv : this.invaders) {
            if (!inv.active) {
                continue;
            }

            if (inv.type == 0) {
                this.alienBitsBuffer[0] = 0x10;
                this.alienBitsBuffer[1] = 0x38;
                this.alienBitsBuffer[2] = 0x7C;
                this.alienBitsBuffer[3] = 0x28;
            } else if (inv.type == 1) {
                this.alienBitsBuffer[0] = 0x44;
                this.alienBitsBuffer[1] = 0x38;
                this.alienBitsBuffer[2] = 0x7C;
                this.alienBitsBuffer[3] = 0x10;
            } else {
                this.alienBitsBuffer[0] = 0x38;
                this.alienBitsBuffer[1] = 0x7C;
                this.alienBitsBuffer[2] = 0x7C;
                this.alienBitsBuffer[3] = 0x44;
            }

            for (var i = 0; i < 4; i++) {
                for (var b = 0; b < 7; b++) {
                    if (((this.alienBitsBuffer[i] >> (6 - b)) & 1) != 0) {
                        U8g2.u8g2_DrawPixel(
                                u8g2,
                                (short) (inv.x
                                + this.rackX
                                + b),
                                (short) (inv.y
                                + this.rackY
                                + i)
                        );
                    }
                }
            }
        }

        for (final var exp : this.explosions) {
            for (var i = 0; i < 5; i++) {
                for (var b = 0; b < 8; b++) {
                    if (((EXPLOSION_BITS[i] >> (7 - b)) & 1) != 0) {
                        U8g2.u8g2_DrawPixel(
                                u8g2,
                                (short) (exp.x + b),
                                (short) (exp.y + i)
                        );
                    }
                }
            }
        }
    }
}
