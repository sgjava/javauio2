/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import com.codeferm.periphery.device.PassiveSpeaker;
import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.device.Xpt2046;
import com.codeferm.periphery.sound.SoundManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Atari Missile Command style game demo with classic arcade colors and optional sound effects.
 *
 * <p>
 * This version adds the main arcade-game flow: six cities, three firing bases, a moving bomber, normal missiles, smart bombs,
 * splitting MIRVs, expanding interceptor explosions, wave bonuses, progressive difficulty, and optional passive speaker sound
 * effects.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Command(name = "MissileCommandTouch", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT", description
        = "Interactive touch Missile Command game demo with optional sound")
@Slf4j
public class TouchMissileCommand extends Base implements Callable<Integer> {

    private static final int BYTES_PER_PIXEL = 2;
    private static final int INITIAL_AMMO = 10;
    private static final double PLAYER_SPEED = 430.0;
    private static final double EXPLOSION_MAX_RADIUS = 27.0;
    private static final double EXPLOSION_GROW_SPEED = 115.0;
    private static final double EXPLOSION_SHRINK_SPEED = 72.0;
    private static final double MISSILE_MIN_SPEED = 28.0;
    private static final double MISSILE_MAX_SPEED = 58.0;
    private static final double SMART_BOMB_SPEED = 42.0;
    private static final double MIRV_SPEED = 35.0;
    private static final long INITIAL_SPAWN_INTERVAL = 1050L;
    private static final long LEVEL_BREAK_MS = 2200L;
    private static final int NORMAL_MISSILE_SCORE = 100;
    private static final int SMART_BOMB_SCORE = 150;
    private static final int MIRV_SCORE = 200;
    private static final int BOMBER_SCORE = 250;

    @Option(names = {"-es", "--enable-sound"}, description = "Enable sound effects.", defaultValue = "false")
    private boolean enableSound;

    @Option(names = {"-sm", "--sound-mode"}, description = "Sound PWM Mode: HW or SW.", defaultValue = "SW")
    private String soundMode;

    @Option(names = {"-sd", "--sound-device"}, description = "Sound PWM chip index or GPIO chip path.", defaultValue = "/dev/gpiochip0")
    private String soundDevice;

    @Option(names = {"-sc", "--sound-channel"}, description = "Sound PWM channel or GPIO line index.", defaultValue = "80")
    private int soundChannel;

    private byte[] frameBuffer;
    private final Random random = new Random();
    private final List<IncomingMissile> incomingMissiles = new ArrayList<>();
    private final List<Interceptor> interceptors = new ArrayList<>();
    private final List<Explosion> explosions = new ArrayList<>();
    private final List<City> cities = new ArrayList<>();
    private final List<MissileBase> bases = new ArrayList<>();
    private final List<Bomber> bombers = new ArrayList<>();
    private int score;
    private int level = 1;
    private int selectedBase = 1;
    private boolean gameOver;
    private boolean waveComplete;
    private boolean bonusAwarded;
    private long spawnTimer;
    private long waveTimer;
    private long nextWaveTimer;
    private int waveMissilesToSpawn;
    private int waveMissilesSpawned;
    private int waveMissilesDestroyed;
    private long lastFrameNanos;
    private double targetX = -1;
    private double targetY = -1;
    private boolean targetVisible;
    private SoundManager soundManager;

    /**
     * Incoming enemy projectile.
     */
    private static final class IncomingMissile {

        enum Type {
            NORMAL, SMART, MIRV
        }

        double x;
        double y;
        final double startX;
        final double startY;
        final double targetX;
        final double targetY;
        final double vx;
        final double vy;
        final Type type;
        boolean active = true;
        boolean split;

        IncomingMissile(final double startX, final double startY, final double targetX, final double targetY, final double speed,
                final Type type) {
            this.x = startX;
            this.y = startY;
            this.startX = startX;
            this.startY = startY;
            this.targetX = targetX;
            this.targetY = targetY;
            this.type = type;
            final var dx = targetX - startX;
            final var dy = targetY - startY;
            final var distance = Math.max(1.0, Math.hypot(dx, dy));
            vx = dx / distance * speed;
            vy = dy / distance * speed;
        }

