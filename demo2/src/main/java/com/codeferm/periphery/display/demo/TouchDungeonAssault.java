/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.PassiveSpeaker;
import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.device.Xpt2046;
import com.codeferm.periphery.sound.SoundManager;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Touch-controlled, Wolfenstein-style raycasting shooter with graphical controls and optional PWM sound support.
 *
 * <p>
 * Uses the Castle Ashlar DDA raycaster for walls and adds depth-tested billboard monsters, shooting, enemy pursuit, health, score
 * and waves.</p>
 *
 * @author Steven P. Goldsmith
 * @version 1.2.0-SNAPSHOT
 * @since 1.0.0
 */
@Slf4j
@Command(name = "TouchDungeonAssault", mixinStandardHelpOptions = true,
        version = "1.2.0-SNAPSHOT",
        description = "Touch-controlled raycasting shooter with graphical controls and optional sound")
public class TouchDungeonAssault extends Base implements Callable<Integer> {

    /**
     * Picocli command spec for inspecting parse results.
     */
    @Spec
    private CommandSpec spec;

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
    @Option(names = {"-sd", "--sound-device"}, description = "Sound PWM chip index or GPIO chip path.", defaultValue
            = "/dev/gpiochip0")
    private String soundDevice;

    /**
     * Sound PWM channel or GPIO line index.
     */
    @Option(names = {"-sc", "--sound-channel"}, description = "Sound PWM channel or GPIO line index.", defaultValue = "80")
    private int soundChannel;

    /**
     * Number of bytes allocated per pixel for RGB565 format.
     */
    private static final int BYTES_PER_PIXEL = 2;

