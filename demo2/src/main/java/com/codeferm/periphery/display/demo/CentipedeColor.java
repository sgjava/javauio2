/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

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
 * A high-fidelity recreation of the Atari classic logic, optimized for unified color display hardware with dynamic scaling.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "CentipedeColor", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Centipede clone for color displays")
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
     * Top boundary fraction of the player's movement area.
     */
    private static final float PLAYER_AREA_TOP_RATIO = 0.625f;
    /**
     * Bottom-most row fraction for centipede movement.
     */
    private static final float BOTTOM_ROW_RATIO = 0.906f;

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
     * Scale factor for sprites based on screen resolution (baseline 96x64).
     */
    private float spriteScale = 1.0f;
    private int scaledBaseSize = 6;
    private int scaledSpiderWidth = 8;
    private int scaledSpiderHeight = 8;

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

        final var screenW = getWidth();
        final var playerAreaTop = (int) (getHeight() * PLAYER_AREA_TOP_RATIO);
        final var bottomRow = (int) (getHeight() * BOTTOM_ROW_RATIO);

        if (centipedeChains.isEmpty()) {
            wave++;
            baseCentipedeSpeed += 4.0f;
            spawnCentipede();
            for (var i = 0; i < segmentsReachedBottom; i++) {
                spawnExtraHead();
            }
            segmentsReachedBottom = 0;
        }

        updateCombat(dt);
        updateCentipedes(dt, screenW, playerAreaTop, bottomRow);
        updateSpider(dt);

        var targetX = screenW / 2.0f;
        if (spider != null) {
            targetX = spider.x + (scaledBaseSize / 2.0f);
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
            playerShot = new Projectile(playerX + (scaledBaseSize / 2.0f) - 0.5f, playerY - 2.0f);
        }
        checkCollisions();
    }

    /**
     * Updates centipede movement, including wall bouncing and player area rebound.
     *
     * @param dt Delta time.
     * @param screenW Screen width.
     * @param playerAreaTop Player area top threshold.
     * @param bottomRow Bottom row threshold.
     */
    private void updateCentipedes(final float dt, final int screenW, final int playerAreaTop, final int bottomRow) {
        for (final var chain : centipedeChains) {
            for (final var s : chain) {
                final var nextX = s.x + (s.dx * baseCentipedeSpeed * dt);
                var turn = (nextX <= 0 && s.dx < 0) || (nextX >= screenW - scaledBaseSize && s.dx > 0);

                if (!turn) {
                    for (final var m : mushrooms) {
                        if (Math.abs(nextX - m.x) < scaledBaseSize && Math.abs(s.y - m.y) < scaledBaseSize) {
                            turn = true;
                            break;
                        }
                    }
                }

                if (turn) {
                    s.dx = -s.dx;
                    if (s.isRising) {
                        s.y -= scaledBaseSize;
                        if (s.y <= playerAreaTop) {
                            s.isRising = false;
                        }
                    } else {
                        s.y += scaledBaseSize;
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
     * Spawns a high-speed independent head into the player area.
     */
    private void spawnExtraHead() {
        final var headChain = new ArrayList<Segment>();
        final var bottomRow = (int) (getHeight() * BOTTOM_ROW_RATIO);
        final var s = new Segment(random.nextInt(getWidth() - scaledBaseSize), bottomRow, true);
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

        if (spider != null && Math.abs(playerShot.x - (spider.x + (scaledSpiderWidth / 2.0f))) < scaledBaseSize
                && Math.abs(playerShot.y - (spider.y + (scaledSpiderHeight / 2.0f))) < scaledBaseSize) {
            spider = null;
            score += 600;
            hit = true;
        }

        if (!hit) {
            final var it = mushrooms.iterator();
            while (it.hasNext()) {
                final var m = it.next();
                if (playerShot.x >= m.x - 1 && playerShot.x <= m.x + scaledBaseSize
                        && playerShot.y >= m.y && playerShot.y <= m.y + scaledBaseSize) {
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
                    if (Math.abs(playerShot.x - (s.x + (scaledBaseSize / 2.0f))) < scaledBaseSize
                            && Math.abs(playerShot.y - (s.y + (scaledBaseSize / 2.0f))) < scaledBaseSize) {
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
        playerX = Math.max(2, Math.min(getWidth() - scaledBaseSize - 2, playerX));
        playerY = getHeight() - scaledBaseSize - 2;
    }

    /**
     * Spawns a full 12-segment centipede at the top center.
     */
    private void spawnCentipede() {
        final var startChain = new ArrayList<Segment>();
        final var startX = getWidth() / 2.0f;
        for (var i = 0; i < 12; i++) {
            startChain.add(new Segment(startX - (i * scaledBaseSize), 10.0f + scaledBaseSize, i == 0));
        }
        centipedeChains.add(startChain);
    }

    /**
     * Calculates scaling parameters based on display dimensions.
     */
    private void calculateScaling() {
        final var screenW = getWidth();
        // Base scale targets a 96x64 display. Scales up smoothly for 240x240 or higher.
        spriteScale = Math.max(1.0f, Math.min(screenW / 96.0f, 3.0f));
        scaledBaseSize = Math.max(6, (int) (6 * spriteScale));
        scaledSpiderWidth = Math.max(8, (int) (8 * spriteScale));
        scaledSpiderHeight = Math.max(8, (int) (8 * spriteScale));
    }

    /**
     * Initializes all bitmap sprites for game entities with dynamic scaling.
     */
    private void initSprites() {
        calculateScaling();

        mushSprite = createScaledBitmap(6, 6, new int[][]{{0, 1, 1, 1, 1, 0}, {1, 1, 2, 2, 1, 1}, {1, 2, 2, 2, 2, 1},
        {1, 1, 1, 1, 1, 1}, {0, 0, 2, 2, 0, 0}, {0, 0, 2, 2, 0, 0}}, Color.GREEN, Color.RED);
        mushDamaged1 = createScaledBitmap(6, 6, new int[][]{{0, 1, 0, 1, 1, 0}, {1, 1, 2, 0, 1, 1}, {1, 0, 2, 2, 0, 1},
        {1, 1, 0, 1, 1, 1}, {0, 0, 2, 2, 0, 0}, {0, 0, 2, 2, 0, 0}}, Color.GREEN, Color.RED);
        mushDamaged2 = createScaledBitmap(6, 6, new int[][]{{0, 0, 0, 1, 1, 0}, {0, 1, 2, 0, 0, 1}, {0, 0, 0, 2, 0, 0},
        {1, 1, 0, 0, 1, 1}, {0, 0, 2, 0, 0, 0}, {0, 0, 2, 2, 0, 0}}, Color.YELLOW, Color.RED);
        headSprite = createScaledBitmap(6, 6, new int[][]{{0, 1, 1, 1, 1, 0}, {1, 2, 1, 1, 2, 1}, {1, 1, 1, 1, 1, 1},
        {1, 1, 1, 1, 1, 1}, {0, 1, 1, 1, 1, 0}, {1, 0, 1, 1, 0, 1}}, Color.GREEN, Color.RED);
        bodySprite = createScaledBitmap(6, 6, new int[][]{{0, 1, 1, 1, 1, 0}, {1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1},
        {1, 1, 1, 1, 1, 1}, {0, 1, 1, 1, 1, 0}, {0, 0, 0, 0, 0, 0}}, Color.GREEN, null);
        spiderSprite = createScaledBitmap(8, 8, new int[][]{{1, 0, 1, 0, 0, 1, 0, 1}, {0, 1, 0, 1, 1, 0, 1, 0},
        {1, 1, 1, 1, 1, 1, 1, 1}, {0, 1, 1, 1, 1, 1, 1, 0}, {0, 1, 1, 1, 1, 1, 1, 0}, {1, 1, 1, 1, 1, 1, 1, 1},
        {0, 1, 0, 1, 1, 0, 1, 0}, {1, 0, 1, 0, 0, 1, 0, 1}}, Color.WHITE, null);
        shooterSprite = createScaledBitmap(6, 6, new int[][]{{0, 0, 1, 1, 0, 0}, {0, 1, 1, 1, 1, 0}, {1, 1, 2, 2, 1, 1},
        {1, 1, 1, 1, 1, 1}, {1, 1, 1, 1, 1, 1}, {1, 0, 1, 1, 0, 1}}, Color.WHITE, Color.RED);
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
        final var targetW = (origW == 6) ? scaledBaseSize : scaledSpiderWidth;
        final var targetH = (origH == 6) ? scaledBaseSize : scaledSpiderHeight;
        final var img = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        final var g2d = img.createGraphics();

        for (var y = 0; y < targetH; y++) {
            for (var x = 0; x < targetW; x++) {
                final var srcX = x * origW / targetW;
                final var srcY = y * origH / targetH;
                if (data[srcY][srcX] == 1) {
                    img.setRGB(x, y, c1.getRGB());
                } else if (data[srcY][srcX] == 2 && c2 != null) {
                    img.setRGB(x, y, c2.getRGB());
                }
            }
        }
        g2d.dispose();
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
                final var sx = random.nextBoolean() ? -20.0f : getWidth() + 20.0f;
                spider = new Spider(sx, (float) random.nextInt((int) (getHeight() * 0.3f)) + (getHeight() * 0.4f),
                        (sx < 0 ? 1 : -1), 1);
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
        if (spider.y < getHeight() * PLAYER_AREA_TOP_RATIO) {
            spider.vy = 1;
        }
        if (spider.y > getHeight() - scaledBaseSize - 4) {
            spider.vy = -1;
        }
        mushrooms.removeIf(m -> Math.abs(spider.x - m.x) < scaledSpiderWidth && Math.abs(spider.y - m.y) < scaledSpiderHeight);
        if (spider.x < -40 || spider.x > getWidth() + 40) {
            spider = null;
        }
    }

    /**
     * Checks for collision between player and hostile entities.
     */
    private void checkCollisions() {
        if (spider != null && Math.abs(spider.x - playerX) < scaledBaseSize && Math.abs(spider.y - playerY) < scaledBaseSize) {
            loseLife();
        }
        for (final var chain : centipedeChains) {
            for (final var s : chain) {
                if (Math.abs(s.x - playerX) < scaledBaseSize && Math.abs(s.y - playerY) < scaledBaseSize) {
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
     * Initializes level state and evenly distributes mushrooms across the upper/middle playfield.
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
        playerX = getWidth() / 2.0f;
        playerY = getHeight() - scaledBaseSize - 2.0f;

        if (mushrooms.isEmpty()) {
            final var screenW = getWidth();
            final var playfieldH = (int) (getHeight() * PLAYER_AREA_TOP_RATIO);
            final var cols = Math.max(6, screenW / (scaledBaseSize * 2));
            final var rows = Math.max(4, playfieldH / (scaledBaseSize * 2));

            // Distribute mushrooms dynamically across the full screen width and upper play area height
            for (var r = 0; r < rows; r++) {
                for (var c = 0; c < cols; c++) {
                    if (random.nextFloat() < 0.45f) { // 45% fill density per grid slot
                        final var mx = (c * scaledBaseSize * 2) + random.nextInt(scaledBaseSize);
                        final var my = (r * scaledBaseSize * 2) + (int) (scaledBaseSize * 1.5f) + random.nextInt(scaledBaseSize);
                        if (my < playfieldH) {
                            mushrooms.add(new Mushroom(mx, my));
                        }
                    }
                }
            }
        }
    }

    /**
     * Renders the current frame to the display.
     */
    private void render() {
        final var screenW = getWidth();
        final var screenH = getHeight();
        final var g = getG2d();

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, screenW, screenH);
        if (currentState == GameState.GAME_OVER) {
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(9, (int) (10 * spriteScale))));
            g.drawString("GAME OVER", screenW / 2 - 35, screenH / 2);
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
                g.fillRect((int) playerShot.x, (int) playerShot.y, Math.max(1, (int) (1 * spriteScale)), Math.max(3, (int) (3
                        * spriteScale)));
            }
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(7, (int) (7 * spriteScale))));
            g.setColor(Color.CYAN);
            g.drawString(String.format("%05d L:%01d W:%d", score, lives, wave), 2, Math.max(8, (int) (8 * spriteScale)));
        }
        getDisplay().drawImage(getImage());
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