        void update(final double dt) {
            x += vx * dt;
            y += vy * dt;
            if (Math.hypot(targetX - x, targetY - y) <= Math.max(2.5, Math.hypot(vx, vy) * dt)) {
                x = targetX;
                y = targetY;
                active = false;
            }
        }
    }

    /**
     * Player interceptor missile.
     */
    private static final class Interceptor {

        double x;
        double y;
        final double startX;
        final double startY;
        final double targetX;
        final double targetY;
        final double vx;
        final double vy;
        boolean active = true;

        Interceptor(final double startX, final double startY, final double targetX, final double targetY) {
            this.x = startX;
            this.y = startY;
            this.startX = startX;
            this.startY = startY;
            this.targetX = targetX;
            this.targetY = targetY;
            final var dx = targetX - startX;
            final var dy = targetY - startY;
            final var distance = Math.max(1.0, Math.hypot(dx, dy));
            vx = dx / distance * PLAYER_SPEED;
            vy = dy / distance * PLAYER_SPEED;
        }

        void update(final double dt) {
            x += vx * dt;
            y += vy * dt;
            if (Math.hypot(targetX - x, targetY - y) <= PLAYER_SPEED * dt + 1.0) {
                x = targetX;
                y = targetY;
                active = false;
            }
        }
    }

    /**
     * Expanding player or enemy explosion.
     */
    private static final class Explosion {

        final double x;
        final double y;
        double radius;
        boolean growing = true;
        boolean active = true;
        final boolean playerExplosion;

        Explosion(final double x, final double y, final boolean playerExplosion) {
            this.x = x;
            this.y = y;
            this.playerExplosion = playerExplosion;
        }

        void update(final double dt) {
            if (growing) {
                radius += EXPLOSION_GROW_SPEED * dt;
                if (radius >= EXPLOSION_MAX_RADIUS) {
                    radius = EXPLOSION_MAX_RADIUS;
                    growing = false;
                }
            } else {
                radius -= EXPLOSION_SHRINK_SPEED * dt;
                if (radius <= 0) {
                    radius = 0;
                    active = false;
                }
            }
        }

        boolean contains(final double px, final double py) {
            return Math.hypot(px - x, py - y) <= radius;
        }
    }

    /**
     * City entity.
     */
    private static final class City {

        final double x;
        final double y;
        boolean alive = true;

        City(final double x, final double y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Firing base entity.
     */
    private static final class MissileBase {

        final double x;
        final double y;
        int ammo;
        boolean alive = true;

        MissileBase(final double x, final double y, final int ammo) {
            this.x = x;
            this.y = y;
            this.ammo = ammo;
        }
    }

    /**
     * Enemy bomber aircraft.
     */
    private static final class Bomber {

        double x;
        double y;
        final double vx;
        final double dropTimer;
        double nextDrop;
        boolean active = true;

        Bomber(final double x, final double y, final double vx, final double nextDrop) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.nextDrop = nextDrop;
            dropTimer = 0;
        }
    }

    /**
     * Initializes a new game.
     *
     * @param width Canvas width.
     * @param height Canvas height.
     */
    private void initGame(final int width, final int height) {
        cities.clear();
        bases.clear();
        incomingMissiles.clear();
        interceptors.clear();
        explosions.clear();
        bombers.clear();
        score = 0;
        level = 1;
        selectedBase = 1;
        gameOver = false;
        waveComplete = false;
        bonusAwarded = false;
        spawnTimer = 0;
        waveTimer = 0;
        nextWaveTimer = 0;
        waveMissilesToSpawn = 0;
        waveMissilesSpawned = 0;
        waveMissilesDestroyed = 0;
        targetX = -1;
        targetY = -1;
        targetVisible = false;
        final double[] cityPositions = {width * 0.08, width * 0.22, width * 0.35, width * 0.65, width * 0.78, width * 0.92};
        for (final var x : cityPositions) {
            cities.add(new City(x, height - 10));
        }
        final double[] basePositions = {width * 0.42, width * 0.50, width * 0.58};
        for (final var x : basePositions) {
            bases.add(new MissileBase(x, height - 10, INITIAL_AMMO));
        }
        selectedBase = 1;
        startWave();
    }

