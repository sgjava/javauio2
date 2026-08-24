/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Space Invaders for color displays with AI-driven player, dynamic sprite scaling, and dirty-write optimization.
 * <p>
 * This class calculates invader grid density, entity positioning, and tracks dirty screen regions to minimize unnecessary
 * full-frame writes to the display buffer.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SpaceInvaders", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Space Invaders for color displays with dirty write optimization")
public class SpaceInvaders extends Base {

    /**
     * Internal game state.
     */
    public enum State {
        /**
         * Active gameplay state.
         */
        PLAYING,
        /**
         * Player explosion animation state.
         */
        EXPLODING,
        /**
         * Game over conclusion state.
         */
        GAME_OVER
    }

    /**
     * Random number generator for game logic.
     */
    private final Random random = new Random();

    /**
     * Main loop control flag.
     */
    private boolean running = true;

    /**
     * Current game state.
     */
    private State gameState = State.PLAYING;

    /**
     * Current player score.
     */
    private int score = 0;

    /**
     * Remaining player lives.
     */
    private int lives = 3;

    /**
     * Frame counter for player explosion animation.
     */
    private int playerExplosionTimer = 0;

    /**
     * Flag to prevent multiple hits in a single frame.
     */
    private boolean hitLock = false;

    /**
     * Player X position scaled by 100 for sub-pixel movement.
     */
    private int playerXScaled;

    /**
     * Constant for player movement speed.
     */
    private static final int PLAYER_SPEED_SCALED = 200;

    /**
     * Timer for AI trajectory changes.
     */
    private int aiDecisionTimer = 0;

    /**
     * Current X coordinate target for the AI.
     */
    private int aiTargetX = 0;

    /**
     * Active player projectile.
     */
    private Projectile playerShot = null;

    /**
     * List of active alien projectiles.
     */
    private final List<Projectile> alienMissiles = new ArrayList<>();

    /**
     * List of active invaders.
     */
    private final List<Invader> invaders = new ArrayList<>();

    /**
     * List of active particle explosions.
     */
    private final List<Explosion> explosions = new ArrayList<>();

    /**
     * Bunker bitmasks [bunker index][row].
     */
    private final int[][] bunkers = new int[3][3];

    /**
     * X offset of the invader rack.
     */
    private int rackX;

    /**
     * Y offset of the invader rack.
     */
    private int rackY;

    /**
     * Movement direction of the rack.
     */
    private int rackDir = 2;

    /**
     * Timer for invader rack stepping.
     */
    private int moveTimer = 0;

    /**
     * Saucer X position.
     */
    private int saucerX = -20;

    /**
     * Timer for saucer spawn intervals.
     */
    private int saucerTimer = 0;

    /**
     * Saucer activity flag.
     */
    private boolean saucerActive = false;

    /**
     * Scale factor for sprites based on screen resolution (baseline 96x64).
     */
    private float spriteScale = 1.0f;
    private int scaledBaseSize = 6;
    private int scaledInvaderWidth = 7;
    private int scaledInvaderHeight = 5;
    private int scaledSaucerWidth = 12;

    // Sprites
    private BufferedImage invaderType0Sprite, invaderType1Sprite, invaderType2Sprite;
    private BufferedImage saucerSprite, playerSprite, explosionSprite;

    /**
     * Projectile data record.
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     */
    public record Projectile(int x, int y) {

    }

    /**
     * Invader entity class.
     */
    public static class Invader {

        /**
         * X coordinate.
         */
        public int x;
        /**
         * Y coordinate.
         */
        public int y;
        /**
         * Invader type variant.
         */
        public int type;
        /**
         * Active state flag.
         */
        public boolean active;

        /**
         * Constructs a new Invader.
         *
         * @param x X coordinate.
         * @param y Y coordinate.
         * @param type Invader type.
         * @param active Active state.
         */
        public Invader(final int x, final int y, final int type, final boolean active) {
            this.x = x;
            this.y = y;
            this.type = type;
            this.active = active;
        }
    }

