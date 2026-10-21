/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.Gc9a01;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Dual display solar system demo featuring a 6-planet solar system overview on Display 1 and a magnified detailed view with
 * sun-synced lighting on Display 2 using parallel pipelines.
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "SolarSystem", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Dual display multi-planet solar system overview and sun-synced planetary detail")
@Getter
@Setter
public class SolarSystem extends Base {

    /**
     * Picocli command spec for inspecting parse results.
     */
    @Spec
    private CommandSpec spec;

    /**
     * Second display SPI device node.
     */
    @Option(names = {"-d2", "--device2"}, description = "SPI device for second screen (default: ${DEFAULT-VALUE})")
    private String device2 = "/dev/spidev1.1";

    /**
     * Second display Data/Command GPIO pin.
     */
    @Option(names = {"-dc2"}, description = "Data/Command GPIO pin for second screen (default: ${DEFAULT-VALUE})")
    private int dcPin2 = 11;

    /**
     * Second display Reset GPIO pin.
     */
    @Option(names = {"-res2"}, description = "Reset GPIO pin for second screen (default: ${DEFAULT-VALUE})")
    private int rstPin2 = 199;

    /**
     * Second display SPI speed in Hz.
     */
    @Option(names = {"-s2", "--spi-speed2"}, description = "SPI speed for second screen in Hz (default: ${DEFAULT-VALUE})")
    private int spiSpeed2 = 32000000;

    /**
     * Planet focus for detail display (earth, mars, venus, jupiter, saturn).
     */
    @Option(names = {"-pl", "--planet"}, description
            = "Detail view focus planet: earth, mars, venus, jupiter, saturn (default: ${DEFAULT-VALUE})")
    private String focusPlanet = "earth";

    /**
     * Shared orbital state container keeping all planets synchronized in time.
     */
    private static class SystemState {

        double mercuryAngle = 0.0;
        double venusAngle = 0.0;
        double earthAngle = 0.0;
        double marsAngle = 0.0;
        double jupiterAngle = 0.0;
        double saturnAngle = 0.0;
        double moonAngle = 0.0;
        long lastUpdateFrame = -1;

        synchronized void update(final long frameCount) {
            if (lastUpdateFrame == frameCount) {
                return;
            }
            lastUpdateFrame = frameCount;
            mercuryAngle += 0.05;
            venusAngle += 0.035;
            earthAngle += 0.02;
            marsAngle += 0.015;
            jupiterAngle += 0.008;
            saturnAngle += 0.005;
            moonAngle += 0.08;
        }
    }

