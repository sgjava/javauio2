/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.st7789.demo;

import com.codeferm.periphery.device.St7789;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Space Invaders for ST7789 utilizing dirty-rectangle tracking and partial screen updates over FFM.
 * <p>
 * Instead of repainting and pushing the entire 240x320 framebuffer every frame, this implementation tracks the bounding
 * boxes of moving entities (player, invaders, projectiles, explosions, score areas), restores the background, redraws only
 * the active elements, and pushes only the minimal changed rectangular window(s) to the display via SPI.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 3.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SpaceInvaders", mixinStandardHelpOptions = true, version = "3.0.0-SNAPSHOT",
        description = "ST7789 Space Invaders with dirty rectangle partial updates using FFM")
public class SpaceInvaders extends Base {

    /**
     * Internal game state.
     */
    public enum State {
        PLAYING, EXPLODING, GAME_OVER
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
     * Previous rendered score for dirty tracking.
     */
    private int lastScore = -1;

    /**
     * Remaining player lives.
     */
    private int lives = 3;

    /**
     * Previous rendered lives.
     */
    private int lastLives = -1;

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
     * Previous player X position for dirty clearing.
     */
    private int lastPlayerX = -1;

    /**
     * Player velocity scaled for sub-pixel movement.
     */
    private int playerVelocityScaled = 0;

    private static final int PLAYER_MAX_SPEED_SCALED = 700;
    private static final int PLAYER_ACCELERATION_SCALED = 100;
    private static final int PLAYER_DECELERATION_SCALED = 140;

    /**
     * Timer for AI trajectory changes.
     */
    private int aiDecisionTimer = 0;

    /**
     * AI movement direction (-1 left, 0 stay, 1 right).
     */
    private int aiDirection = 0;

    /**
     * Active player projectile.
     */
    private Projectile playerShot = null;

    /**
     * Previous player projectile position for dirty clearing.
     */
    private Projectile lastPlayerShot = null;

    /**
     * List of active alien projectiles.
     */
    private final List<Projectile> alienMissiles = new ArrayList<>();

    /**
     * List of previous alien projectiles for dirty clearing.
     */
    private final List<Projectile> lastAlienMissiles = new ArrayList<>();

    /**
     * List of active invaders.
     */
    private final List<Invader> invaders = new ArrayList<>();

    /**
     * List of active particle explosions.
     */
    private final List<Explosion> explosions = new ArrayList<>();

    /**
     * Bunker bitmasks [bunker index][row] scaled for 240x320 (16 columns wide, 8 rows high).
     */
    private final int[][] bunkers = new int[4][8];

    /**
     * X offset of the invader rack.
     */
    private int rackX;

    /**
     * Previous rack X offset.
     */
    private int lastRackX;

    /**
     * Y offset of the invader rack.
     */
    private int rackY;

    /**
     * Previous rack Y offset.
     */
    private int lastRackY;

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
    private int saucerX = -30;

    /**
     * Previous saucer X position.
     */
    private int lastSaucerX = -30;

    /**
     * Timer for saucer spawn intervals.
     */
    private int saucerTimer = 0;

    /**
     * Saucer activity flag.
     */
    private boolean saucerActive = false;

    /**
     * Previous saucer activity flag.
     */
    private boolean lastSaucerActive = false;

    /**
     * Arcade accurate 16x11 Saucer sprite.
     */
    private static final int[] SAUCER_BITS = {
        0x03C0, 0x0FF0, 0x3FF3, 0x6B6B, 0x7FF7, 0x1FF8, 0x07E0
    };