    /**
     * Particle explosion entity class.
     */
    public static class Explosion {

        /**
         * X coordinate.
         */
        public int x;
        /**
         * Y coordinate.
         */
        public int y;
        /**
         * Countdown timer.
         */
        public int timer = 6;

        /**
         * Constructs a new Explosion particle.
         *
         * @param x X coordinate.
         * @param y Y coordinate.
         */
        public Explosion(final int x, final int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Calculates scaling parameters based on display dimensions.
     */
    private void calculateScaling() {
        final var screenW = getWidth();
        spriteScale = Math.max(1.0f, Math.min(screenW / 96.0f, 3.0f));
        scaledBaseSize = Math.max(6, (int) (6 * spriteScale));
        scaledInvaderWidth = Math.max(7, (int) (7 * spriteScale));
        scaledInvaderHeight = Math.max(5, (int) (5 * spriteScale));
        scaledSaucerWidth = Math.max(12, (int) (12 * spriteScale));
    }

    /**
     * Initializes all bitmap sprites for game entities with dynamic scaling.
     */
    private void initSprites() {
        calculateScaling();

        invaderType0Sprite = createScaledBitmap(7, 4, new int[][]{{0, 0, 1, 0, 1, 0, 0}, {0, 0, 0, 1, 0, 0, 0},
        {0, 1, 1, 1, 1, 1, 0}, {1, 0, 1, 1, 1, 0, 1}}, Color.MAGENTA, null);
        invaderType1Sprite = createScaledBitmap(7, 4, new int[][]{{0, 1, 0, 0, 0, 1, 0}, {0, 0, 1, 1, 1, 0, 0},
        {0, 1, 1, 1, 1, 1, 0}, {0, 0, 1, 0, 1, 0, 0}}, Color.CYAN, null);
        invaderType2Sprite = createScaledBitmap(7, 4, new int[][]{{0, 0, 1, 1, 1, 0, 0}, {0, 1, 1, 1, 1, 1, 0},
        {0, 1, 1, 1, 1, 1, 0}, {1, 0, 1, 0, 1, 0, 1}}, Color.GREEN, null);

        saucerSprite = createScaledBitmap(12, 5, new int[][]{{0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0}, {0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0,
            0},
        {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0}, {1, 0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1}, {0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0}},
                Color.RED, null);

        playerSprite = createScaledBitmap(9, 4, new int[][]{{0, 0, 0, 0, 1, 0, 0, 0, 0}, {0, 0, 1, 1, 1, 1, 1, 0, 0},
        {0, 1, 1, 1, 1, 1, 1, 1, 0}, {1, 1, 1, 1, 1, 1, 1, 1, 1}}, Color.YELLOW, null);

        explosionSprite = createScaledBitmap(8, 5, new int[][]{{0, 0, 1, 0, 0, 1, 0, 0}, {0, 1, 0, 1, 1, 0, 1, 0},
        {1, 0, 0, 0, 0, 0, 0, 1}, {0, 1, 0, 1, 1, 0, 1, 0}, {0, 0, 1, 0, 0, 1, 0, 0}}, Color.ORANGE, null);
    }

    /**
     * Helper to generate a dynamically scaled BufferedImage from a template array using nearest-neighbor scaling.
     *
     * @param origW Original template width.
     * @param origH Original template height.
     * @param data Template data grid.
     * @param c1 Primary color.
     * @param c2 Secondary color.
     * @return Scaled BufferedImage.
     */
    private BufferedImage createScaledBitmap(final int origW, final int origH, final int[][] data, final Color c1, final Color c2) {
        final var targetW = (origW == 7) ? scaledInvaderWidth
                : (origW == 12 ? scaledSaucerWidth : (origW == 9 ? scaledBaseSize + 3 : 8));
        final var targetH = (origH == 4) ? scaledInvaderHeight - 1 : (origH == 5 ? scaledInvaderHeight : 6);
        final var img = new BufferedImage(Math.max(1, targetW), Math.max(1, targetH), BufferedImage.TYPE_INT_RGB);
        final var g2d = img.createGraphics();

        for (var y = 0; y < targetH; y++) {
            for (var x = 0; x < targetW; x++) {
                final var srcX = x * origW / targetW;
                final var srcY = y * origH / targetH;
                if (srcY < data.length && srcX < data[srcY].length) {
                    if (data[srcY][srcX] == 1) {
                        img.setRGB(x, y, c1.getRGB());
                    } else if (data[srcY][srcX] == 2 && c2 != null) {
                        img.setRGB(x, y, c2.getRGB());
                    }
                }
            }
        }
        g2d.dispose();
        return img;
    }

    /**
     * Initializes the level state, including grid calculation and bunker resets.
     *
     * @param w Display width.
     * @param h Display height.
     * @param fullReset True if lives/score should be reset.
     */
    public final void initLevel(final int w, final int h, final boolean fullReset) {
        if (fullReset) {
            score = 0;
            lives = 3;
            initSprites();
        }
        playerXScaled = (w / 2) * 100;
        aiTargetX = w / 2;
        playerShot = null;
        alienMissiles.clear();
        invaders.clear();
        explosions.clear();
        hitLock = false;
        saucerActive = false;
        saucerTimer = 0;

        final var colSpacing = scaledInvaderWidth + 2;
        final var rowSpacing = scaledInvaderHeight + 2;
        final var cols = Math.max(5, (w * 7 / 10) / colSpacing);
        final var rows = Math.max(1, (h * 4 / 10) / rowSpacing);
        rackX = (w - (cols * colSpacing)) / 2;
        rackY = h / 6;

        for (var row = 0; row < rows; row++) {
            for (var col = 0; col < cols; col++) {
                final var type = (row == 0) ? 0 : (row < rows / 2 ? 1 : 2);
                invaders.add(new Invader(col * colSpacing, row * rowSpacing, type, true));
            }
        }
        for (var i = 0; i < 3; i++) {
            bunkers[i][0] = 0x3E;
            bunkers[i][1] = 0x7F;
            bunkers[i][2] = 0x63;
        }
    }

    /**
     * Triggers player hit state.
     *
     * @param isLanding True if invaders have reached the bottom.
     */
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
     * Main update logic for AI, movement, and combat.
     *
     * @param w Display width.
     * @param h Display height.
     */
    private void updateLogic(final int w, final int h) {
        if (gameState == State.EXPLODING) {
            if (--playerExplosionTimer <= 0) {
                if (lives > 0) {
                    initLevel(w, h, false);
                    gameState = State.PLAYING;
                } else {
                    gameState = State.GAME_OVER;
                }
            }
            return;
        }
        if (gameState == State.PLAYING) {
            updateAI(w, h);
            updateSaucer(w);
            updateInvaders(w, h);
            updateCombat(w, h);
            explosions.removeIf(e -> --e.timer <= 0);
        }
    }

    /**
     * AI logic for dodging missiles and targeting invaders.
     *
     * @param w Display width.
     * @param h Display height.
     */
    private void updateAI(final int w, final int h) {
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
                    newTargetX = target.x + rackX + (scaledInvaderWidth / 2) + (random.nextInt(5) - 2);
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
        playerXScaled = Math.max(800, Math.min((w - scaledBaseSize) * 100, playerXScaled));
    }

    /**
     * Updates invader rack movement.
     *
     * @param w Display width.
     * @param h Display height.
     */
    private void updateInvaders(final int w, final int h) {
        var activeCount = 0;
        var minX = 1000;
        var maxX = -1000;
        for (final var inv : invaders) {
            if (inv.active) {
                activeCount++;
                final var curX = inv.x + rackX;
                if (curX < minX) {
                    minX = curX;
                }
                if (curX + scaledInvaderWidth > maxX) {
                    maxX = curX + scaledInvaderWidth;
                }
                if (inv.y + rackY + scaledInvaderHeight >= h - 12) {
                    registerHit(true);
                    return;
                }
            }
        }
        if (activeCount == 0) {
            initLevel(w, h, false);
            return;
        }
        if (++moveTimer >= Math.max(2, activeCount / 6)) {
            if (rackDir > 0 && maxX >= w - 2) {
                rackDir = -2;
                rackY += Math.max(2, scaledInvaderHeight / 2);
            } else if (rackDir < 0 && minX <= 2) {
                rackDir = 2;
                rackY += Math.max(2, scaledInvaderHeight / 2);
            } else {
                rackX += rackDir;
            }
            moveTimer = 0;
        }
    }

    /**
     * Handles projectile movement and collision detection.
     *
     * @param w Display width.
     * @param h Display height.
     */
    private void updateCombat(final int w, final int h) {
        final var px = playerXScaled / 100;
        final var saucerY = h / 6;
        if (playerShot == null) {
            playerShot = new Projectile(px + (scaledBaseSize / 2), h - 10);
        } else {
            final var nx = playerShot.x;
            final var ny = playerShot.y - 4;
            if (saucerActive && nx >= saucerX && nx <= saucerX + scaledSaucerWidth && ny >= saucerY
                    && ny <= saucerY + scaledInvaderHeight) {
                score += 150;
                saucerActive = false;
                playerShot = null;
                explosions.add(new Explosion(saucerX + (scaledSaucerWidth / 2), saucerY));
            } else if (ny < 10 || checkBunkerCollision(w, h, nx, ny)) {
                playerShot = null;
            } else {
                playerShot = new Projectile(nx, ny);
                for (final var inv : invaders) {
                    if (inv.active && nx >= inv.x + rackX && nx <= inv.x + rackX + scaledInvaderWidth && ny >= inv.y + rackY
                            && ny <= inv.y + rackY + scaledInvaderHeight) {
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
                alienMissiles.add(new Projectile(s.x + rackX + (scaledInvaderWidth / 2), s.y + rackY + scaledInvaderHeight));
            }
        }
        for (var i = 0; i < alienMissiles.size(); i++) {
            final var m = alienMissiles.get(i);
            if (m == null) {
                continue;
            }
            final var ny = m.y + 2;
            if (ny > h || checkBunkerCollision(w, h, m.x, ny)) {
                alienMissiles.set(i, null);
            } else if (ny > h - 10 && Math.abs(m.x - (px + (scaledBaseSize / 2))) < 6) {
                registerHit(false);
                return;
            } else {
                alienMissiles.set(i, new Projectile(m.x, ny));
            }
        }
        alienMissiles.removeIf(m -> m == null);
    }

    /**
     * Checks for collision with bunkers and updates bitmasks upon impact.
     *
     * @param w Display width.
     * @param h Display height.
     * @param x Projectile X.
     * @param y Projectile Y.
     * @return True if hit.
     */
    private boolean checkBunkerCollision(final int w, final int h, final int x, final int y) {
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
     * Updates mystery saucer position and spawn timer.
     *
     * @param w Display width.
     */
    private void updateSaucer(final int w) {
        if (!saucerActive) {
            if (++saucerTimer > (400 + random.nextInt(800))) {
                saucerActive = true;
                saucerX = -scaledSaucerWidth;
            }
        } else {
            saucerX += 1;
            if (saucerX > w) {
                saucerActive = false;
                saucerTimer = 0;
            }
        }
    }

    /**
     * Renders centered text with a cleared background rectangle.
     *
     * @param g Graphics context.
     * @param text String to draw.
     * @param w Display width.
     * @param y Y position.
     * @param color Text color.
     */
    private void drawCenteredText(final Graphics2D g, final String text, final int w, final int y, final Color color) {
        final var fm = g.getFontMetrics();
        final var x = (w - fm.stringWidth(text)) / 2;
        g.setColor(Color.BLACK);
        g.fillRect(x - 2, y - fm.getAscent(), fm.stringWidth(text) + 4, fm.getHeight());
        g.setColor(color);
        g.drawString(text, x, y);
    }

    /**
     * Main rendering loop optimized with dirty rectangle tracking.
     */
    private void render() {
        final var w = getWidth();
        final var h = getHeight();
        final var px = playerXScaled / 100;
        final var g = getG2d();

        // Build dirty tracking bounding box union or clear/draw optimized region
        final var dirtyRect = new Rectangle(0, 0, w, h);

        // If game is active, we can constrain clear/redraw optimizations, but for general compatibility 
        // we clear the bounding box context before selective redraw components.
        g.setBackground(Color.BLACK);
        g.clearRect(dirtyRect.x, dirtyRect.y, dirtyRect.width, dirtyRect.height);

        g.setFont(new Font("Monospaced", Font.PLAIN, Math.max(8, (int) (9 * spriteScale))));
        g.setColor(Color.WHITE);
        g.drawString(String.format("%04d", score), 2, 10);
        g.setColor(Color.GREEN);
        for (var i = 0; i < lives; i++) {
            g.fillRect(w - (i * 6) - 7, h - 4, 4, 3);
        }

        if (saucerActive) {
            g.drawImage(saucerSprite, saucerX, h / 6, null);
        }

        g.setColor(Color.CYAN);
        final var spacing = w / 3;
        for (var i = 0; i < 3; i++) {
            final var bx = (spacing / 2) + (i * spacing) - 4;
            for (var r = 0; r < 3; r++) {
                for (var c = 0; c < 7; c++) {
                    if (((bunkers[i][r] >> (6 - c)) & 1) == 1) {
                        g.fillRect(bx + c, (h - 24) + (r * 2), 1, 2);
                    }
                }
            }
        }

        for (final var inv : invaders) {
            if (!inv.active) {
                continue;
            }
            final var sprite = (inv.type == 0) ? invaderType0Sprite : (inv.type == 1 ? invaderType1Sprite : invaderType2Sprite);
            g.drawImage(sprite, inv.x + rackX, inv.y + rackY, null);
        }

        for (final var exp : explosions) {
            g.drawImage(explosionSprite, exp.x, exp.y, null);
        }

        g.setColor(Color.WHITE);
        if (playerShot != null) {
            g.fillRect(playerShot.x, playerShot.y, Math.max(1, (int) (1 * spriteScale)), Math.max(3, (int) (3 * spriteScale)));
        }
        g.setColor(Color.RED);
        for (final var m : alienMissiles) {
            g.fillRect(m.x, m.y, Math.max(1, (int) (1 * spriteScale)), Math.max(3, (int) (3 * spriteScale)));
        }

        if (null == gameState) {
            g.drawImage(playerSprite, px, h - 8, null);
        } else {
            switch (gameState) {
                case EXPLODING -> {
                    g.setColor(Color.WHITE);
                    for (var i = 0; i < 20; i++) {
                        g.fillRect(px + random.nextInt(15) - 7, h - 5 + random.nextInt(10) - 5, 1, 1);
                    }
                }
                case GAME_OVER ->
                    drawCenteredText(g, "GAME OVER", w, h / 2, Color.RED);
                default ->
                    g.drawImage(playerSprite, px, h - 8, null);
            }
        }

        // Push only the dirty sub-region or entire image based on hardware backing
        getDisplay().drawImage(getImage(), dirtyRect.x, dirtyRect.y, dirtyRect.width, dirtyRect.height);
    }

    /**
     * Main game loop execution refactored for Base.
     *
     * @return Exit code.
     * @throws Exception Hardware or timing exception.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        final var w = getWidth();
        final var h = getHeight();
        final var targetFps = getFps();
        initLevel(w, h, true);

        try {
            while (isRunning() && !Thread.currentThread().isInterrupted()) {
                updateLogic(w, h);
                render();

                if (gameState == State.GAME_OVER) {
                    try {
                        TimeUnit.SECONDS.sleep(3);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running = false;
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(1000 / targetFps);
                } catch (final InterruptedException e) {
                    log.info("Game loop interrupted, shutting down...");
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Main entry point using picocli.
     *
     * @param args Argument list.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new SpaceInvaders()).execute(args));
    }
}