    /**
     * Renders the 6-planet Solar System overview on Display 1.
     *
     * @param display Abstract color display driver instance.
     * @param state Shared system state.
     */
    private void runOverviewDemo(final AbstractColorDisplay display, final SystemState state) {
        final var width = getWidth();
        final var height = getHeight();
        final var centerX = width / 2.0;
        final var centerY = height / 2.0;
        final var screenRadius = (int) (Math.min(width, height) * 0.45);

        final var frameDelay = 1000 / getFps();
        log.info("Starting Solar System Overview on Display 1 at {} FPS", getFps());

        final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(2);
        final BufferedImage bufferA = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage bufferB = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        final Thread renderThread = new Thread(() -> {
            var activeBuffer = bufferA;
            long frameCount = 0;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                state.update(frameCount++);

                final var pixels = ((DataBufferInt) activeBuffer.getRaster().getDataBuffer()).getData();
                final var spaceBg = 0x010206;

                for (var i = 0; i < pixels.length; i++) {
                    pixels[i] = spaceBg;
                }

                // Render Sun at center
                final var sunRadius = 22;
                for (var y = 0; y < height; y++) {
                    final var dy = y - centerY;
                    final var rowOffset = y * width;
                    for (var x = 0; x < width; x++) {
                        final var dx = x - centerX;
                        final var distSq = dx * dx + dy * dy;

                        if (distSq <= screenRadius * screenRadius) {
                            if (distSq <= sunRadius * sunRadius) {
                                pixels[rowOffset + x] = 0xFFCC22;
                            } else {
                                // Orbit rings for 6 planets
                                final double[] orbits = {30.0, 44.0, 60.0, 76.0, 93.0, 108.0};
                                for (double orbit : orbits) {
                                    if (Math.abs(Math.sqrt(distSq) - orbit) < 1.0) {
                                        pixels[rowOffset + x] = 0x223344;
                                    }
                                }
                            }
                        }
                    }
                }

                // Planet definitions: angles, orbits, radii, colors
                final double[] angles
                        = {state.mercuryAngle, state.venusAngle, state.earthAngle, state.marsAngle, state.jupiterAngle,
                            state.saturnAngle};
                final double[] orbits = {30.0, 44.0, 60.0, 76.0, 93.0, 108.0};
                final int[] radii = {3, 4, 5, 4, 7, 6};
                final int[] colors = {0x888888, 0xEECC88, 0x3388EE, 0xDD4422, 0xD4A373, 0xE0AE6F};

                for (int i = 0; i < angles.length; i++) {
                    final var px = centerX + Math.cos(angles[i]) * orbits[i];
                    final var py = centerY + Math.sin(angles[i]) * orbits[i];
                    final var pRadius = radii[i];
                    final var pColor = colors[i];

                    for (var y = 0; y < height; y++) {
                        final var dy = y - py;
                        final var rowOffset = y * width;
                        for (var x = 0; x < width; x++) {
                            final var dx = x - px;
                            if (dx * dx + dy * dy <= pRadius * pRadius) {
                                pixels[rowOffset + x] = pColor;
                            }
                        }
                    }
                }

                try {
                    frameQueue.put(activeBuffer);
                    activeBuffer = (activeBuffer == bufferA) ? bufferB : bufferA;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "SolarSystem-Overview-Worker");

        renderThread.start();

        try {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                final var startTime = System.currentTimeMillis();
                try {
                    final BufferedImage frame = frameQueue.poll(frameDelay, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        display.drawImage(frame, 0, 0, width, height);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                final var diff = System.currentTimeMillis() - startTime;
                if (diff < frameDelay) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(frameDelay - diff);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            renderThread.interrupt();
            try {
                renderThread.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Renders the magnified detail view with sun-synced day/night lighting on Display 2.
     *
     * @param display Abstract color display driver instance.
     * @param state Shared system state.
     */
    private void runDetailDemo(final AbstractColorDisplay display, final SystemState state) {
        final var width = getWidth();
        final var height = getHeight();
        final var centerX = width / 2.0;
        final var centerY = height / 2.0;
        final var screenRadius = (int) (Math.min(width, height) * 0.45);

        final var frameDelay = 1000 / getFps();
        log.info("Starting Planetary Detail View [{}] on Display 2 at {} FPS", focusPlanet, getFps());

        final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(2);
        final BufferedImage bufferA = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage bufferB = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        final Thread renderThread = new Thread(() -> {
            var activeBuffer = bufferA;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                final var pixels = ((DataBufferInt) activeBuffer.getRaster().getDataBuffer()).getData();
                final var spaceBg = 0x010206;

                for (var i = 0; i < pixels.length; i++) {
                    pixels[i] = spaceBg;
                }

                // Determine current angle of the focused planet relative to the Sun to sync sun vector
                double planetAngle = state.earthAngle;
                if ("mars".equalsIgnoreCase(focusPlanet)) {
                    planetAngle = state.marsAngle;
                } else if ("venus".equalsIgnoreCase(focusPlanet)) {
                    planetAngle = state.venusAngle;
                } else if ("mercury".equalsIgnoreCase(focusPlanet)) {
                    planetAngle = state.mercuryAngle;
                } else if ("jupiter".equalsIgnoreCase(focusPlanet)) {
                    planetAngle = state.jupiterAngle;
                } else if ("saturn".equalsIgnoreCase(focusPlanet)) {
                    planetAngle = state.saturnAngle;
                }

                // Sun direction vector from the planet's perspective (Sun is at center of overview)
                final double sunDirX = -Math.cos(planetAngle);
                final double sunDirY = -Math.sin(planetAngle);

                final var planetRadius = 55;

                for (var y = 0; y < height; y++) {
                    final var dy = y - centerY;
                    final var rowOffset = y * width;
                    for (var x = 0; x < width; x++) {
                        final var dx = x - centerX;
                        final var distSq = dx * dx + dy * dy;

                        if (distSq <= screenRadius * screenRadius) {
                            if (distSq <= planetRadius * planetRadius) {
                                final var nx = dx / (double) planetRadius;
                                final var ny = dy / (double) planetRadius;

                                // Dynamic illumination dot product with Sun direction vector
                                final var lightDot = nx * sunDirX + ny * sunDirY;

                                int baseR = 40, baseG = 110, baseB = 210; // Earth default
                                if ("mars".equalsIgnoreCase(focusPlanet)) {
                                    baseR = 180;
                                    baseG = 80;
                                    baseB = 40;
                                } else if ("venus".equalsIgnoreCase(focusPlanet)) {
                                    baseR = 210;
                                    baseG = 170;
                                    baseB = 100;
                                } else if ("mercury".equalsIgnoreCase(focusPlanet)) {
                                    baseR = 130;
                                    baseG = 130;
                                    baseB = 130;
                                } else if ("jupiter".equalsIgnoreCase(focusPlanet)) {
                                    baseR = 210;
                                    baseG = 160;
                                    baseB = 110;
                                } else if ("saturn".equalsIgnoreCase(focusPlanet)) {
                                    baseR = 220;
                                    baseG = 190;
                                    baseB = 130;
                                }

                                final var shade = Math.clamp(0.15 + 0.85 * Math.max(0.0, lightDot), 0.05, 1.0);
                                pixels[rowOffset + x] = ((int) (baseR * shade) << 16) | ((int) (baseG * shade) << 8) | (int) (baseB
                                        * shade);
                            }
                        }
                    }
                }

                // Saturn Rings detail render if Saturn is focused
                if ("saturn".equalsIgnoreCase(focusPlanet)) {
                    for (var y = 0; y < height; y++) {
                        final var dy = y - centerY;
                        final var rowOffset = y * width;
                        for (var x = 0; x < width; x++) {
                            final var dx = x - centerX;
                            final var dist = Math.sqrt(dx * dx + dy * dy);
                            // Draw elliptical rings
                            if (dist >= 65 && dist <= 85 && Math.abs(dy) < 12) {
                                pixels[rowOffset + x] = 0xD4B278;
                            }
                        }
                    }
                }

                // Earth Moon orbital detail phase sync if Earth is focused
                if ("earth".equalsIgnoreCase(focusPlanet)) {
                    final var moonOrbitRadius = 85.0;
                    final var moonX = centerX + Math.cos(state.moonAngle) * moonOrbitRadius;
                    final var moonY = centerY + Math.sin(state.moonAngle) * moonOrbitRadius;
                    final var moonRadius = 12;

                    for (var y = 0; y < height; y++) {
                        final var dy = y - moonY;
                        final var rowOffset = y * width;
                        for (var x = 0; x < width; x++) {
                            final var dx = x - moonX;
                            if (dx * dx + dy * dy <= moonRadius * moonRadius) {
                                final var mnx = dx / (double) moonRadius;
                                final var mny = dy / (double) moonRadius;
                                final var moonLight = mnx * sunDirX + mny * sunDirY;
                                final var shade = Math.clamp(0.1 + 0.9 * Math.max(0.0, moonLight), 0.05, 1.0);
                                final var gray = (int) (220 * shade);
                                pixels[rowOffset + x] = (gray << 16) | (gray << 8) | gray;
                            }
                        }
                    }
                }

                try {
                    frameQueue.put(activeBuffer);
                    activeBuffer = (activeBuffer == bufferA) ? bufferB : bufferA;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "SolarSystem-Detail-Worker");

        renderThread.start();

        try {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                final var startTime = System.currentTimeMillis();
                try {
                    final BufferedImage frame = frameQueue.poll(frameDelay, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        display.drawImage(frame, 0, 0, width, height);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                final var diff = System.currentTimeMillis() - startTime;
                if (diff < frameDelay) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(frameDelay - diff);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            renderThread.interrupt();
            try {
                renderThread.join(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public final Integer call() throws Exception {
        if (!spec.commandLine().getParseResult().hasMatchedOption("fps")) {
            setFps(30);
        }
        super.call();

        final AbstractColorDisplay display1 = getDisplay();
        final AbstractColorDisplay display2 = new Gc9a01(
                device2,
                0,
                spiSpeed2,
                getGpioDevice(),
                dcPin2,
                rstPin2
        );

        final SystemState sharedState = new SystemState();

        try {
            final Thread display1Thread = new Thread(() -> runOverviewDemo(display1, sharedState), "Overview-Thread");
            final Thread display2Thread = new Thread(() -> runDetailDemo(display2, sharedState), "Detail-Thread");

            display1Thread.start();
            display2Thread.start();

            try {
                while (isRunning() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            display1Thread.interrupt();
            display2Thread.interrupt();
            try {
                display1Thread.join();
                display2Thread.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } finally {
            try {
                display2.close();
            } catch (final Exception e) {
                log.error("Error closing display 2", e);
            }
            done();
        }
        return 0;
    }

    public static void main(final String... args) {
        System.exit(new CommandLine(new SolarSystem()).execute(args));
    }
}