    /**
     * Map layout grid representing walls (1) and open space (0).
     */
    private static final int[][] MAP = {
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1},
        {1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1},
        {1, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1},
        {1, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 1},
        {1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 0, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1},
        {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}
    };

    /**
     * Field of view projection plane constant.
     */
    private static final double FOV_PLANE = 0.66;

    /**
     * Player movement speed.
     */
    private static final double MOVE_SPEED = 2.0;

    /**
     * Player rotation speed.
     */
    private static final double ROTATE_SPEED = 2.5;

    /**
     * Monster tracking speed.
     */
    private static final double MONSTER_SPEED = 0.48;

    /**
     * Firing weapon cooldown interval in seconds.
     */
    private static final double FIRE_INTERVAL = 0.32;

    /**
     * Height reserved for the top HUD telemetry strip.
     */
    private static final int TOP_HUD_HEIGHT = 14;

    /**
     * Height reserved for the bottom graphical control bar.
     */
    private static final int CONTROL_BAR_HEIGHT = 32;

    /**
     * Player state container.
     */
    private static final class Player {

        double x = 1.5;
        double y = 1.5;
        double dirX = 1.0;
        double dirY = 0.0;
        double planeX = 0.0;
        double planeY = FOV_PLANE;
        double health = 100.0;
        int score;
        int wave = 1;
        int kills;
        double fireCooldown;
        double damageFlash;
        double muzzleFlash;
        boolean gameOver;
    }

    /**
     * Monster entity state container.
     */
    private static final class Monster {

        double x;
        double y;
        double health = 2.0;
        double attackCooldown;
        double animation;
        boolean alive = true;

        Monster(final double x, final double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Projected sprite record for depth sorting.
     */
    private record SpriteProjection(Monster monster, double depth, int screenX,
            int height, int width, int top, int left) {

    }

    private final Random random = new Random();
    private final Player player = new Player();
    private final List<Monster> monsters = new ArrayList<>();
    private int width;
    private int height;
    private int viewHeight;
    private BufferedImage frame;
    private byte[] frameBuffer;
    private int[] pixels;
    private double[] zBuffer;
    private SoundManager soundManager;
    private boolean firePressed;
    private boolean turnLeft;
    private boolean turnRight;
    private boolean moveForward;
    private boolean moveBackward;
    private boolean gameStarted;

    /**
     * Packs RGB color components into an integer.
     *
     * @param r Red component (0-255).
     * @param g Green component (0-255).
     * @param b Blue component (0-255).
     * @return Packed RGB integer value.
     */
    private static int rgb(final int r, final int g, final int b) {
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Checks if coordinates hit a wall.
     *
     * @param x X coordinate position.
     * @param y Y coordinate position.
     * @return True if a wall is present.
     */
    private boolean wall(final double x, final double y) {
        final var mx = (int) x;
        final var my = (int) y;
        return mx < 0 || my < 0 || mx >= MAP.length || my >= MAP[0].length
                || MAP[mx][my] != 0;
    }

    /**
     * Checks if a circular position is clear of walls.
     *
     * @param x X coordinate position.
     * @param y Y coordinate position.
     * @return True if position is clear.
     */
    private boolean clearPosition(final double x, final double y) {
        final double radius = 0.20;
        return !wall(x - radius, y - radius) && !wall(x + radius, y - radius)
                && !wall(x - radius, y + radius) && !wall(x + radius, y + radius);
    }

    /**
     * Moves the player with collision detection.
     *
     * @param dx Delta X movement.
     * @param dy Delta Y movement.
     */
    private void movePlayer(final double dx, final double dy) {
        if (clearPosition(player.x + dx, player.y)) {
            player.x += dx;
        }
        if (clearPosition(player.x, player.y + dy)) {
            player.y += dy;
        }
    }

    /**
     * Rotates player orientation angle.
     *
     * @param angle Rotation angle step in radians.
     */
    private void rotate(final double angle) {
        final var oldDirX = player.dirX;
        player.dirX = player.dirX * Math.cos(angle) - player.dirY * Math.sin(angle);
        player.dirY = oldDirX * Math.sin(angle) + player.dirY * Math.cos(angle);
        final var oldPlaneX = player.planeX;
        player.planeX = player.planeX * Math.cos(angle) - player.planeY * Math.sin(angle);
        player.planeY = oldPlaneX * Math.sin(angle) + player.planeY * Math.cos(angle);
    }

    /**
     * Spawns a new wave of monsters randomly across the map.
     */
    private void spawnWave() {
        monsters.clear();
        final var count = Math.min(3 + player.wave * 2, 18);
        for (var i = 0; i < count; i++) {
            for (var attempt = 0; attempt < 100; attempt++) {
                final var x = 1 + random.nextInt(MAP.length - 2);
                final var y = 1 + random.nextInt(MAP[0].length - 2);
                final var dx = x + 0.5 - player.x;
                final var dy = y + 0.5 - player.y;
                if (MAP[x][y] == 0 && dx * dx + dy * dy > 9.0
                        && monsters.stream().noneMatch(m -> Math.hypot(m.x - (x + 0.5), m.y - (y + 0.5)) < 1.0)) {
                    monsters.add(new Monster(x + 0.5, y + 0.5));
                    break;
                }
            }
        }
    }

    /**
     * Initializes or resets player and monster states for a new game session.
     */
    private void startGame() {
        player.x = 1.5;
        player.y = 1.5;
        player.dirX = 1.0;
        player.dirY = 0.0;
        player.planeX = 0.0;
        player.planeY = FOV_PLANE;
        player.health = 100.0;
        player.score = 0;
        player.wave = 1;
        player.kills = 0;
        player.gameOver = false;
        player.fireCooldown = 0;
        player.damageFlash = 0;
        spawnWave();
        gameStarted = true;
        if (soundManager != null) {
            soundManager.playGameStart();
        }
    }

    /**
     * Fires weapon and checks for monster target hits.
     */
    private void fire() {
        if (player.fireCooldown > 0 || player.gameOver) {
            return;
        }
        player.fireCooldown = FIRE_INTERVAL;
        player.muzzleFlash = 0.10;
        if (soundManager != null) {
            soundManager.playFire();
        }

        Monster target = null;
        double bestDistance = Double.MAX_VALUE;
        for (final var monster : monsters) {
            if (!monster.alive) {
                continue;
            }
            final var dx = monster.x - player.x;
            final var dy = monster.y - player.y;
            final var distance = Math.hypot(dx, dy);
            final var forward = dx * player.dirX + dy * player.dirY;
            final var lateral = Math.abs(dx * player.dirY - dy * player.dirX);
            if (forward > 0 && lateral < Math.max(0.22, forward * 0.13)
                    && distance < bestDistance && hasLineOfSight(monster.x, monster.y)) {
                target = monster;
                bestDistance = distance;
            }
        }
        if (target != null) {
            target.health -= 1.0;
            if (target.health <= 0) {
                target.alive = false;
                player.score += 100;
                player.kills++;
            }
        }
    }

    /**
     * Checks clear line of sight to target coordinates.
     *
     * @param tx Target X coordinate.
     * @param ty Target Y coordinate.
     * @return True if unobstructed.
     */
    private boolean hasLineOfSight(final double tx, final double ty) {
        final var dx = tx - player.x;
        final var dy = ty - player.y;
        final var distance = Math.hypot(dx, dy);
        final var steps = Math.max(1, (int) (distance * 12));
        for (var i = 1; i < steps; i++) {
            final var t = i / (double) steps;
            if (wall(player.x + dx * t, player.y + dy * t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Updates game physics, movement, inputs, and enemy AI per time step.
     *
     * @param dt Delta time elapsed in seconds.
     */
    private void update(final double dt) {
        if (player.gameOver) {
            return;
        }
        if (turnLeft) {
            rotate(-ROTATE_SPEED * dt);
        }
        if (turnRight) {
            rotate(ROTATE_SPEED * dt);
        }
        if (moveForward) {
            movePlayer(player.dirX * MOVE_SPEED * dt, player.dirY * MOVE_SPEED * dt);
        }
        if (moveBackward) {
            movePlayer(-player.dirX * MOVE_SPEED * dt, -player.dirY * MOVE_SPEED * dt);
        }
        if (firePressed) {
            fire();
        }

        player.fireCooldown = Math.max(0, player.fireCooldown - dt);
        player.muzzleFlash = Math.max(0, player.muzzleFlash - dt);
        player.damageFlash = Math.max(0, player.damageFlash - dt);

        for (final var monster : monsters) {
            if (!monster.alive) {
                continue;
            }
            monster.animation += dt;
            monster.attackCooldown = Math.max(0, monster.attackCooldown - dt);
            final var dx = player.x - monster.x;
            final var dy = player.y - monster.y;
            final var distance = Math.hypot(dx, dy);
            if (distance > 0.72) {
                final var step = MONSTER_SPEED * (1.0 + player.wave * 0.04) * dt;
                final var mx = dx / distance * step;
                final var my = dy / distance * step;
                if (clearPosition(monster.x + mx, monster.y)) {
                    monster.x += mx;
                }
                if (clearPosition(monster.x, monster.y + my)) {
                    monster.y += my;
                }
            } else if (monster.attackCooldown <= 0) {
                player.health -= Math.min(18, 8 + player.wave * 2);
                player.damageFlash = 0.18;
                monster.attackCooldown = 1.0;
                if (soundManager != null) {
                    soundManager.playExplosion();
                }
                if (player.health <= 0) {
                    player.health = 0;
                    player.gameOver = true;
                    if (soundManager != null) {
                        soundManager.playGameOver();
                    }
                }
            }
        }

        monsters.removeIf(m -> !m.alive);
        if (monsters.isEmpty()) {
            player.wave++;
            player.health = Math.min(100, player.health + 20);
            spawnWave();
        }
    }

    /**
     * Renders the raycast 3D walls, ceiling, and floor.
     */
    private void renderWorld() {
        final int ceiling = rgb(30, 32, 43);
        final int floor = rgb(48, 42, 36);
        // Render 3D viewport starting below top HUD strip
        for (var y = TOP_HUD_HEIGHT; y < TOP_HUD_HEIGHT + viewHeight; y++) {
            final var color = y < TOP_HUD_HEIGHT + viewHeight / 2 ? ceiling : floor;
            final var offset = y * width;
            for (var x = 0; x < width; x++) {
                pixels[offset + x] = color;
            }
        }

        for (var x = 0; x < width; x++) {
            final var cameraX = 2.0 * x / width - 1.0;
            final var rayDirX = player.dirX + player.planeX * cameraX;
            final var rayDirY = player.dirY + player.planeY * cameraX;
            var mapX = (int) player.x;
            var mapY = (int) player.y;
            final var deltaX = Math.abs(1.0 / (rayDirX == 0 ? 1e-12 : rayDirX));
            final var deltaY = Math.abs(1.0 / (rayDirY == 0 ? 1e-12 : rayDirY));
            int stepX;
            int stepY;
            double sideX;
            double sideY;
            if (rayDirX < 0) {
                stepX = -1;
                sideX = (player.x - mapX) * deltaX;
            } else {
                stepX = 1;
                sideX = (mapX + 1.0 - player.x) * deltaX;
            }
            if (rayDirY < 0) {
                stepY = -1;
                sideY = (player.y - mapY) * deltaY;
            } else {
                stepY = 1;
                sideY = (mapY + 1.0 - player.y) * deltaY;
            }

            int side = 0;
            for (var guard = 0; guard < 64; guard++) {
                if (sideX < sideY) {
                    sideX += deltaX;
                    mapX += stepX;
                    side = 0;
                } else {
                    sideY += deltaY;
                    mapY += stepY;
                    side = 1;
                }
                if (mapX < 0 || mapY < 0 || mapX >= MAP.length || mapY >= MAP[0].length
                        || MAP[mapX][mapY] != 0) {
                    break;
                }
            }
            var distance = side == 0 ? sideX - deltaX : sideY - deltaY;
            distance = Math.max(0.05, distance);
            zBuffer[x] = distance;
            final var lineHeight = (int) (viewHeight / distance);
            final var start = Math.max(TOP_HUD_HEIGHT, TOP_HUD_HEIGHT + viewHeight / 2 - lineHeight / 2);
            final var end = Math.min(TOP_HUD_HEIGHT + viewHeight - 1, TOP_HUD_HEIGHT + viewHeight / 2 + lineHeight / 2);

            var wallX = side == 0
                    ? player.y + distance * rayDirY
                    : player.x + distance * rayDirX;
            wallX -= Math.floor(wallX);
            final var texX = (int) (wallX * 64);
            final var seed = mapX * 59 + mapY * 43;
            var shade = Math.max(0.22, 1.0 / (1.0 + distance * 0.18));
            if (side == 1) {
                shade *= 0.72;
            }
            for (var y = start; y <= end; y++) {
                final var texY = (int) ((y - (TOP_HUD_HEIGHT + viewHeight / 2.0 - lineHeight / 2.0))
                        * 64.0 / Math.max(1, lineHeight));
                final var row = texY / 12;
                final var mortar = texY % 12 < 2 || ((texX + ((seed ^ row * 31) & 15)) % 24 < 2);
                final var hash = (seed ^ row ^ (texX / 24)) & 3;
                var r = mortar ? 76 : switch (hash) {
                    case 0 ->
                        130;
                    case 1 ->
                        166;
                    case 2 ->
                        145;
                    default ->
                        112;
                };
                var g = mortar ? 72 : r - 18;
                var b = mortar ? 68 : r - 34;
                final var grain = (texX ^ texY) & 7;
                r = (int) (Math.max(0, r - grain * 2) * shade);
                g = (int) (Math.max(0, g - grain * 2) * shade);
                b = (int) (Math.max(0, b - grain * 2) * shade);
                pixels[y * width + x] = rgb(r, g, b);
            }
        }
    }

    /**
     * Renders depth-tested billboard monster sprites.
     */
    private void renderMonsters() {
        final List<SpriteProjection> visible = new ArrayList<>();
        final var determinant = player.planeX * player.dirY - player.dirX * player.planeY;
        final var invDet = 1.0 / determinant;
        for (final var monster : monsters) {
            final var spriteX = monster.x - player.x;
            final var spriteY = monster.y - player.y;
            final var transformX = invDet * (player.dirY * spriteX - player.dirX * spriteY);
            final var transformY = invDet * (-player.planeY * spriteX + player.planeX * spriteY);
            if (transformY <= 0.1) {
                continue;
            }
            final var screenX = (int) ((width / 2.0) * (1.0 + transformX / transformY));
            final var spriteHeight = Math.abs((int) (viewHeight / transformY));
            final var spriteWidth = Math.max(1, spriteHeight * 3 / 4);
            final var top = Math.max(TOP_HUD_HEIGHT, TOP_HUD_HEIGHT + viewHeight / 2 - spriteHeight / 2);
            final var left = screenX - spriteWidth / 2;
            visible.add(new SpriteProjection(monster, transformY, screenX,
                    spriteHeight, spriteWidth, top, left));
        }
        visible.sort(Comparator.comparingDouble(SpriteProjection::depth).reversed());
        for (final var sprite : visible) {
            final var right = Math.min(width - 1, sprite.left() + sprite.width());
            final var bottom = Math.min(TOP_HUD_HEIGHT + viewHeight - 1, sprite.top() + sprite.height());
            for (var stripe = Math.max(0, sprite.left()); stripe <= right; stripe++) {
                if (sprite.depth() >= zBuffer[stripe]) {
                    continue;
                }
                final var nx = (stripe - sprite.left()) / (double) sprite.width();
                for (var y = sprite.top(); y <= bottom; y++) {
                    final var ny = (y - sprite.top()) / (double) Math.max(1, sprite.height());
                    final var color = monsterPixel(nx, ny, sprite.monster());
                    if (color != 0) {
                        pixels[y * width + stripe] = color;
                    }
                }
            }
        }
    }

    /**
     * Procedural imp sprite: transparent background, horns, head, eyes, torso and arms. Shape is drawn in normalized 0..1 sprite
     * coordinates.
     *
     * @param x Normalized X coordinate (0 to 1).
     * @param y Normalized Y coordinate (0 to 1).
     * @param monster Monster instance reference.
     * @return Packed RGB pixel color or 0 for transparent.
     */
    private int monsterPixel(final double x, final double y, final Monster monster) {
        final var bob = Math.sin(monster.animation * 7.0) * 0.018;
        final var adjustedY = y - bob;
        final var horn = adjustedY > 0.08 && adjustedY < 0.30
                && ((x > 0.16 && x < 0.34 && adjustedY < 0.28 - (x - 0.16) * 0.8)
                || (x > 0.66 && x < 0.84 && adjustedY < 0.28 - (0.84 - x) * 0.8));
        final var head = ellipse(x, adjustedY, 0.5, 0.31, 0.27, 0.24);
        final var body = ellipse(x, adjustedY, 0.5, 0.69, 0.31, 0.30);
        final var arm = ellipse(x, adjustedY, 0.22, 0.62, 0.13, 0.25)
                || ellipse(x, adjustedY, 0.78, 0.62, 0.13, 0.25);
        if (horn) {
            return rgb(105, 35, 25);
        }
        if (head) {
            if (ellipse(x, adjustedY, 0.39, 0.32, 0.045, 0.035)
                    || ellipse(x, adjustedY, 0.61, 0.32, 0.045, 0.035)) {
                return rgb(255, 220, 60);
            }
            if (adjustedY > 0.43 && adjustedY < 0.49 && x > 0.39 && x < 0.61) {
                return rgb(35, 12, 12);
            }
            return rgb(155, 48, 36);
        }
        if (body || arm) {
            return rgb(95, 32, 30);
        }
        return 0;
    }

    /**
     * Mathematical ellipse check helper.
     *
     * @param x X point.
     * @param y Y point.
     * @param cx Center X.
     * @param cy Center Y.
     * @param rx Radius X.
     * @param ry Radius Y.
     * @return True if point is within ellipse bounds.
     */
    private boolean ellipse(final double x, final double y, final double cx, final double cy,
            final double rx, final double ry) {
        final var dx = (x - cx) / rx;
        final var dy = (y - cy) / ry;
        return dx * dx + dy * dy <= 1.0;
    }

    /**
     * Renders the top HUD, crosshair, and graphical bottom control buttons.
     */
    private void renderHud() {
        // Clear Top HUD strip background
        for (var y = 0; y < TOP_HUD_HEIGHT; y++) {
            for (var x = 0; x < width; x++) {
                pixels[y * width + x] = rgb(12, 13, 17);
            }
        }
        // Top HUD telemetry: HP, Score, Wave
        drawText(3, TOP_HUD_HEIGHT - 3, "HP " + (int) player.health, rgb(90, 235, 100), 1);
        drawText(width / 2 - 24, TOP_HUD_HEIGHT - 3, "S:" + player.score, rgb(245, 220, 120), 1);
        drawText(width - 32, TOP_HUD_HEIGHT - 3, "W" + player.wave, rgb(230, 150, 90), 1);

        final var cx = width / 2;
        final var cy = TOP_HUD_HEIGHT + viewHeight / 2;
        final var cross = rgb(255, 235, 190);
        for (var i = -3; i <= 3; i++) {
            put(cx + i, cy, cross);
            put(cx, cy + i, cross);
        }

        // Render graphical bottom control bar
        final var barTop = height - CONTROL_BAR_HEIGHT;
        for (var y = barTop; y < height; y++) {
            for (var x = 0; x < width; x++) {
                pixels[y * width + x] = rgb(20, 22, 30);
            }
        }

        // Draw 3 graphical buttons: Left Arrow, Fire, Right Arrow
        final var btnWidth = width / 3;
        for (var i = 0; i < 3; i++) {
            final var bx = i * btnWidth;
            // Button background box
            for (var y = barTop + 2; y < height - 2; y++) {
                for (var x = bx + 2; x < bx + btnWidth - 2; x++) {
                    pixels[y * width + x] = i == 1 ? rgb(160, 45, 40) : rgb(35, 45, 60);
                }
            }
        }

        // Draw graphical arrow/text icons inside buttons
        drawText(btnWidth / 2 - 4, barTop + 12, "<", rgb(255, 255, 255), 1);
        drawText(btnWidth + btnWidth / 2 - 12, barTop + 12, "FIRE", rgb(255, 255, 255), 1);
        drawText(btnWidth * 2 + btnWidth / 2 - 4, barTop + 12, ">", rgb(255, 255, 255), 1);

        if (player.muzzleFlash > 0) {
            final var radius = Math.max(3, width / 12);
            for (var y = -radius; y <= radius; y++) {
                for (var x = -radius; x <= radius; x++) {
                    if (x * x + y * y <= radius * radius && Math.abs(x + y) % 3 == 0) {
                        put(cx + x, cy + y, rgb(255, 190, 65));
                    }
                }
            }
        }
        if (player.damageFlash > 0) {
            for (var x = 0; x < width; x++) {
                put(x, TOP_HUD_HEIGHT, rgb(255, 40, 30));
                put(x, TOP_HUD_HEIGHT + viewHeight - 1, rgb(255, 40, 30));
            }
        }
        if (player.gameOver) {
            drawText(width / 2 - 27, TOP_HUD_HEIGHT + viewHeight / 2 - 5, "GAME OVER",
                    rgb(255, 65, 55), 1);
            drawText(width / 2 - 29, TOP_HUD_HEIGHT + viewHeight / 2 + 8, "TAP TO RETRY",
                    rgb(255, 235, 180), 1);
        }
    }

    /**
     * Sets pixel color safely within screen bounds.
     *
     * @param x X pixel coordinate.
     * @param y Y pixel coordinate.
     * @param color Packed RGB color integer.
     */
    private void put(final int x, final int y, final int color) {
        if (x >= 0 && y >= 0 && x < width && y < height) {
            pixels[y * width + x] = color;
        }
    }

    /**
     * Draws text using a tiny 3x5 bitmap font.
     *
     * @param x X coordinate.
     * @param y Y coordinate.
     * @param text String text to draw.
     * @param color Text color.
     * @param scale Pixel scale factor.
     */
    private void drawText(final int x, final int y, final String text, final int color, final int scale) {
        for (var i = 0; i < text.length(); i++) {
            final var glyph = glyph(text.charAt(i));
            for (var row = 0; row < 5; row++) {
                for (var col = 0; col < 3; col++) {
                    if ((glyph[row] & (1 << (2 - col))) != 0) {
                        for (var sy = 0; sy < scale; sy++) {
                            for (var sx = 0; sx < scale; sx++) {
                                put(x + i * 4 * scale + col * scale + sx,
                                        y - 4 * scale + row * scale + sy, color);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Bitmap font glyph data lookup.
     *
     * @param c Character to look up.
     * @return 5-row integer array bitmask.
     */
    private int[] glyph(final char c) {
        return switch (Character.toUpperCase(c)) {
            case '0' ->
                new int[]{7, 5, 5, 5, 7};
            case '1' ->
                new int[]{2, 6, 2, 2, 7};
            case '2' ->
                new int[]{7, 1, 7, 4, 7};
            case '3' ->
                new int[]{7, 1, 7, 1, 7};
            case '4' ->
                new int[]{5, 5, 7, 1, 1};
            case '5' ->
                new int[]{7, 4, 7, 1, 7};
            case '6' ->
                new int[]{7, 4, 7, 5, 7};
            case '7' ->
                new int[]{7, 1, 2, 2, 2};
            case '8' ->
                new int[]{7, 5, 7, 5, 7};
            case '9' ->
                new int[]{7, 5, 7, 1, 7};
            case 'A' ->
                new int[]{2, 5, 7, 5, 5};
            case 'C' ->
                new int[]{7, 4, 4, 4, 7};
            case 'E' ->
                new int[]{7, 4, 6, 4, 7};
            case 'F' ->
                new int[]{7, 4, 6, 4, 4};
            case 'G' ->
                new int[]{7, 4, 5, 5, 7};
            case 'H' ->
                new int[]{5, 5, 7, 5, 5};
            case 'I' ->
                new int[]{7, 2, 2, 2, 7};
            case 'M' ->
                new int[]{5, 7, 7, 5, 5};
            case 'N' ->
                new int[]{5, 7, 7, 7, 5};
            case 'O' ->
                new int[]{7, 5, 5, 5, 7};
            case 'P' ->
                new int[]{7, 5, 7, 4, 4};
            case 'R' ->
                new int[]{6, 5, 6, 5, 5};
            case 'S' ->
                new int[]{7, 4, 7, 1, 7};
            case 'T' ->
                new int[]{7, 2, 2, 2, 2};
            case 'U' ->
                new int[]{5, 5, 5, 5, 7};
            case 'W' ->
                new int[]{5, 5, 7, 7, 5};
            case '<' ->
                new int[]{2, 4, 7, 4, 2};
            case '>' ->
                new int[]{4, 2, 7, 2, 4};
            default ->
                new int[]{0, 0, 0, 0, 0};
        };
    }

    /**
     * Handles touch input events for player turning, movement, and firing.
     */
    private void handleTouch() {
        final var touch = getTouch();
        if (touch == null || !((Xpt2046) touch).pollEvent(1)) {
            return;
        }
        final var xpt = (Xpt2046) touch;
        if (xpt.isPressed()) {
            final var raw = xpt.readCoordinates();
            final var point = mapTouchToScreen(raw.x(), raw.y());
            final var x = point.x;
            final var y = point.y;
            if (player.gameOver) {
                startGame();
            } else if (y >= height - CONTROL_BAR_HEIGHT) {
                // 3 bottom button zones: Left Arrow, Fire, Right Arrow
                final var section = Math.min(2, x * 3 / width);
                switch (section) {
                    case 0 ->
                        turnLeft = true;
                    case 1 ->
                        firePressed = true;
                    case 2 ->
                        turnRight = true;
                    default -> {
                    }
                }
            } else {
                // Tap and hold middle 3D viewport moves forward
                moveForward = true;
            }
        } else {
            turnLeft = false;
            turnRight = false;
            moveForward = false;
            moveBackward = false;
            firePressed = false;
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
     * Clears the display screen to pitch black upon application exit for clean shutdown.
     *
     * @param display Abstract color display driver instance.
     */
    private void clearScreen(final AbstractColorDisplay display) {
        final var clrWidth = getWidth();
        final var clrHeight = getHeight();
        if (frameBuffer == null || frameBuffer.length != clrWidth * clrHeight * BYTES_PER_PIXEL) {
            frameBuffer = new byte[clrWidth * clrHeight * BYTES_PER_PIXEL];
        }
        try {
            display.setWindow(0, 0, clrWidth, clrHeight);
            MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
            display.writeData(display.getImageSegment());
            log.info("Display screen cleared successfully.");
        } catch (final Exception e) {
            log.error("Error clearing display screen during shutdown", e);
        }
    }

    /**
     * Executes the primary game loop rendering the world, monsters, and HUD, transmitting frames via the robust RGB565 framebuffer
     * copy pipeline to eliminate flicker.
     *
     * @param display Abstract color display driver instance.
     */
    private void runGame(final AbstractColorDisplay display) {
        width = getWidth();
        height = getHeight();
        viewHeight = Math.max(1, height - TOP_HUD_HEIGHT - CONTROL_BAR_HEIGHT);
        frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        zBuffer = new double[width];
        if (frameBuffer == null || frameBuffer.length != width * height * BYTES_PER_PIXEL) {
            frameBuffer = new byte[width * height * BYTES_PER_PIXEL];
        }
        startGame();

        var previous = System.nanoTime();
        final var frameNanos = 1_000_000_000L / Math.max(1, getFps());
        while (isRunning() && !Thread.currentThread().isInterrupted()) {
            final var now = System.nanoTime();
            final var dt = Math.min(0.05, (now - previous) / 1_000_000_000.0);
            previous = now;

            handleTouch();
            update(dt);
            renderWorld();
            renderMonsters();
            renderHud();

            convertArgbToRgb565(frame, frameBuffer);
            display.setWindow(0, 0, width, height);
            MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
            display.writeData(display.getImageSegment());

            final var elapsed = System.nanoTime() - now;
            final var sleep = frameNanos - elapsed;
            if (sleep > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleep);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Initializes optional audio managers and starts game execution.
     *
     * @param display Abstract color display driver instance.
     * @throws Exception if initialization or execution fails.
     */
    private void runDemo(final AbstractColorDisplay display) throws Exception {
        log.info("Dungeon Assault demo started on device {} line {} (sound enabled: {})...",
                getGpioDevice(), getTouchIrqLine(), enableSound);
        if (enableSound) {
            try (final var speaker = new PassiveSpeaker(PwmDeviceFactory.create(soundMode, soundDevice, soundChannel)); final var manager
                    = new SoundManager(speaker)) {
                soundManager = manager;
                runGame(display);
            } finally {
                soundManager = null;
            }
        } else {
            soundManager = null;
            runGame(display);
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
        if (!spec.commandLine().getParseResult().hasMatchedOption("fps")) {
            setFps(20);
        }
        super.call();
        try {
            runDemo(getDisplay());
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
    public static void main(final String... args) {
        System.exit(new CommandLine(new TouchDungeonAssault()).execute(args));
    }
}