    /**
     * Starts a new wave.
     */
    private void startWave() {
        waveComplete = false;
        bonusAwarded = false;
        spawnTimer = 0;
        waveTimer = 0;
        nextWaveTimer = 0;
        waveMissilesSpawned = 0;
        waveMissilesDestroyed = 0;
        waveMissilesToSpawn = Math.min(18 + level * 4, 70);
        if (soundManager != null) {
            soundManager.playGameStart();
        }
    }

    /**
     * Converts ARGB image buffer to RGB565 using direct raster buffer access for maximum performance.
     *
     * @param image Source image.
     * @param dest Destination byte array.
     * @param width Image width.
     * @param height Image height.
     */
    private void convertArgbToRgb565(final BufferedImage image, final byte[] dest, final int width, final int height) {
        final var buffer = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        var index = 0;
        var pixelIndex = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                final var rgb = buffer[pixelIndex++];
                final var r = (rgb >> 16) & 0xff;
                final var g = (rgb >> 8) & 0xff;
                final var b = rgb & 0xff;
                final var rgb565 = ((r & 0xf8) << 8) | ((g & 0xfc) << 3) | (b >> 3);
                dest[index++] = (byte) (rgb565 >> 8);
                dest[index++] = (byte) rgb565;
            }
        }
    }

    /**
     * Returns one living ground target.
     *
     * @return Target or null.
     */
    private double[] randomGroundTarget() {
        final var targets = new ArrayList<double[]>();
        for (final var city : cities) {
            if (city.alive) {
                targets.add(new double[]{city.x, city.y});
            }
        }
        for (final var base : bases) {
            if (base.alive) {
                targets.add(new double[]{base.x, base.y});
            }
        }
        if (targets.isEmpty()) {
            return null;
        }
        return targets.get(random.nextInt(targets.size()));
    }

    /**
     * Spawns a conventional missile, smart bomb, or MIRV.
     *
     * @param width Screen width.
     */
    private void spawnMissile(final int width) {
        final var target = randomGroundTarget();
        if (target == null) {
            gameOver = true;
            if (soundManager != null) {
                soundManager.playGameOver();
            }
            return;
        }
        final var startX = 8.0 + random.nextDouble() * Math.max(1.0, width - 16.0);
        final var typeRoll = random.nextDouble();
        final IncomingMissile.Type type;
        final double speed;
        if (level >= 3 && typeRoll < 0.12) {
            type = IncomingMissile.Type.MIRV;
            speed = MIRV_SPEED + level * 1.8;
        } else if (level >= 2 && typeRoll < 0.28) {
            type = IncomingMissile.Type.SMART;
            speed = SMART_BOMB_SPEED + level * 1.7;
        } else {
            type = IncomingMissile.Type.NORMAL;
            speed = MISSILE_MIN_SPEED + random.nextDouble() * (MISSILE_MAX_SPEED - MISSILE_MIN_SPEED) + level * 2.5;
        }
        incomingMissiles.add(new IncomingMissile(startX, 2, target[0], target[1], speed, type));
        waveMissilesSpawned++;
    }

    /**
     * Spawns a moving bomber.
     *
     * @param width Screen width.
     * @param height Screen height.
     */
    private void spawnBomber(final int width, final int height) {
        if (level < 2 || bombers.size() >= Math.min(1 + level / 5, 2)) {
            return;
        }
        final var fromLeft = random.nextBoolean();
        final var x = fromLeft ? -18.0 : width + 18.0;
        final var y = 18.0 + random.nextDouble() * Math.min(28.0, height * 0.16);
        final var speed = 18.0 + level * 2.0;
        final var vx = fromLeft ? speed : -speed;
        bombers.add(new Bomber(x, y, vx, 0.8 + random.nextDouble() * 1.2));
    }

    /**
     * Fires an interceptor from the selected battery.
     *
     * @param targetX Target X coordinate.
     * @param targetY Target Y coordinate.
     */
    private void fireInterceptor(final double targetX, final double targetY) {
        if (gameOver || waveComplete) {
            return;
        }
        if (selectedBase < 0 || selectedBase >= bases.size()) {
            selectNextBase();
        }
        if (selectedBase < 0 || selectedBase >= bases.size()) {
            return;
        }
        final var base = bases.get(selectedBase);
        if (!base.alive || base.ammo <= 0) {
            selectNextBase();
            if (selectedBase < 0) {
                return;
            }
        }
        final var firingBase = bases.get(selectedBase);
        if (!firingBase.alive || firingBase.ammo <= 0) {
            return;
        }
        final var clampedTargetY = Math.min(targetY, firingBase.y - 7);
        firingBase.ammo--;
        interceptors.add(new Interceptor(firingBase.x, firingBase.y, targetX, clampedTargetY));
        this.targetX = targetX;
        this.targetY = clampedTargetY;
        this.targetVisible = true;
        if (soundManager != null) {
            soundManager.playFire();
        }
    }

    /**
     * Selects a firing base by touching near its position.
     *
     * @param x Touch X coordinate.
     */
    private void selectBase(final double x) {
        var bestBase = -1;
        var bestDistance = Double.MAX_VALUE;
        for (var i = 0; i < bases.size(); i++) {
            final var base = bases.get(i);
            if (!base.alive || base.ammo <= 0) {
                continue;
            }
            final var distance = Math.abs(base.x - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestBase = i;
            }
        }
        if (bestBase >= 0 && bestDistance <= 32) {
            selectedBase = bestBase;
        }
    }

    /**
     * Automatically chooses a living base with ammunition.
     */
    private void selectNextBase() {
        if (selectedBase >= 0 && selectedBase < bases.size()) {
            final var current = bases.get(selectedBase);
            if (current.alive && current.ammo > 0) {
                return;
            }
        }
        var best = -1;
        var bestDistance = Double.MAX_VALUE;
        for (var i = 0; i < bases.size(); i++) {
            final var base = bases.get(i);
            if (!base.alive || base.ammo <= 0) {
                continue;
            }
            final var distance = Math.abs(base.x - targetX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        selectedBase = best;
    }

    /**
     * Updates enemy missiles and resolves ground impacts.
     *
     * @param dt Delta time.
     */
    private void updateIncomingMissiles(final double dt) {
        final Iterator<IncomingMissile> iterator = incomingMissiles.iterator();
        while (iterator.hasNext()) {
            final var missile = iterator.next();
            missile.update(dt);
            if (missile.type == IncomingMissile.Type.MIRV && !missile.split && missile.active && missile.y > getHeight() * 0.40) {
                missile.split = true;
                missile.active = false;
                final var leftTarget = findNearestGroundTarget(missile.x - 25, missile.y);
                final var rightTarget = findNearestGroundTarget(missile.x + 25, missile.y);
                if (leftTarget != null) {
                    incomingMissiles.add(new IncomingMissile(missile.x, missile.y, leftTarget[0], leftTarget[1], MIRV_SPEED + level
                            * 2.0, IncomingMissile.Type.NORMAL));
                    waveMissilesSpawned++;
                }
                if (rightTarget != null) {
                    incomingMissiles.add(new IncomingMissile(missile.x, missile.y, rightTarget[0], rightTarget[1], MIRV_SPEED
                            + level * 2.0, IncomingMissile.Type.NORMAL));
                    waveMissilesSpawned++;
                }
                iterator.remove();
                continue;
            }
            if (!missile.active) {
                explosions.add(new Explosion(missile.targetX, missile.targetY, false));
                destroyTarget(missile.targetX, missile.targetY);
                if (soundManager != null) {
                    soundManager.playExplosion();
                }
                iterator.remove();
            }
        }
    }

    /**
     * Finds the nearest living city or base to an X coordinate.
     *
     * @param x Preferred X coordinate.
     * @param y Preferred Y coordinate.
     * @return Target or null.
     */
    private double[] findNearestGroundTarget(final double x, final double y) {
        double[] best = null;
        var bestDistance = Double.MAX_VALUE;
        for (final var city : cities) {
            if (city.alive) {
                final var distance = Math.hypot(city.x - x, city.y - y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new double[]{city.x, city.y};
                }
            }
        }
        for (final var base : bases) {
            if (base.alive) {
                final var distance = Math.hypot(base.x - x, base.y - y);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new double[]{base.x, base.y};
                }
            }
        }
        return best;
    }

    /**
     * Updates player interceptors.
     *
     * @param dt Delta time.
     */
    private void updateInterceptors(final double dt) {
        final Iterator<Interceptor> iterator = interceptors.iterator();
        while (iterator.hasNext()) {
            final var interceptor = iterator.next();
            interceptor.update(dt);
            if (!interceptor.active) {
                explosions.add(new Explosion(interceptor.x, interceptor.y, true));
                if (soundManager != null) {
                    soundManager.playExplosion();
                }
                iterator.remove();
            }
        }
    }

    /**
     * Updates enemy bombers.
     *
     * @param dt Delta time.
     * @param width Screen width.
     */
    private void updateBombers(final double dt, final int width) {
        final Iterator<Bomber> iterator = bombers.iterator();
        while (iterator.hasNext()) {
            final var bomber = iterator.next();
            bomber.x += bomber.vx * dt;
            bomber.nextDrop -= dt;
            if (bomber.nextDrop <= 0) {
                final var target = randomGroundTarget();
                if (target != null) {
                    final var smart = level >= 4 && random.nextDouble() < 0.35;
                    final var type = smart ? IncomingMissile.Type.SMART : IncomingMissile.Type.NORMAL;
                    incomingMissiles.add(new IncomingMissile(bomber.x, bomber.y + 5, target[0], target[1], smart ? SMART_BOMB_SPEED
                            : MISSILE_MIN_SPEED + level * 2.0, type));
                    waveMissilesSpawned++;
                }
                bomber.nextDrop = 0.8 + random.nextDouble() * 1.5;
            }
            if ((bomber.vx > 0 && bomber.x > width + 25) || (bomber.vx < 0 && bomber.x < -25)) {
                bomber.active = false;
                iterator.remove();
            }
        }
    }

    /**
     * Updates explosions and destroys attackers touched by the leading edge.
     *
     * @param dt Delta time.
     */
    private void updateExplosions(final double dt) {
        final var newExplosions = new ArrayList<Explosion>();
        for (final var explosion : explosions) {
            explosion.update(dt);
            if (!explosion.playerExplosion) {
                continue;
            }
            final Iterator<IncomingMissile> missileIterator = incomingMissiles.iterator();
            while (missileIterator.hasNext()) {
                final var missile = missileIterator.next();
                if (explosion.contains(missile.x, missile.y)) {
                    final var points = switch (missile.type) {
                        case NORMAL ->
                            NORMAL_MISSILE_SCORE;
                        case SMART ->
                            SMART_BOMB_SCORE;
                        case MIRV ->
                            MIRV_SCORE;
                    };
                    score += points;
                    waveMissilesDestroyed++;
                    newExplosions.add(new Explosion(missile.x, missile.y, true));
                    missileIterator.remove();
                }
            }
            final Iterator<Bomber> bomberIterator = bombers.iterator();
            while (bomberIterator.hasNext()) {
                final var bomber = bomberIterator.next();
                if (explosion.contains(bomber.x, bomber.y)) {
                    score += BOMBER_SCORE;
                    newExplosions.add(new Explosion(bomber.x, bomber.y, true));
                    bomber.active = false;
                    bomberIterator.remove();
                }
            }
        }
        explosions.addAll(newExplosions);
        explosions.removeIf(explosion -> !explosion.active);
    }

    /**
     * Destroys a city or firing base at ground coordinates.
     *
     * @param x Impact X coordinate.
     * @param y Impact Y coordinate.
     */
    private void destroyTarget(final double x, final double y) {
        for (final var city : cities) {
            if (city.alive && Math.hypot(city.x - x, city.y - y) < 13) {
                city.alive = false;
                return;
            }
        }
        for (var i = 0; i < bases.size(); i++) {
            final var base = bases.get(i);
            if (base.alive && Math.hypot(base.x - x, base.y - y) < 13) {
                base.alive = false;
                base.ammo = 0;
                if (selectedBase == i) {
                    selectNextBase();
                }
                return;
            }
        }
    }

    /**
     * Returns the number of surviving cities.
     *
     * @return Living city count.
     */
    private int livingCities() {
        var count = 0;
        for (final var city : cities) {
            if (city.alive) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of remaining missiles in all bases.
     *
     * @return Remaining ammunition.
     */
    private int remainingAmmo() {
        var count = 0;
        for (final var base : bases) {
            if (base.alive) {
                count += base.ammo;
            }
        }
        return count;
    }

    /**
     * Awards the end-of-wave bonus.
     */
    private void awardWaveBonus() {
        if (bonusAwarded) {
            return;
        }
        bonusAwarded = true;
        score += livingCities() * 100;
        score += remainingAmmo() * 5;
    }

    /**
     * Determines whether a new wave can begin.
     *
     * @return True if all current attackers are gone.
     */
    private boolean waveIsClear() {
        return waveMissilesSpawned >= waveMissilesToSpawn && incomingMissiles.isEmpty() && interceptors.isEmpty() && explosions.
                isEmpty() && bombers.isEmpty();
    }

    /**
     * Updates wave progression.
     *
     * @param dt Delta time.
     */
    private void updateWave(final double dt) {
        if (waveComplete) {
            nextWaveTimer -= (long) (dt * 1000);
            if (nextWaveTimer <= 0) {
                level++;
                startWave();
                for (final var base : bases) {
                    if (base.alive) {
                        base.ammo = INITIAL_AMMO;
                    }
                }
            }
            return;
        }
        if (waveIsClear()) {
            awardWaveBonus();
            waveComplete = true;
            nextWaveTimer = LEVEL_BREAK_MS;
        }
    }

    /**
     * Updates the complete game state.
     *
     * @param dt Delta time.
     * @param width Screen width.
     * @param height Screen height.
     */
    private void update(final double dt, final int width, final int height) {
        if (gameOver) {
            return;
        }
        waveTimer += (long) (dt * 1000);
        final var spawnInterval = Math.max(300L, INITIAL_SPAWN_INTERVAL - (level - 1L) * 65L);
        if (!waveComplete && waveMissilesSpawned < waveMissilesToSpawn) {
            spawnTimer += (long) (dt * 1000);
            if (spawnTimer >= spawnInterval) {
                spawnTimer = 0;
                spawnMissile(width);
                if (level >= 2 && random.nextDouble() < Math.min(0.06 + level * 0.006, 0.16)) {
                    spawnBomber(width, height);
                }
            }
        }
        updateBombers(dt, width);
        updateIncomingMissiles(dt);
        updateInterceptors(dt);
        updateExplosions(dt);
        updateWave(dt);
        if (livingCities() == 0) {
            gameOver = true;
            if (soundManager != null) {
                soundManager.playGameOver();
            }
        }
    }

    /**
     * Draws a firing base.
     *
     * @param g Graphics context.
     * @param base Base entity.
     * @param selected Whether selected.
     */
    private void drawBase(final Graphics2D g, final MissileBase base, final boolean selected) {
        final var x = (int) base.x;
        final var y = (int) base.y;
        if (!base.alive) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(x - 10, y - 3, 20, 3);
            g.fillRect(x - 5, y - 5, 10, 2);
            return;
        }
        g.setColor(Color.GREEN);
        g.fillRect(x - 12, y - 4, 24, 4);
        g.fillRect(x - 5, y - 10, 10, 6);
        g.drawLine(x, y - 9, x, y - 17);
        if (selected) {
            g.setColor(Color.YELLOW);
            g.drawRect(x - 14, y - 20, 28, 18);
        }
        g.setColor(Color.GREEN);
        g.setFont(new Font("Monospaced", Font.BOLD, 8));
        g.drawString(Integer.toString(base.ammo), x - 4, y + 9);
    }

    /**
     * Draws a city.
     *
     * @param g Graphics context.
     * @param city City entity.
     */
    private void drawCity(final Graphics2D g, final City city) {
        final var x = (int) city.x;
        final var y = (int) city.y;
        if (!city.alive) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(x - 8, y, 16, 3);
            return;
        }
        g.setColor(Color.GREEN);
        g.fillRect(x - 9, y - 5, 5, 5);
        g.fillRect(x - 2, y - 10, 5, 10);
        g.fillRect(x + 5, y - 6, 5, 6);
        g.drawLine(x - 2, y - 10, x - 2, y - 13);
        g.drawLine(x + 2, y - 10, x + 2, y - 13);
    }

    /**
     * Draws an enemy bomber.
     *
     * @param g Graphics context.
     * @param bomber Bomber entity.
     */
    private void drawBomber(final Graphics2D g, final Bomber bomber) {
        final var x = (int) bomber.x;
        final var y = (int) bomber.y;
        g.setColor(Color.YELLOW);
        g.fillRect(x - 7, y - 1, 14, 3);
        g.fillRect(x - 3, y - 4, 6, 3);
        g.fillRect(x - 11, y + 2, 8, 2);
        g.fillRect(x + 3, y + 2, 8, 2);
        g.fillRect(x - 1, y + 4, 2, 2);
    }

    /**
     * Draws a solid explosion with a red outer fill and white core.
     *
     * @param g Graphics context.
     * @param explosion Explosion entity.
     */
    private void drawExplosion(final Graphics2D g, final Explosion explosion) {
        final var radius = (int) explosion.radius;
        if (radius <= 0) {
            return;
        }
        final var inner = Math.max(1, radius - 3);
        if (explosion.playerExplosion) {
            g.setColor(Color.RED);
            g.fillOval((int) explosion.x - radius, (int) explosion.y - radius, radius * 2, radius * 2);
            g.setColor(Color.WHITE);
            g.fillOval((int) explosion.x - inner, (int) explosion.y - inner, inner * 2, inner * 2);
        } else {
            g.setColor(Color.RED);
            g.fillOval((int) explosion.x - radius, (int) explosion.y - radius, radius * 2, radius * 2);
        }
    }

    /**
     * Draws the score and game status.
     *
     * @param g Graphics context.
     * @param width Screen width.
     * @param height Screen height.
     */
    private void drawHud(final Graphics2D g, final int width, final int height) {
        g.setFont(new Font("Monospaced", Font.BOLD, 9));
        g.setColor(Color.RED);
        g.drawString(Integer.toString(score), 4, 10);
        g.setColor(Color.YELLOW);
        g.drawString("HI 7500", Math.max(4, width / 2 - 22), 10);
        g.setColor(Color.GREEN);
        g.drawString("W" + level, Math.max(4, width - 25), 10);
        if (waveComplete && !gameOver) {
            final var text = "BONUS";
            g.setColor(Color.WHITE);
            final var metrics = g.getFontMetrics();
            g.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2 - 4);
        }
        if (gameOver) {
            g.setColor(Color.RED);
            g.setFont(new Font("Monospaced", Font.BOLD, 18));
            final var text = "GAME OVER";
            final var metrics = g.getFontMetrics();
            g.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2);
            g.setColor(Color.YELLOW);
            g.setFont(new Font("Monospaced", Font.PLAIN, 8));
            final var restart = "TOUCH TO RESTART";
            final var restartMetrics = g.getFontMetrics();
            g.drawString(restart, (width - restartMetrics.stringWidth(restart)) / 2, height / 2 + 15);
        }
    }

    /**
     * Renders one complete RGB565 frame using the base class image buffer and graphics context.
     *
     * @param display Color display.
     */
    private void renderFrame(final AbstractColorDisplay display) {
        final var width = getWidth();
        final var height = getHeight();
        if (frameBuffer == null) {
            frameBuffer = new byte[width * height * BYTES_PER_PIXEL];
        }
        synchronized (this) {
            final var g2d = getG2d();
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, width, height);
            g2d.setColor(Color.BLUE);
            g2d.fillRect(0, height - 6, width, 6);
            for (final var city : cities) {
                drawCity(g2d, city);
            }
            for (var i = 0; i < bases.size(); i++) {
                drawBase(g2d, bases.get(i), i == selectedBase);
            }
            for (final var missile : incomingMissiles) {
                final var trailFactor = 0.22;
                final var trailX = missile.startX + (missile.x - missile.startX) * trailFactor;
                final var trailY = missile.startY + (missile.y - missile.startY) * trailFactor;
                g2d.setColor(missile.type == IncomingMissile.Type.SMART ? Color.MAGENTA : missile.type
                        == IncomingMissile.Type.MIRV ? Color.ORANGE : Color.RED);
                g2d.drawLine((int) trailX, (int) trailY, (int) missile.x, (int) missile.y);
                g2d.fillRect((int) missile.x - 1, (int) missile.y - 1, 3, 3);
            }
            for (final var bomber : bombers) {
                drawBomber(g2d, bomber);
            }
            g2d.setColor(Color.GREEN);
            for (final var interceptor : interceptors) {
                g2d.drawLine((int) interceptor.startX, (int) interceptor.startY, (int) interceptor.x, (int) interceptor.y);
                g2d.fillRect((int) interceptor.x - 1, (int) interceptor.y - 1, 3, 3);
            }
            for (final var explosion : explosions) {
                drawExplosion(g2d, explosion);
            }
            if (targetVisible && !gameOver) {
                final var x = (int) targetX;
                final var y = (int) targetY;
                g2d.setColor(Color.GREEN);
                g2d.drawLine(x - 6, y, x + 6, y);
                g2d.drawLine(x, y - 6, x, y + 6);
                g2d.drawRect(x - 3, y - 3, 6, 6);
            }
            drawHud(g2d, width, height);
            convertArgbToRgb565(getImage(), frameBuffer, width, height);
        }
        display.setWindow(0, 0, width, height);
        MemorySegment.copy(frameBuffer, 0, display.getImageSegment(), ValueLayout.JAVA_BYTE, 0, frameBuffer.length);
        display.writeData(display.getImageSegment());
    }

    /**
     * Core game loop execution helper.
     *
     * @param display Color display.
     * @param touch Touch device.
     * @param width Screen width.
     * @param height Screen height.
     * @throws Exception On failure.
     */
    private void runGameLoop(final AbstractColorDisplay display, final AbstractTouch touch, final int width, final int height)
            throws Exception {
        initGame(width, height);
        renderFrame(display);

        final var xpt = (Xpt2046) touch;
        lastFrameNanos = System.nanoTime();
        while (!Thread.currentThread().isInterrupted() && isRunning()) {
            final var now = System.nanoTime();
            var dt = (now - lastFrameNanos) / 1_000_000_000.0;
            lastFrameNanos = now;
            dt = Math.min(dt, 0.050);
            if (xpt != null && xpt.pollEvent(1)) {
                final var edge = xpt.getEdge();
                if (edge == Periphery.GPIO_EDGE_FALLING() && xpt.isPressed()) {
                    final var rawPoint = xpt.readCoordinates();
                    final var screenPoint = mapTouchToScreen(rawPoint.x(), rawPoint.y());
                    final var touchX = Math.max(0, Math.min(screenPoint.x, width - 1));
                    final var touchY = Math.max(15, Math.min(screenPoint.y, height - 25));
                    if (gameOver) {
                        initGame(width, height);
                    } else if (touchY > height - 35) {
                        selectBase(touchX);
                    } else {
                        fireInterceptor(touchX, touchY);
                    }
                }
            }
            synchronized (this) {
                update(dt, width, height);
            }
            renderFrame(display);
        }
    }

    /**
     * Runs the touch game loop with optional sound management.
     *
     * @param display Color display.
     * @param touch Touch device.
     * @throws Exception On failure.
     */
    private void runDemo(final AbstractColorDisplay display, final AbstractTouch touch) throws Exception {
        log.info("Missile Command demo started on device {} line {} (sound enabled: {})...", getGpioDevice(), getTouchIrqLine(),
                enableSound);
        final var width = getWidth();
        final var height = getHeight();

        if (enableSound) {
            try (
                    final var speaker = new PassiveSpeaker(PwmDeviceFactory.create(soundMode, soundDevice, soundChannel)); final var manager
                    = new SoundManager(speaker)) {
                this.soundManager = manager;
                runGameLoop(display, touch, width, height);
            } finally {
                this.soundManager = null;
            }
        } else {
            this.soundManager = null;
            runGameLoop(display, touch, width, height);
        }
    }

    /**
     * CLI entry point.
     *
     * @return Exit code.
     * @throws Exception On failure.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();
        try {
            runDemo(getDisplay(), getTouch());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Application entry point.
     *
     * @param args Command-line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new TouchMissileCommand()).execute(args));
    }
}
