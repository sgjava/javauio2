/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.Ssd1331;
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
 * Base CLI provider for SSD1331 hardware demonstrations.
 * <p>
 * Handles FFM (Foreign Function & Memory) native library loading, SPI/GPIO initialization, and provides a Java2D {@link Graphics2D}
 * drawing surface. Implements a robust shutdown sequence to ensure the OLED panel is powered down correctly on exit.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Data
@Slf4j
public class Base implements Callable<Integer> {

    /**
     * Load native periphery libraries for FFM access.
     */
    static {
        NativeLoader.load();
    }

    @Option(names = {"-d", "--device"}, description = "SPI device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/spidev0.0";

    @Option(names = {"-m", "--mode"}, description = "SPI mode, ${DEFAULT-VALUE} by default.")
    private int mode = 3;

    @Option(names = {"-s", "--speed"}, description = "Max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int speed = 7000000;

    @Option(names = {"-g", "--gpio-device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private String gpioDevice = "/dev/gpiochip0";

    @Option(names = {"-dc", "--dc-line"}, description = "DC line, ${DEFAULT-VALUE} by default.")
    private int dc = 24;

    @Option(names = {"-res", "--res-line"}, description = "RES line, ${DEFAULT-VALUE} by default.")
    private int res = 25;

    @Option(names = {"-f", "--fps"}, description = "Target frames per second, ${DEFAULT-VALUE} by default.")
    private int fps = 60;

    @Option(names = {"-t", "--time"}, description = "Seconds to run before exit, ${DEFAULT-VALUE} by default.")
    private int runTime = 60;

    @Option(names = {"--sleep"}, description = "Milliseconds to sleep for text/graphics, ${DEFAULT-VALUE} by default.")
    private long sleep = 5000;

    @Option(names = {"--snapshot"}, description = "Seconds after start to capture screen, 0 to disable.")
    private int snapshotTime = 0;

    /**
     * SSD1331 hardware driver instance.
     */
    private Ssd1331 oled;

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
     * * Flag to signal rendering loops to terminate. Volatile ensures visibility across timer and shutdown hook threads.
     */
    private volatile boolean running = true;

    /**
     * Bootstraps hardware and initializes the rendering context.
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
        log.info("Initializing SSD1331 on {} (speed: {}Hz)", device, speed);
        oled = new Ssd1331(device, mode, speed, gpioDevice, dc, res);
        width = oled.getWidth();
        height = oled.getHeight();
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
        }, "SSD1331-Cleanup-Hook"));
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
        // Snapshot timer to capture the current image buffer to disk
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
     * Captures the current state of the {@code BufferedImage} and saves it as a PNG.
     *
     * * @param fileName The name of the file to save.
     */
    public void saveSnapshot(final String fileName) {
        try {
            final var outputFile = new File(fileName);
            if (ImageIO.write(image, "png", outputFile)) {
                log.info("Snapshot saved to: {}", outputFile.getAbsolutePath());
            }
        } catch (final Exception e) {
            log.error("Failed to save snapshot: {}", e.getMessage());
        }
    }

    /**
     * Executes hardware teardown and releases native resources.
     * <p>
     * Sends the {@code 0xAE} (Display OFF) command to the SSD1331 to prevent "burn-in" or image persistence after the process
     * terminates.
     * </p>
     */
    public void done() {
        synchronized (this) {
            if (oled != null) {
                try {
                    // Send hardware-level power down command
                    oled.writeCommand(new byte[]{(byte) 0xAE});
                    oled.clear();
                    oled.close();
                    log.info("Hardware resources released.");
                } catch (final Exception e) {
                    log.error("Cleanup failed: {}", e.getMessage());
                }
                oled = null;
            }
            if (g2d != null) {
                g2d.dispose();
                g2d = null;
            }
        }
    }
}
