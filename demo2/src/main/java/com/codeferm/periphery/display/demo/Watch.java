/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.AbstractColorDisplay;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * High-performance realistic analog watch demo featuring a gear-driven tick-tock second hand, smooth hour/minute hands, tick
 * markers, and a date display using FFM and a producer-consumer background rendering pipeline.
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Watch", mixinStandardHelpOptions = true, version = "1.1.0-SNAPSHOT",
        description = "Realistic analog watch with tick-tock second hand, hour/minute hands, and date display")
public class Watch extends Base {

    /**
     * Picocli command spec for inspecting parse results.
     */
    @Spec
    private CommandSpec spec;

    /**
     * Main rendering and clock loop using a producer-consumer pattern.
     *
     * @param display Abstract color display driver instance used for foreign memory transfers.
     */
    public final void runDemo(final AbstractColorDisplay display) {
        final var width = getWidth();
        final var height = getHeight();
        final var centerX = width / 2.0;
        final var centerY = height / 2.0;
        final var radius = Math.min(width, height) * 0.48;

        final var frameDelay = 1000 / getFps();
        log.info("Starting Analog Watch Demo via FFM at {} FPS with resolution {}x{}", getFps(), width, height);

        // Double-buffering queue to decouple frame generation from hardware transmission
        final BlockingQueue<BufferedImage> frameQueue = new ArrayBlockingQueue<>(2);

        // Pre-allocate dual buffers for zero-allocation handoff
        final BufferedImage bufferA = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage bufferB = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Fetch base snapshot handler image buffer if snapshot flag is enabled
        final BufferedImage snapshotBi = getImage();

        // Background Producer Thread (Watch Dial & Hands Rendering)
        final Thread renderThread = new Thread(() -> {
            var activeBuffer = bufferA;
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                final var now = LocalDateTime.now();
                final var hour = now.getHour();
                final var minute = now.getMinute();
                final var second = now.getSecond(); // Discrete second for tick-tock motion

                // Smooth movement for hour and minute hands
                final var exactMinute = minute + second / 60.0;
                final var exactHour = (hour % 12) + exactMinute / 60.0;

                final var g2d = activeBuffer.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // Clear background (Dark charcoal watch face dial)
                g2d.setColor(new Color(20, 20, 25));
                g2d.fillRect(0, 0, width, height);

                // Outer bezel ring
                g2d.setColor(new Color(70, 70, 80));
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawOval((int) (centerX - radius), (int) (centerY - radius), (int) (radius * 2), (int) (radius * 2));

                // Hour tick marks (Bold at 12, 3, 6, 9)
                for (var i = 0; i < 12; i++) {
                    final var angle = (i / 12.0) * 2.0 * Math.PI - Math.PI / 2.0;
                    final var outerX = centerX + Math.cos(angle) * (radius - 4);
                    final var outerY = centerY + Math.sin(angle) * (radius - 4);
                    final var innerX = centerX + Math.cos(angle) * (radius - 14);
                    final var innerY = centerY + Math.sin(angle) * (radius - 14);

                    g2d.setColor(i % 3 == 0 ? Color.WHITE : new Color(170, 170, 180));
                    g2d.setStroke(new BasicStroke(i % 3 == 0 ? 3.0f : 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2d.drawLine((int) innerX, (int) innerY, (int) outerX, (int) outerY);
                }

                // Minute tick marks
                for (var i = 0; i < 60; i++) {
                    if (i % 5 != 0) {
                        final var angle = (i / 60.0) * 2.0 * Math.PI - Math.PI / 2.0;
                        final var outerX = centerX + Math.cos(angle) * (radius - 4);
                        final var outerY = centerY + Math.sin(angle) * (radius - 4);
                        final var innerX = centerX + Math.cos(angle) * (radius - 8);
                        final var innerY = centerY + Math.sin(angle) * (radius - 8);

                        g2d.setColor(new Color(110, 110, 120));
                        g2d.setStroke(new BasicStroke(1.0f));
                        g2d.drawLine((int) innerX, (int) innerY, (int) outerX, (int) outerY);
                    }
                }

                // Date Window at 3 o'clock position
                final var dateBoxX = (int) (centerX + radius * 0.48);
                final var dateBoxY = (int) (centerY - 9);
                g2d.setColor(Color.BLACK);
                g2d.fillRect(dateBoxX - 16, dateBoxY - 8, 32, 18);
                g2d.setColor(new Color(100, 100, 110));
                g2d.drawRect(dateBoxX - 16, dateBoxY - 8, 32, 18);
                g2d.setFont(new Font("SansSerif", Font.BOLD, 10));
                g2d.setColor(Color.WHITE);
                final var dateStr = String.format("%02d", now.getDayOfMonth());
                g2d.drawString(dateStr, dateBoxX - 11, dateBoxY + 5);

                // Hour Hand
                final var hourAngle = (exactHour / 12.0) * 2.0 * Math.PI - Math.PI / 2.0;
                final var hourLen = radius * 0.5;
                final var hx = centerX + Math.cos(hourAngle) * hourLen;
                final var hy = centerY + Math.sin(hourAngle) * hourLen;
                g2d.setColor(Color.WHITE);
                g2d.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.drawLine((int) centerX, (int) centerY, (int) hx, (int) hy);

                // Minute Hand
                final var minAngle = (exactMinute / 60.0) * 2.0 * Math.PI - Math.PI / 2.0;
                final var minLen = radius * 0.75;
                final var mx = centerX + Math.cos(minAngle) * minLen;
                final var my = centerY + Math.sin(minAngle) * minLen;
                g2d.setColor(new Color(210, 210, 220));
                g2d.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.drawLine((int) centerX, (int) centerY, (int) mx, (int) my);

                // Second Hand (Gear-driven tick-tock motion using discrete integer seconds)
                final var secAngle = (second / 60.0) * 2.0 * Math.PI - Math.PI / 2.0;
                final var secLen = radius * 0.85;
                final var sx = centerX + Math.cos(secAngle) * secLen;
                final var sy = centerY + Math.sin(secAngle) * secLen;
                final var tailX = centerX - Math.cos(secAngle) * (radius * 0.2);
                final var tailY = centerY - Math.sin(secAngle) * (radius * 0.2);
                g2d.setColor(new Color(230, 60, 60)); // Classic red second hand
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawLine((int) tailX, (int) tailY, (int) sx, (int) sy);

                // Center pivot pin (capped with metallic silver and dark rim)
                g2d.setColor(new Color(180, 180, 190));
                g2d.fillOval((int) centerX - 4, (int) centerY - 4, 8, 8);
                g2d.setColor(new Color(40, 40, 40));
                g2d.drawOval((int) centerX - 4, (int) centerY - 4, 8, 8);

                g2d.dispose();

                // Mirror into snapshot buffer if enabled
                if (snapshotBi != null) {
                    final var srcPixels = ((DataBufferInt) activeBuffer.getRaster().getDataBuffer()).getData();
                    final var snapPixels = ((DataBufferInt) snapshotBi.getRaster().getDataBuffer()).getData();
                    System.arraycopy(srcPixels, 0, snapPixels, 0, srcPixels.length);
                }

                try {
                    frameQueue.put(activeBuffer);
                    activeBuffer = (activeBuffer == bufferA) ? bufferB : bufferA;
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Watch-Worker");

        renderThread.start();

        // Main Consumer Loop (Pacing & Hardware Display Transfer)
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
     * Entry point for CLI execution via Picocli.
     * <p>
     * Defaults to 30 FPS, which is plenty for a step-ticking second hand while keeping CPU usage low.
     * </p>
     *
     * @return Exit code.
     * @throws Exception possible hardware or foreign memory exception.
     */
    @Override
    public final Integer call() throws Exception {
        if (!spec.commandLine().getParseResult().hasMatchedOption("fps")) {
            setFps(30);
        }
        super.call();
        try {
            runDemo(getDisplay());
        } finally {
            done();
        }
        return 0;
    }

    /**
     * Main method.
     *
     * @param args Command line arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new Watch()).execute(args));
    }
}
