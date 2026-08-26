/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.Ssd1331;
import com.codeferm.periphery.device.St7789;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

/**
 * Unified base CLI provider for hardware display demonstrations (u8g2 style).
 * <p>
 * Handles FFM native library loading, SPI/GPIO initialization, and provides a unified Java2D {@link Graphics2D} drawing surface
 * across different display controllers.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Data
@Slf4j
public abstract class Base implements Callable<Integer> {

    /**
     * Load native periphery libraries for FFM access.
     */
    static {
        NativeLoader.load();
    }

    @Option(names = {"--display-type"}, description = "Display type (ST7789, SSD1331), ${DEFAULT-VALUE} by default.")
    private String displayType = "ST7789";

    @Option(names = {"-d", "--device"}, description = "SPI device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/spidev0.0";

    @Option(names = {"-m", "--mode"}, description = "SPI mode, ${DEFAULT-VALUE} by default.")
    private int mode = 0;

    @Option(names = {"-s", "--speed"}, description = "Max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int speed = 32000000;

    @Option(names = {"-g", "--gpio-device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private String gpioDevice = "/dev/gpiochip0";

    @Option(names = {"-dc", "--dc-line"}, description = "DC line, ${DEFAULT-VALUE} by default.")
    private int dc = 71;

    @Option(names = {"-res", "--res-line"}, description = "RES line (SSD1331 only), ${DEFAULT-VALUE} by default.")
    private int res = 25;

    @Option(names = {"-b", "--buffer-size"}, description = "SPI transfer buffer chunk size in bytes, ${DEFAULT-VALUE} by default.")
    private int bufferSize = 65536;

    @Option(names = {"-f", "--fps"}, description = "Target frames per second, ${DEFAULT-VALUE} by default.")
    private int fps = 60;

    @Option(names = {"-t", "--time"}, description = "Seconds to run before exit, ${DEFAULT-VALUE} by default.")
    private int runTime = 60;

    @Option(names = {"--sleep"}, description = "Milliseconds to sleep for text/graphics, ${DEFAULT-VALUE} by default.")
    private long sleep = 5000;

    @Option(names = {"--snapshot"}, description = "Seconds after start to capture screen, 0 to disable.")
    private int snapshotTime = 0;

    /**
     * Unified abstract color display driver instance.
     */
    private AbstractColorDisplay display;

    /**
     * Off-screen image buffer for Java2D rendering.
     */
    private BufferedImage image;

    /**
     * Graphics context for drawing to the off-screen image.
     */
    private Graphics2D g2d;

    /**
     * Cached display width.
     */
    private int width;

    /**
     * Cached display height.
     */
    private int height;

    /**
     * Flag to signal rendering loops to terminate. Volatile ensures visibility across timer and shutdown hook threads.
     */
    private volatile boolean running = true;

    /**
     * Bootstraps hardware and initializes the rendering context based on the selected display type.
     * <p>
     * Registers a JVM shutdown hook to handle {@code SIGINT} (Ctrl+C) and schedules an automatic exit task based on
     * {@code runTime}.
     * </p>
     *
     * @return Exit code (0 for success).
     * @throws Exception if hardware access is denied or initialization fails.
     */
    @Override
    public Integer call() throws Exception {
        log.info("Initializing display type {} on {} (speed: {}Hz)", displayType, device, speed);

        if ("ST7789".equalsIgnoreCase(displayType)) {
            display = new St7789(device, mode, speed, gpioDevice, dc, bufferSize);
        } else if ("SSD1331".equalsIgnoreCase(displayType)) {
            display = new Ssd1331(device, mode, speed, gpioDevice, dc, res);
        } else {
            throw new IllegalArgumentException("Unknown display type: " + displayType);
        }

        width = display.getWidth();
        height = display.getHeight();

        // Initialize high-performance off-screen buffer
        final var bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.image = bufferedImage;
        this.g2d = bufferedImage.createGraphics();

        final var mainThread = Thread.currentThread();
        final var scheduler = Executors.newSingleThreadScheduledExecutor();

        // Ensures display is turned off even if interrupted via Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                log.info("Ctrl+C detected. Powering down display...");
                running = false;
                mainThread.interrupt();
                done();
            }
        }, "Display-Cleanup-Hook"));

        // Timer to prevent perpetual execution in headless or automated environments
        if (runTime > 0) {
            scheduler.schedule(() -> {
                if (running) {
                    log.info("{} second timer expired. Signalling stop...", runTime);
                    running = false;
                    mainThread.interrupt();
                }
            }, runTime, TimeUnit.SECONDS);
        }

        // Snapshot timer to capture the current image buffer to disk safely
        if (snapshotTime > 0) {
            scheduler.schedule(() -> {
                saveSnapshot(String.format("snapshot_%ds.png", snapshotTime));
            }, snapshotTime, TimeUnit.SECONDS);
        }

        scheduler.shutdown();
        log.info("Display initialized: {}x{}. Auto-exit: {}s, Snapshot: {}s.", width, height, runTime, snapshotTime);
        return 0;
    }

    /**
     * Captures the current state of the {@code BufferedImage} and saves it as a PNG with thread safety.
     *
     * @param fileName The name of the file to save.
     */
    public void saveSnapshot(final String fileName) {
        synchronized (this) {
            try {
                if (image != null) {
                    final var outputFile = new File(fileName);
                    // Create parent directories if required
                    if (outputFile.getParentFile() != null) {
                        outputFile.getParentFile().mkdirs();
                    }
                    if (ImageIO.write(image, "png", outputFile)) {
                        log.info("Snapshot saved to: {}", outputFile.getAbsolutePath());
                    }
                } else {
                    log.error("Failed to save snapshot: image buffer is null.");
                }
            } catch (final Exception e) {
                log.error("Failed to save snapshot: {}", e.getMessage());
            }
        }
    }

    /**
     * Executes hardware teardown and releases native resources cleanly across any display controller.
     */
    public void done() {
        synchronized (this) {
            if (display != null) {
                try {
                    display.clear();
                    display.close(); // Automatically triggers controller-specific closeNative()
                    log.info("Hardware resources released.");
                } catch (final Exception e) {
                    log.error("Cleanup failed: {}", e.getMessage());
                }
                display = null;
            }
            if (g2d != null) {
                g2d.dispose();
                g2d = null;
            }
        }
    }
}