    /**
     * Arcade accurate Invader sprites.
     */
    private static final int[] INVADER_TYPE0_FRAME1 = {0x0180, 0x03C0, 0x07E0, 0x0BD0, 0x0FF0, 0x0180, 0x0240, 0x0420};
    private static final int[] INVADER_TYPE0_FRAME2 = {0x0180, 0x03C0, 0x07E0, 0x0BD0, 0x0FF0, 0x0180, 0x0420, 0x0240};
    private static final int[] INVADER_TYPE1_FRAME1 = {0x03C0, 0x07E0, 0x0FF0, 0x0DB0, 0x0FF0, 0x0380, 0x0440, 0x0880};
    private static final int[] INVADER_TYPE1_FRAME2 = {0x03C0, 0x07E0, 0x0FF0, 0x0DB0, 0x0FF0, 0x0380, 0x0880, 0x0440};
    private static final int[] INVADER_TYPE2_FRAME1 = {0x0180, 0x0BD0, 0x0DB0, 0x0FF0, 0x07E0, 0x0380, 0x0440, 0x0280};
    private static final int[] INVADER_TYPE2_FRAME2 = {0x0180, 0x0BD0, 0x0DB0, 0x0FF0, 0x07E0, 0x0380, 0x0280, 0x0440};

    /**
     * Animation frame toggle for invaders.
     */
    private boolean invaderAnimFrame = false;

    public record Projectile(int x, int y) {}

    public static class Invader {
        public int x, y, type;
        public boolean active;
        public Invader(final int x, final int y, final int type, final boolean active) {
            this.x = x; this.y = y; this.type = type; this.active = active;
        }
    }

    public static class Explosion {
        public int x, y, timer = 10;
        public Explosion(final int x, final int y) { this.x = x; this.y = y; }
    }

    public final void initLevel(final int w, final int h, final boolean fullReset) {
        if (fullReset) { score = 0; lives = 3; }
        playerXScaled = (w / 2) * 100;
        playerVelocityScaled = 0;
        aiDirection = 0;
        playerShot = null;
        lastPlayerShot = null;
        alienMissiles.clear();
        lastAlienMissiles.clear();
        invaders.clear();
        explosions.clear();
        hitLock = false;
        saucerActive = false;
        lastSaucerActive = false;
        saucerTimer = 0;

        rackX = 24; lastRackX = rackX;
        rackY = 45; lastRackY = rackY;

        for (var row = 0; row < 5; row++) {
            for (var col = 0; col < 11; col++) {
                invaders.add(new Invader(col * 18, row * 14, (row == 0) ? 0 : (row < 3 ? 1 : 2), true));
            }
        }
        for (var i = 0; i < 4; i++) {
            bunkers[i][0] = 0x0FF0; bunkers[i][1] = 0x3FFC; bunkers[i][2] = 0x7FFE;
            bunkers[i][3] = 0xFFFF; bunkers[i][4] = 0xFFFF; bunkers[i][5] = 0xC3C3;
            bunkers[i][6] = 0x8181; bunkers[i][7] = 0x0000;
        }

        final var g = getG2d();
        g.setBackground(Color.BLACK);
        g.clearRect(0, 0, w, h);
        renderFullStaticElements(g, w, h);
        getLcd().drawImage(getImage());
    }

    private void renderFullStaticElements(final Graphics2D g, final int w, final int h) {
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.setColor(Color.WHITE);
        g.drawString("SCORE", 15, 18);
        g.drawString("LIVES", w - 75, 18);
        g.setColor(Color.GREEN);
        final var spacing = w / 4;
        for (var i = 0; i < 4; i++) {
            final var bx = (spacing / 2) + (i * spacing) - 16;
            for (var r = 0; r < 8; r++) {
                for (var c = 0; c < 16; c++) {
                    if (((bunkers[i][r] >> (15 - c)) & 1) == 1) {
                        g.fillRect(bx + c, (h - 55) + (r * 2), 1, 2);
                    }
                }
            }
        }
    }

    private void registerHit(final boolean isLanding) {
        if (!hitLock) {
            hitLock = true;
            lives = isLanding ? 0 : lives - 1;
            playerExplosionTimer = 60;
            gameState = State.EXPLODING;
            playerShot = null;
            alienMissiles.clear();
        }
    }

