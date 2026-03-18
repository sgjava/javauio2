/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.device.BlockingButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Interactive SSD1331 OLED demonstration using hardware interrupts and Java 2D.
 * <p>
 * This implementation provides a robust, thread-safe environment for hardware interaction using the Foreign Function & Memory (FFM)
 * API. It handles asynchronous interrupts from a physical button to cycle through visual scenes.
 * </p>
 * <p>
 * Standards applied:
 * <ul>
 * <li>Concurrency: Deterministic shutdown sequence to prevent FFM scope exceptions.</li>
 * <li>Clean Code: Full Javadoc, removal of descriptive text after prompt, and FPS capping.</li>
 * </ul>
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "Ssd1331ButtonDemo", mixinStandardHelpOptions = true, version = "1.3.2",
        description = "High-standard interactive SSD1331 OLED demonstration.")
public class ButtonDemo extends Base {

    @Option(names = {"--button-dev"}, description = "Button GPIO device.", defaultValue = "/dev/gpiochip0")
    private String buttonDev;

    @Option(names = {"--button-line"}, description = "Button GPIO line.", defaultValue = "18")
    private int buttonLine;

    @Option(names = {"--debounce"}, description = "Debounce in ms.", defaultValue = "100")
    private int debounce;

    /**
     * Total count of available visual scenes.
     */
    private static final int SCENE_COUNT = 5;

    /**
     * Frame delay in milliseconds for 30 FPS target.
     */
    private static final long FRAME_DELAY_MS = 33L;

    /**
     * Thread-safe state for current active scene.
     */
    private final AtomicInteger sceneIndex = new AtomicInteger(0);

    /**
     * Monitors the hardware button for interrupt events in a background thread.
     *
     * @param button The hardware button device.
     */
    private void startButtonListener(final BlockingButton button) {
        log.info("Button listener started on line {}", buttonLine);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Short poll timeout allows frequent checks of interrupt status
                final var event = button.waitForEvent(500);
                if (event != null && event.edge() == Periphery.GPIO_EDGE_FALLING()) {
                    final var next = sceneIndex.updateAndGet(i -> (i + 1) % SCENE_COUNT);
                    log.info("Hardware Interrupt: Advancing to Scene {}", next);
                }
            }
        } catch (final Exception e) {
            // Only log errors if the thread wasn't intentionally stopped
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Hardware listener error: {}", e.getMessage());
            }
        } finally {
            log.debug("Background button listener thread exiting.");
        }
    }

    /**
     * Dispatches rendering logic based on current scene state.
     *
     * @param scene Current active scene index.
     */
    private void drawContent(final int scene) {
        final var g = getG2d();
        final var w = getWidth();
        final var h = getHeight();

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);

        switch (scene) {
            case 0 ->
                renderPrompt(g, w, h);
            case 1 ->
                renderShapes(g, w, h);
            case 2 ->
                renderGeometry(g, w, h);
            case 3 ->
                renderArt(g, w, h);
            case 4 ->
                renderPulse(g, w, h);
            default ->
                renderPrompt(g, w, h);
        }
    }

    private void renderPrompt(final Graphics2D g, final int w, final int h) {
        g.setColor(Color.ORANGE);
        g.drawRect(5, 5, w - 10, h - 10);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(Color.WHITE);
        g.drawString("READY...", 28, 25);

        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            g.setColor(Color.CYAN);
            g.drawString("PRESS BUTTON", 12, 45);
        }
    }

    private void renderShapes(final Graphics2D g, final int w, final int h) {
        g.setColor(Color.CYAN);
        g.fillOval(10, 10, 30, 30);
        g.setColor(Color.MAGENTA);
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(50, 15, 30, 30, 12, 12);
        g.setColor(Color.YELLOW);
        g.drawRect(35, 35, 20, 20);
    }

    private void renderGeometry(final Graphics2D g, final int w, final int h) {
        g.setColor(Color.GREEN);
        for (var i = 0; i < w; i += 6) {
            g.drawLine(i, 0, w - i, h);
        }
    }

    private void renderArt(final Graphics2D g, final int w, final int h) {
        for (var i = 0; i < 10; i++) {
            g.setColor(new Color(Color.HSBtoRGB(i / 10f, 0.8f, 0.8f)));
            g.drawOval(i * 4, i * 2, w - (i * 8), h - (i * 4));
        }
    }

    private void renderPulse(final Graphics2D g, final int w, final int h) {
        final var time = System.currentTimeMillis() % 2000;
        final var pulseSize = (int) (time / 25);
        g.setColor(new Color(0, 180, 255));
        g.drawOval((w / 2) - (pulseSize / 2), (h / 2) - (pulseSize / 2), pulseSize, pulseSize);
        g.setColor(Color.WHITE);
        g.fillOval((w / 2) - 1, (h / 2) - 1, 3, 3);
    }

    /**
     * Application entry point orchestrating lifecycle and resource management.
     *
     * @return Process exit code.
     * @throws Exception on hardware initialization failure.
     */
    @Override
    public final Integer call() throws Exception {
        super.call();

        final ExecutorService pool = Executors.newSingleThreadExecutor();
        final long runTimeMs = (getRunTime() > 0) ? getRunTime() * 1000L : 60000L;

        BlockingButton button = null;

        try {
            button = new BlockingButton(buttonDev, buttonLine);
            button.setDebounceMillis(debounce);

            final var finalButton = button;
            pool.execute(() -> startButtonListener(finalButton));

            log.info("Interactive demo active for {}ms.", runTimeMs);
            final long end = System.currentTimeMillis() + runTimeMs;

            while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted()) {
                drawContent(sceneIndex.get());
                getOled().drawImage(getImage());

                try {
                    TimeUnit.MILLISECONDS.sleep(FRAME_DELAY_MS);
                } catch (final InterruptedException e) {
                    // Restore interrupt status so while loop exits gracefully
                    Thread.currentThread().interrupt();
                    log.debug("Main loop sleep interrupted.");
                }
            }
        } finally {
            log.info("Shutdown sequence initiated...");

            // 1. Stop the background pool first to prevent "Already closed" native errors
            pool.shutdownNow();
            try {
                if (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.warn("Background pool shutdown timeout.");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 2. Close hardware button after the listener thread has definitely stopped
            if (button != null) {
                try {
                    button.close();
                } catch (final Exception e) {
                    log.error("Error closing button: {}", e.getMessage());
                }
            }

            // 3. Final hardware cleanup via Base (SPI/Arena/Context)
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
        System.exit(new CommandLine(new ButtonDemo()).execute(args));
    }
}
