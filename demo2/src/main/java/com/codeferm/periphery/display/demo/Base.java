/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.AbstractColorDisplay;
import com.codeferm.periphery.device.AbstractTouch;
import com.codeferm.periphery.device.Ili9341;
import com.codeferm.periphery.device.Ssd1331;
import com.codeferm.periphery.device.St7789;
import com.codeferm.periphery.device.Xpt2046;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

/**
 * Unified base CLI provider for hardware display and touch demonstrations (u8g2 style).
 * <p>
 * Handles FFM native library loading, SPI/GPIO initialization for both display and touch controllers, calibration properties
 * management, and provides a unified Java2D {@link Graphics2D} drawing surface, along with generic touch rotation mapping and
 * hit-testing abstractions.
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

    /**
     * Touchable element interface for generic layout hit testing.
     */
    public interface TouchableElement {

        /**
         * Gets the bounding rectangle of the touchable element.
         *
         * @return Rectangle bounds.
         */
        Rectangle getBounds();
    }

    @Option(names = {"--display-type"}, description = "Display type (ST7789, ILI9341, SSD1331), ${DEFAULT-VALUE} by default.")
    private String displayType = "ST7789";

    @Option(names = {"-d", "--device"}, description = "SPI device for display, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/spidev0.0";

    @Option(names = {"-m", "--mode"}, description = "SPI mode, ${DEFAULT-VALUE} by default.")
    private int mode = 0;

    @Option(names = {"-s", "--speed"}, description = "Display max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int speed = 32000000;

    @Option(names = {"-g", "--gpio-device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private String gpioDevice = "/dev/gpiochip0";

    @Option(names = {"-dc", "--dc-line"}, description = "DC line, ${DEFAULT-VALUE} by default.")
    private int dc = 71;

    @Option(names = {"-res", "--res-line"}, description = "RES line (SSD1331/ILI9341), ${DEFAULT-VALUE} by default.")
    private int res = 25;

    @Option(names = {"-led", "--led-line"}, description = "LED backlight line (ILI9341), ${DEFAULT-VALUE} by default.")
    private int led = 24;

    @Option(names = {"-b", "--buffer-size"}, description = "SPI transfer buffer chunk size in bytes, ${DEFAULT-VALUE} by default.")
    private int bufferSize = 65536;

    @Option(names = {"-r", "--rotation"}, description = "Display rotation (0, 90, 180, 270), ${DEFAULT-VALUE} by default.")
    private int rotation = 0;

    // --- Touch Configuration Options ---
    @Option(names = {"--touch-type"}, description = "Touch type (XPT2046, NONE), ${DEFAULT-VALUE} by default.")
    private String touchType = "NONE";

    @Option(names = {"--touch-device"}, description = "SPI device for touch, ${DEFAULT-VALUE} by default.")
    private String touchDevice = "/dev/spidev1.0";

    @Option(names = {"--touch-mode"}, description = "Touch SPI mode, ${DEFAULT-VALUE} by default.")
    private int touchMode = 0;

    @Option(names = {"--touch-speed"}, description = "Touch SPI max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int touchSpeed = 2000000;

    @Option(names = {"--touch-irq-line"}, description = "Touch IRQ GPIO line offset, ${DEFAULT-VALUE} by default.")
    private int touchIrqLine = 34;

    @Option(names = {"-fp", "--touch-properties"}, description = "Touch properties configuration file, ${DEFAULT-VALUE} by default.")
    private String touchPropertiesFile = "touch.properties";

    @Option(names = {"-f", "--fps"}, description = "Target frames per second, ${DEFAULT-VALUE} by default.")
    private int fps = 60;

    @Option(names = {"-t", "--time"}, description = "Seconds to run before exit, ${DEFAULT-VALUE} by default.")
    private int runTime = 60;

    @Option(names = {"--sleep"}, description = "Milliseconds to sleep for text/graphics, ${DEFAULT-VALUE} by default.")
    private long sleep = 5000;

    @Option(names = {"--snapshot"}, description = "Seconds after start to capture screen, 0 to disable.")
    private int snapshotTime = 0;

    /**
     * Calibration bounds loaded from properties file.
     */
    protected double minRawX = 0;
    protected double maxRawX = 4095;
    protected double minRawY = 0;
    protected double maxRawY = 4095;

    /**
     * Unified abstract color display driver instance.
     */
    private AbstractColorDisplay display;

    /**
     * Unified abstract touch controller instance.
     */
    private AbstractTouch touch;

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
     * Load touch calibration properties from disk if available.
     */
    protected final void loadTouchProperties() {
        final var props = new Properties();
        try (final var in = new FileInputStream(touchPropertiesFile)) {
            props.load(in);
            minRawX = Double.parseDouble(props.getProperty("min.raw.x", String.valueOf(minRawX)));
            maxRawX = Double.parseDouble(props.getProperty("max.raw.x", String.valueOf(maxRawX)));
            minRawY = Double.parseDouble(props.getProperty("min.raw.y", String.valueOf(minRawY)));
            maxRawY = Double.parseDouble(props.getProperty("max.raw.y", String.valueOf(maxRawY)));
            log.info("Loaded touch calibration from {}: minX={}, maxX={}, minY={}, maxY={}",
                    touchPropertiesFile, minRawX, maxRawX, minRawY, maxRawY);
        } catch (final Exception e) {
            log.warn("Failed to load {} (using defaults): {}", touchPropertiesFile, e.getMessage());
        }
    }

    /**
     * Maps raw touch coordinates to screen coordinates, automatically handling rotation and calibration bounds.
     *
     * @param rawX Raw X coordinate from sensor.
     * @param rawY Raw Y coordinate from sensor.
     * @return Mapped screen point.
     */
    public final java.awt.Point mapTouchToScreen(final int rawX, final int rawY) {
        final var displayWidth = display.getWidth();
        final var displayHeight = display.getHeight();

        final double normX = Math.clamp((rawX - minRawX) / (maxRawX - minRawX), 0.0, 1.0);
        final double normY = Math.clamp((rawY - minRawY) / (maxRawY - minRawY), 0.0, 1.0);

        double screenNormX;
        double screenNormY;

        switch (rotation) {
            case 90 -> {
                screenNormX = normY;
                screenNormY = 1.0 - normX;
            }
            case 180 -> {
                screenNormX = 1.0 - normX;
                screenNormY = 1.0 - normY;
            }
            case 270 -> {
                screenNormX = 1.0 - normY;
                screenNormY = normX;
            }
            default -> {
                screenNormX = normX;
                screenNormY = normY;
            }
        }

        final var screenX = (int) (screenNormX * displayWidth);
        final var screenY = (int) (screenNormY * displayHeight);
        return new java.awt.Point(Math.clamp(screenX, 0, displayWidth - 1), Math.clamp(screenY, 0, displayHeight - 1));
    }

    /**
     * Automatically maps raw touch coordinates (handling calibration and rotation) and performs hit-testing against a collection of
     * touchable elements.
     *
     * @param rawX Raw X coordinate from sensor.
     * @param rawY Raw Y coordinate from sensor.
     * @param elements Array of touchable elements.
     * @return The matched TouchableElement, or null if nothing was hit.
     */
    public final TouchableElement findTouchedElement(final int rawX, final int rawY, final TouchableElement[] elements) {
        final var screenPoint = mapTouchToScreen(rawX, rawY);

        if (elements != null) {
            for (final var element : elements) {
                if (element.getBounds().contains(screenPoint)) {
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * Bootstraps hardware and initializes the rendering context based on the selected display and touch types.
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
        log.info("Initializing display type {} on {} (speed: {}Hz, rotation: {}°)", displayType, device, speed, rotation);
        final var targetDisplay = switch (displayType != null ? displayType.toUpperCase() : "") {
            case "ST7789" ->
                new St7789(device, mode, speed, gpioDevice, dc, bufferSize);
            case "ILI9341" ->
                new Ili9341(device, mode, speed, gpioDevice, dc, res, led, bufferSize);
            case "SSD1331" ->
                new Ssd1331(device, mode, speed, gpioDevice, dc, res);
            default ->
                throw new IllegalArgumentException("Unknown display type: " + displayType);
        };
        targetDisplay.setRotation(rotation);
        display = targetDisplay;

        width = display.getWidth();
        height = display.getHeight();

        // Initialize optional touch controller and load calibration properties
        if (touchType != null && !touchType.equalsIgnoreCase("NONE")) {
            log.info("Initializing touch type {} on {} (speed: {}Hz, IRQ line: {})", touchType, touchDevice, touchSpeed,
                    touchIrqLine);
            final var targetTouch = switch (touchType.toUpperCase()) {
                case "XPT2046" ->
                    new Xpt2046(touchDevice, touchMode, touchSpeed, gpioDevice, touchIrqLine);
                default ->
                    throw new IllegalArgumentException("Unknown touch type: " + touchType);
            };
            targetTouch.open();
            touch = targetTouch;
            loadTouchProperties();
        }

        // Initialize high-performance off-screen buffer
        final var bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.image = bufferedImage;
        this.g2d = bufferedImage.createGraphics();

        final var mainThread = Thread.currentThread();
        final var scheduler = Executors.newSingleThreadScheduledExecutor();

        // Ensures display and touch are shut down even if interrupted via Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running) {
                log.info("Ctrl+C detected. Powering down hardware...");
                running = false;
                mainThread.interrupt();
                done();
            }
        }, "Hardware-Cleanup-Hook"));

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
        log.info("Hardware initialized successfully. Auto-exit: {}s, Snapshot: {}s.", runTime, snapshotTime);
        return 0;
    }

    /**
     * Captures the current state of the {@code BufferedImage} and saves it as a PNG with thread safety.
     *
     * @param fileName The name of the file to save.
     */
    public final void saveSnapshot(final String fileName) {
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
     * Executes hardware teardown and releases native resources cleanly across display and touch controllers.
     */
    public final void done() {
        synchronized (this) {
            if (touch != null) {
                try {
                    touch.close();
                    log.info("Touch hardware resources released.");
                } catch (final Exception e) {
                    log.error("Touch cleanup failed: {}", e.getMessage());
                }
                touch = null;
            }
            if (display != null) {
                try {
                    display.clear();
                    display.close();
                    log.info("Display hardware resources released.");
                } catch (final Exception e) {
                    log.error("Display cleanup failed: {}", e.getMessage());
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