    private void updateLogic(final int w, final int h) {
        if (gameState == State.EXPLODING) {
            if (--playerExplosionTimer <= 0) {
                if (lives > 0) { initLevel(w, h, false); gameState = State.PLAYING; } else { gameState = State.GAME_OVER; }
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

    private void updateAI(final int w, final int h) {
        if (--aiDecisionTimer <= 0) {
            aiDecisionTimer = 6;
            final var currentX = playerXScaled / 100;
            var desiredDirection = 0;
            Projectile threat = null;
            for (final var m : alienMissiles) {
                if (Math.abs(m.x - currentX) < 24 && m.y > h - 90) {
                    if (threat == null || m.y > threat.y) threat = m;
                }
            }
            if (threat != null) {
                if (currentX < 25) desiredDirection = 1;
                else if (currentX > w - 25) desiredDirection = -1;
                else desiredDirection = (threat.x < currentX) ? 1 : -1;
            } else {
                Invader target = null;
                var bestDistance = Integer.MAX_VALUE;
                for (final var inv : invaders) {
                    if (inv.active) {
                        final var dist = Math.abs((inv.x + rackX + 6) - currentX);
                        if (dist < bestDistance) { bestDistance = dist; target = inv; }
                    }
                }
                if (target != null) {
                    final var delta = (target.x + rackX + 6) - currentX;
                    if (delta > 5) desiredDirection = 1;
                    else if (delta < -5) desiredDirection = -1;
                }
            }
            aiDirection = desiredDirection;
        }
        final var desiredVelocity = aiDirection * PLAYER_MAX_SPEED_SCALED;
        if (playerVelocityScaled < desiredVelocity) {
            playerVelocityScaled = Math.min(playerVelocityScaled + PLAYER_ACCELERATION_SCALED, desiredVelocity);
        } else if (playerVelocityScaled > desiredVelocity) {
            playerVelocityScaled = Math.max(playerVelocityScaled - PLAYER_DECELERATION_SCALED, desiredVelocity);
        }
        playerXScaled += playerVelocityScaled;
        playerXScaled = Math.max(1200, Math.min((w - 12) * 100, playerXScaled));
    }

    private void updateInvaders(final int w, final int h) {
        var activeCount = 0; var minX = 1000; var maxX = -1000;
        for (final var inv : invaders) {
            if (inv.active) {
                activeCount++;
                final var curX = inv.x + rackX;
                if (curX < minX) minX = curX;
                if (curX + 12 > maxX) maxX = curX + 12;
                if (inv.y + rackY + 8 >= h - 30) { registerHit(true); return; }
            }
        }
        if (activeCount == 0) { initLevel(w, h, false); return; }
        if (++moveTimer >= Math.max(3, activeCount / 3)) {
            invaderAnimFrame = !invaderAnimFrame; lastRackX = rackX; lastRackY = rackY;
            if (rackDir > 0 && maxX >= w - 10) { rackDir = -2; rackY += 6; }
            else if (rackDir < 0 && minX <= 10) { rackDir = 2; rackY += 6; }
            else rackX += rackDir;
            moveTimer = 0;
        }
    }

    private void updateCombat(final int w, final int h) {
        final var px = playerXScaled / 100;
        lastPlayerShot = playerShot;
        if (playerShot == null) playerShot = new Projectile(px, h - 22);
        else {
            final var ny = playerShot.y - 6;
            if (ny < 20 || checkBunkerCollision(w, h, playerShot.x, ny)) playerShot = null;
            else {
                playerShot = new Projectile(playerShot.x, ny);
                for (final var inv : invaders) {
                    if (inv.active && playerShot.x >= inv.x + rackX && playerShot.x <= inv.x + rackX + 12 && ny >= inv.y + rackY && ny <= inv.y + rackY + 8) {
                        inv.active = false; score += (inv.type == 0 ? 40 : (inv.type == 1 ? 20 : 10));
                        playerShot = null; explosions.add(new Explosion(inv.x + rackX, inv.y + rackY)); break;
                    }
                }
            }
        }
        lastAlienMissiles.clear(); lastAlienMissiles.addAll(alienMissiles);
        if (random.nextInt(35) == 0 && alienMissiles.size() < 4) {
            final var activeOnes = invaders.stream().filter(i -> i.active).toList();
            if (!activeOnes.isEmpty()) {
                final var s = activeOnes.get(random.nextInt(activeOnes.size()));
                alienMissiles.add(new Projectile(s.x + rackX + 6, s.y + rackY + 8));
            }
        }
        for (var i = 0; i < alienMissiles.size(); i++) {
            final var m = alienMissiles.get(i);
            if (m == null) continue;
            final var ny = m.y + 4;
            if (ny > h - 15 || checkBunkerCollision(w, h, m.x, ny)) alienMissiles.set(i, null);
            else if (ny > h - 22 && Math.abs(m.x - px) < 8) { registerHit(false); return; }
            else alienMissiles.set(i, new Projectile(m.x, ny));
        }
        alienMissiles.removeIf(m -> m == null);
    }

    private boolean checkBunkerCollision(final int w, final int h, final int x, final int y) {
        final var bunkerYStart = h - 55;
        if (y < bunkerYStart || y > bunkerYStart + 16) return false;
        final var spacing = w / 4;
        for (var i = 0; i < 4; i++) {
            final var bx = (spacing / 2) + (i * spacing) - 16;
            if (x >= bx && x < bx + 16) {
                final var row = (y - bunkerYStart) / 2;
                if (row >= 0 && row < 8 && ((bunkers[i][row] >> (15 - (x - bx))) & 1) == 1) {
                    bunkers[i][row] &= ~(1 << (15 - (x - bx))); return true;
                }
            }
        }
        return false;
    }

    private void updateSaucer(final int w) {
        lastSaucerX = saucerX; lastSaucerActive = saucerActive;
        if (!saucerActive) { if (++saucerTimer > (500 + random.nextInt(600))) { saucerActive = true; saucerX = -30; } }
        else { saucerX += 2; if (saucerX > w) { saucerActive = false; saucerTimer = 0; } }
    }

    private void render() {
        final var w = getWidth(); final var h = getHeight();
        final var px = playerXScaled / 100; final var g = getG2d();
        final List<Rectangle> dirtyRects = new ArrayList<>();
        if (score != lastScore || lives != lastLives) {
            dirtyRects.add(new Rectangle(15, 20, 80, 20)); dirtyRects.add(new Rectangle(w - 75, 20, 70, 15));
            lastScore = score; lastLives = lives;
        }
        if (saucerActive || lastSaucerActive) dirtyRects.add(new Rectangle(Math.min(saucerX, lastSaucerX) - 2, 28, 20, 12));
        if (rackX != lastRackX || rackY != lastRackY || invaderAnimFrame) {
            dirtyRects.add(new Rectangle(lastRackX - 2, lastRackY - 2, (11 * 18) + 16, (5 * 14) + 16));
            dirtyRects.add(new Rectangle(rackX - 2, rackY - 2, (11 * 18) + 16, (5 * 14) + 16));
            lastRackX = rackX; lastRackY = rackY;
        }
        if (px != lastPlayerX || lastPlayerX == -1) {
            final var minX = Math.min(px, lastPlayerX == -1 ? px : lastPlayerX);
            final var maxX = Math.max(px, lastPlayerX == -1 ? px : lastPlayerX);
            dirtyRects.add(new Rectangle(minX - 16, h - 27, (maxX - minX) + 32, 21)); lastPlayerX = px;
        }
        if (playerShot != null) dirtyRects.add(new Rectangle(playerShot.x() - 1, playerShot.y() - 1, 4, 8));
        if (lastPlayerShot != null) dirtyRects.add(new Rectangle(lastPlayerShot.x() - 1, lastPlayerShot.y() - 1, 4, 8));
        for (final var m : alienMissiles) dirtyRects.add(new Rectangle(m.x() - 1, m.y() - 1, 4, 8));
        for (final var m : lastAlienMissiles) dirtyRects.add(new Rectangle(m.x() - 1, m.y() - 1, 4, 8));
        for (final var exp : explosions) dirtyRects.add(new Rectangle(exp.x - 4, exp.y - 4, 12, 12));
        g.setColor(Color.BLACK);
        for (final var rect : dirtyRects) g.fillRect(rect.x, rect.y, rect.width, rect.height);
        g.setFont(new Font("Monospaced", Font.BOLD, 14)); g.setColor(Color.WHITE); g.drawString(String.format("%04d", score), 15, 34);
        g.setColor(Color.GREEN);
        for (var i = 0; i < lives; i++) { g.fillRect(w - 75 + (i * 20), 24, 12, 6); g.fillRect(w - 75 + (i * 20) + 4, 21, 4, 3); }
        if (saucerActive) {
            g.setColor(Color.RED);
            for (var i = 0; i < 7; i++) { for (var b = 0; b < 16; b++) if (((SAUCER_BITS[i] >> (15 - b)) & 1) == 1) g.fillRect(saucerX + b, 30 + i, 1, 1); }
        }
        for (final var inv : invaders) {
            if (!inv.active) continue;
            g.setColor(inv.type == 0 ? Color.MAGENTA : (inv.type == 1 ? Color.CYAN : Color.YELLOW));
            final int[] bts = switch (inv.type) { case 0 -> invaderAnimFrame ? INVADER_TYPE0_FRAME2 : INVADER_TYPE0_FRAME1; case 1 -> invaderAnimFrame ? INVADER_TYPE1_FRAME2 : INVADER_TYPE1_FRAME1; default -> invaderAnimFrame ? INVADER_TYPE2_FRAME2 : INVADER_TYPE2_FRAME1; };
            for (var i = 0; i < 8; i++) { for (var b = 0; b < 12; b++) if (((bts[i] >> (11 - b)) & 1) == 1) g.fillRect(inv.x + rackX + b, inv.y + rackY + i, 2, 2); }
        }
        for (final var exp : explosions) { g.setColor(Color.ORANGE); g.drawOval(exp.x - 2, exp.y - 2, 8, 8); }
        g.setColor(Color.WHITE);
        if (playerShot != null) g.fillRect(playerShot.x, playerShot.y, 2, 6);
        g.setColor(Color.RED);
        for (final var m : alienMissiles) g.fillRect(m.x, m.y, 2, 6);
        if (gameState == State.EXPLODING) {
            g.setColor(Color.WHITE);
            for (var i = 0; i < 30; i++) g.fillRect(px + random.nextInt(24) - 12, h - 22 + random.nextInt(14) - 7, 2, 2);
        } else if (gameState == State.GAME_OVER) drawCenteredText(g, "GAME OVER", w, h / 2, Color.RED);
        else drawPlayer(g, px, h);
        getLcd().drawImage(getImage());
    }

    private void drawPlayer(final Graphics2D g, final int px, final int h) {
        g.setColor(Color.GREEN); g.fillRect(px - 12, h - 18, 24, 10); g.fillRect(px - 4, h - 24, 8, 6);
    }

    private void drawCenteredText(final Graphics2D g, final String text, final int w, final int y, final Color color) {
        final var fm = g.getFontMetrics(); final var x = (w - fm.stringWidth(text)) / 2;
        g.setColor(Color.BLACK); g.fillRect(x - 4, y - fm.getAscent(), fm.stringWidth(text) + 8, fm.getHeight() + 4);
        g.setColor(color); g.drawString(text, x, y);
    }

    @Override
    public Integer call() throws Exception {
        super.call();
        final var w = getWidth(); final var h = getHeight();
        initLevel(w, h, true);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                updateLogic(w, h); render();
                if (gameState == State.GAME_OVER) { TimeUnit.SECONDS.sleep(3); running = false; }
                TimeUnit.MILLISECONDS.sleep(1000 / getFps());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); running = false; }
        }
        done(); return 0;
    }

    public static void main(final String... args) {
        System.exit(new CommandLine(new SpaceInvaders()).execute(args));
    }
}