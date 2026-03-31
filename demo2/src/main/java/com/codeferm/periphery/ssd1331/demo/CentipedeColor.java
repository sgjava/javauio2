/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Centipede clone.
 * <p>
 * A high-fidelity recreation of the Atari classic logic, optimized for SSD1331 OLED displays. Features include:
 * <ul>
 * <li>Player Area Rebound (y=40 to y=58).</li>
 * <li>Persistent extra head spawning for uncleared bottom zones.</li>
 * <li>Mushroom drop balancing and spider "gardening" logic.</li>
 * </ul>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "CentipedeColor", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class CentipedeColor extends Base {

    /**
     * Random number generator for game events.
     */
    private final Random random = new Random();

    /**
     * Game state definitions.
     */
    private enum GameState {
        PLAYING, GAME_OVER
    }

    /**
     * Current state of the game loop.
     */
    private GameState currentState = GameState.PLAYING;

    /**
     * Display width in pixels.
     */
    private static final int SCREEN_W = 96;
    /**
     * Display height in pixels.
     */
    private static final int SCREEN_H = 64;
    /**
     * Top boundary of the player's movement area.
     */
    private static final int PLAYER_AREA_TOP = 40;
    /**
     * Bottom-most row for centipede movement.
     */
    private static final int BOTTOM_ROW = 58;

    /**
     * Base movement speed of the centipede, scales with wave.
     */
    private float baseCentipedeSpeed = 30.0f;
    /**
     * Current player X coordinate.
     */
    private float playerX;
    /**
     * Current player Y coordinate (fixed).
     */
    private float playerY;
    /**
     * Current player horizontal velocity.
     */
    private float playerVX = 0;

    /**
     * Active player projectile, null if none.
     */
    private Projectile playerShot = null;
    /**
     * List of active mushrooms on the field.
     */
    private final List<Mushroom> mushrooms = new ArrayList<>();
    /**
     * List of active centipede chains (each chain is a list of segments).
     */
    private final List<List<Segment>> centipedeChains = new ArrayList<>();
    /**
     * Active spider entity, null if none.
     */
    private Spider spider = null;

    // Sprites
    private BufferedImage mushSprite, mushDamaged1, mushDamaged2, headSprite, bodySprite, spiderSprite, shooterSprite;

    /**
     * Player's current score.
     */
    private int score = 0;
    /**
     * Remaining player lives.
     */
    private int lives = 5;
    /**
     * Current game wave level.
     */
    private int wave = 1;
    /**
     * Count of centipede segments that reached the bottom to penalize the next wave.
     */
    private int segmentsReachedBottom = 0;

    /**
     * Mushroom entity containing position and health data.
     */
    public static class Mushroom {

        public int x, y, health = 3;

        public Mushroom(final int x, final int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Individual segment of a centipede chain.
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
     * Spider entity that moves erratically and eats mushrooms.
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
     * Player-fired projectile.
     */
    public static class Projectile {

        public float x, y;

        public Projectile(final float x, final float y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Main update loop called once per frame.
     *
     * @param dt Delta time in seconds since last update.
     */
    private void update(final float dt) {
        if (currentState == GameState.GAME_OVER) {
            return;
        }

        if (centipedeChains.isEmpty()) {
            wave++;
            baseCentipedeSpeed += 4.0f;
            spawnCentipede();
            // Implement the swarm penalty: extra heads for each segment that reached the bottom
            for (var i = 0; i < segmentsReachedBottom; i++) {
                spawnExtraHead();
            }
            segmentsReachedBottom = 0;
        }

        updateCombat(dt);
        updateCentipedes(dt);
        updateSpider(dt);

        var targetX = SCREEN_W / 2.0f;
        if (spider != null) {
            targetX = spider.x + 4;
        } else if (!centipedeChains.isEmpty()) {
            Segment lowest = null;
            for (final var chain : centipedeChains) {
                for (final var s : chain) {
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
        if (playerShot == null) {
            playerShot = new Projectile(playerX + 2, playerY - 2);
        }
        checkCollisions();
    }

    /**
     * Updates centipede movement, including wall bouncing and player area rebound.
     *
     * @param dt Delta time.
     */
    private void updateCentipedes(final float dt) {
        for (final var chain : centipedeChains) {
            for (final var s : chain) {
                final var nextX = s.x + (s.dx * baseCentipedeSpeed * dt);
                var turn = (nextX <= 0 && s.dx < 0) || (nextX >= SCREEN_W - 6 && s.dx > 0);

                if (!turn) {
                    for (final var m : mushrooms) {
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
                        if (s.y <= PLAYER_AREA_TOP) {
                            s.isRising = false;
                        }
                    } else {
                        s.y += 6;
                        if (s.y >= BOTTOM_ROW) {
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
     * Spawns a high-speed independent head into the player area.
     */
    private void spawnExtraHead() {
        final var headChain = new ArrayList<Segment>();
        final var s = new Segment(random.nextInt(SCREEN_W - 6), BOTTOM_ROW, true);
        s.isRising = true;
        headChain.add(s);
        centipedeChains.add(headChain);
    }

    /**
     * Handles projectile movement and collision with mushrooms, centipedes, and spiders.
     *
     * @param dt Delta time.
     */
    private void updateCombat(final float dt) {
        if (playerShot == null) {
            return;
        }
        playerShot.y -= 280.0f * dt;
        var hit = false;

        if (spider != null && Math.abs(playerShot.x - (spider.x + 4)) < 6 && Math.abs(playerShot.y - (spider.y + 4)) < 6) {
            spider = null;
            score += 600;
            hit = true;
        }

        if (!hit) {
            final var it = mushrooms.iterator();
            while (it.hasNext()) {
                final var m = it.next();
                if (playerShot.x >= m.x - 1 && playerShot.x <= m.x + 6 && playerShot.y >= m.y && playerShot.y <= m.y + 6) {
                    m.health--;
                    score += 5;
                    hit = true;
                    if (m.health < 0) {
                        it.remove();
                    }
                    break;
                }
            }
        }

        if (!hit) {
            final var newChains = new ArrayList<List<Segment>>();
            final var chainIt = centipedeChains.iterator();
            while (chainIt.hasNext()) {
                final var chain = chainIt.next();
                for (var j = 0; j < chain.size(); j++) {
                    final var s = chain.get(j);
                    if (Math.abs(playerShot.x - (s.x + 3)) < 6 && Math.abs(playerShot.y - (s.y + 3)) < 6) {
                        if (random.nextInt(3) == 0) {
                            mushrooms.add(new Mushroom((int) s.x, (int) s.y));
                        }
                        score += 100;
                        hit = true;
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
                    chainIt.remove();
                }
                if (hit) {
                    break;
                }
            }
            centipedeChains.addAll(newChains);
        }
        if (playerShot != null && (playerShot.y < 0 || hit)) {
            playerShot = null;
        }
    }

    /**
     * Moves the player toward the AI's determined target.
     *
     * @param targetX Coordinate the AI is moving toward.
     * @param dt Delta time.
     */
    private void movePlayer(final float targetX, final float dt) {
        final var diff = targetX - playerX;
        playerVX = (diff > 0) ? 80.0f : -80.0f;
        if (Math.abs(diff) < 2) {
            playerVX = 0;
        }
        playerX += playerVX * dt;
        playerX = Math.max(2, Math.min(SCREEN_W - 8, playerX));
        playerY = SCREEN_H - 8;
    }

    /**
     * Spawns a full 12-segment centipede at the top center.
     */
    private void spawnCentipede() {
        final var startChain = new ArrayList<Segment>();
        final var startX = SCREEN_W / 2.0f;
        for (var i = 0; i < 12; i++) {
            startChain.add(new Segment(startX - (i * 6), 10, i == 0));
        }
        centipedeChains.add(startChain);
    }

    /**
     * Initializes all bitmap sprites for game entities.
     */
    private void initSprites() {
        mushSprite = createBitmap(6, new int[][]{{0, 1, 1, 1, 1, 0}, {1, 1, 2, 2, 1, 1}, {1, 2, 2, 2, 2, 1}, {1, 1, 1, 1, 1, 1}, {0,
            0, 2, 2, 0, 0}, {0, 0, 2, 2, 0, 0}}, Color.GREEN, Color.RED);
        mushDamaged1 = createBitmap(6, new int[][]{{0, 1, 0, 1, 1, 0}, {1, 1, 2, 0, 1, 1}, {1, 0, 2, 2, 0, 1}, {1, 1, 0, 1, 1, 1}, {
            0, 0, 2, 2, 0, 0}, {0, 0, 2, 2, 0, 0}}, Color.GREEN, Color.RED);
        mushDamaged2 = createBitmap(6, new int[][]{{0, 0, 0, 1, 1, 0}, {0, 1, 2, 0, 0, 1}, {0, 0, 0, 2, 0, 0}, {1, 1, 0, 0, 1, 1}, {
            0, 0, 2, 0, 0, 0}, {0, 0, 2, 2, 0, 0}}, Color.YELLOW, Color.RED);
        headSprite = createBitmap(6, new int[][]{{0, 1, 1, 1, 1, 0}, {1, 2, 1, 1, 2, 1}, {1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1}, {0,
            1, 1, 1, 1, 0}, {1, 0, 1, 1, 0, 1}}, Color.GREEN, Color.RED);
        bodySprite = createBitmap(6, new int[][]{{0, 1, 1, 1, 1, 0}, {1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1}, {0,
            1, 1, 1, 1, 0}, {0, 0, 0, 0, 0, 0}}, Color.GREEN, null);
        spiderSprite = createBitmap(8, new int[][]{{1, 0, 1, 0, 0, 1, 0, 1}, {0, 1, 0, 1, 1, 0, 1, 0}, {1, 1, 1, 1, 1, 1, 1, 1}, {0,
            1, 1, 1, 1, 1, 1, 0}, {0, 1, 1, 1, 1, 1, 1, 0}, {1, 1, 1, 1, 1, 1, 1, 1}, {0, 1, 0, 1, 1, 0, 1, 0},
        {1, 0, 1, 0, 0, 1, 0, 1}}, Color.WHITE, null);
        shooterSprite = createBitmap(6, new int[][]{{0, 0, 1, 1, 0, 0}, {0, 1, 1, 1, 1, 0}, {1, 1, 2, 2, 1, 1}, {1, 1, 1, 1, 1, 1},
        {1, 1, 1, 1, 1, 1}, {1, 0, 1, 1, 0, 1}}, Color.WHITE, Color.RED);
    }

    /**
     * Helper to generate a BufferedImage from a small integer array.
     *
     * @param size Width and height.
     * @param data Binary-style data (1 for color1, 2 for color2).
     * @param c1 Primary color.
     * @param c2 Secondary color.
     * @return Generated BufferedImage.
     */
    private BufferedImage createBitmap(final int size, final int[][] data, final Color c1, final Color c2) {
        final var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                if (data[y][x] == 1) {
                    img.setRGB(x, y, c1.getRGB());
                } else if (data[y][x] == 2 && c2 != null) {
                    img.setRGB(x, y, c2.getRGB());
                }
            }
        }
        return img;
    }

    /**
     * Updates spider spawning, movement, and mushroom eating logic.
     *
     * @param dt Delta time.
     */
    private void updateSpider(final float dt) {
        if (spider == null) {
            if (random.nextInt(180) == 0) {
                final var sx = random.nextBoolean() ? -10 : SCREEN_W + 10;
                spider = new Spider(sx, (float) random.nextInt(20) + 32, (sx < 0 ? 1 : -1), 1);
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
        if (spider.y < 32) {
            spider.vy = 1;
        }
        if (spider.y > SCREEN_H - 8) {
            spider.vy = -1;
        }
        mushrooms.removeIf(m -> Math.abs(spider.x - m.x) < 7 && Math.abs(spider.y - m.y) < 7);
        if (spider.x < -40 || spider.x > SCREEN_W + 40) {
            spider = null;
        }
    }

    /**
     * Checks for collision between player and hostile entities.
     */
    private void checkCollisions() {
        if (spider != null && Math.abs(spider.x - playerX) < 5 && Math.abs(spider.y - playerY) < 5) {
            loseLife();
        }
        for (final var chain : centipedeChains) {
            for (final var s : chain) {
                if (Math.abs(s.x - playerX) < 4 && Math.abs(s.y - playerY) < 4) {
                    loseLife();
                    return;
                }
            }
        }
    }

    /**
     * Reduces life count and resets level or ends game.
     */
    private void loseLife() {
        lives--;
        if (lives <= 0) {
            currentState = GameState.GAME_OVER;
        } else {
            initLevel(false);
        }
    }

    /**
     * Initializes level state.
     *
     * @param fullReset reset all state including score and wave.
     */
    public void initLevel(final boolean fullReset) {
        if (fullReset) {
            score = 0;
            lives = 5;
            wave = 1;
            baseCentipedeSpeed = 30.0f;
            currentState = GameState.PLAYING;
            initSprites();
            mushrooms.clear();
        }
        centipedeChains.clear();
        spawnCentipede();
        spider = null;
        playerShot = null;
        segmentsReachedBottom = 0;
        playerX = SCREEN_W / 2.0f;
        playerY = SCREEN_H - 8.0f;
        if (mushrooms.isEmpty()) {
            for (var i = 0; i < 18; i++) {
                mushrooms.add(new Mushroom((random.nextInt(14) * 6) + 6, (random.nextInt(7) * 6) + 12));
            }
        }
    }

    /**
     * Renders the current frame to the OLED display.
     */
    private void render() {
        final var g = getG2d();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, SCREEN_W, SCREEN_H);
        if (currentState == GameState.GAME_OVER) {
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            g.drawString("GAME OVER", 20, 32);
        } else {
            for (final var m : mushrooms) {
                final var b = (m.health >= 3) ? mushSprite : (m.health == 2 ? mushDamaged1 : mushDamaged2);
                g.drawImage(b, m.x, m.y, null);
            }
            for (final var chain : centipedeChains) {
                for (final var s : chain) {
                    g.drawImage(s.isHead ? headSprite : bodySprite, (int) s.x, (int) s.y, null);
                }
            }
            if (spider != null) {
                g.drawImage(spiderSprite, (int) spider.x, (int) spider.y, null);
            }
            g.drawImage(shooterSprite, (int) playerX, (int) playerY, null);
            if (playerShot != null) {
                g.setColor(Color.WHITE);
                g.fillRect((int) playerShot.x, (int) playerShot.y, 1, 3);
            }
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 7));
            g.setColor(Color.CYAN);
            g.drawString(String.format("%05d L:%01d W:%d", score, lives, wave), 2, 7);
        }
        getOled().drawImage(getImage());
    }

    @Override
    public Integer call() throws Exception {
        super.call();
        initLevel(true);
        var lastTime = System.nanoTime();
        try {
            while (isRunning() && !Thread.currentThread().isInterrupted()) {
                final var currentTime = System.nanoTime();
                var deltaTime = (currentTime - lastTime) / 1_000_000_000.0f;
                lastTime = currentTime;
                if (deltaTime > 0.05f) {
                    deltaTime = 0.05f;
                }
                update(deltaTime);
                render();
                if (currentState == GameState.GAME_OVER) {
                    try {
                        TimeUnit.SECONDS.sleep(3);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(1000 / getFps());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Entry point.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new CentipedeColor()).execute(args));
    }
}
