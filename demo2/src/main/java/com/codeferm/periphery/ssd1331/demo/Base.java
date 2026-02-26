/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.ssd1331.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.Ssd1331;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

/**
 * Base CLI for SSD1331 provides hardware initialization and a Graphics2D canvas.
 * <p>
 * This class serves as the application base for SSD1331 demos. It handles native library loading via a static block and manages the
 * life-cycle of FFM-based hardware resources.
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
     * Load native libraries once for all inheriting applications.
     */
    static {
        NativeLoader.load();
    }

    /**
     * SPI device option. Raspberry Pi default is usually /dev/spidev0.0.
     */
    @Option(names = {"-d", "--device"}, description = "SPI device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/spidev0.0";

    /**
     * SPI mode option.
     */
    @Option(names = {"-m", "--mode"}, description = "SPI mode, ${DEFAULT-VALUE} by default.")
    private int mode = 3;

    /**
     * SPI Hz speed option. 7 MHz is stable for SSD1331 on Pi 4/5.
     */
    @Option(names = {"-s", "--speed"}, description = "Max speed in Hz, ${DEFAULT-VALUE} by default.")
    private int speed = 7000000;

    /**
     * GPIO device option. Standard Pi character device.
     */
    @Option(names = {"-g", "--gpio-device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private String gpioDevice = "/dev/gpiochip0";

    /**
     * DC (Data/Command) line option. Defaulting to BCM 24.
     */
    @Option(names = {"-dc", "--dc-line"}, description = "DC line, ${DEFAULT-VALUE} by default.")
    private int dc = 24;

    /**
     * RES (Reset) line option. Defaulting to BCM 25.
     */
    @Option(names = {"-res", "--res-line"}, description = "RES line, ${DEFAULT-VALUE} by default.")
    private int res = 25;

    /**
     * Target frames per second.
     */
    @Option(names = {"-f", "--fps"}, description = "Target frames per second, ${DEFAULT-VALUE} by default.")
    private int fps = 60;

    /**
     * Milliseconds to sleep for text and graphics.
     */
    @Option(names = {"--sleep"}, description = "Milliseconds to sleep for text and graphics, ${DEFAULT-VALUE} by default.")
    private long sleep = 5000;

    /**
     * SSD1331 driver instance.
     */
    private Ssd1331 oled;

    /**
     * Off-screen image buffer.
     */
    private BufferedImage image;

    /**
     * Graphics context for the off-screen image.
     */
    private Graphics2D g2d;

    /**
     * Display width cached from driver.
     */
    private int width;

    /**
     * Display height cached from driver.
     */
    private int height;

    /**
     * Initialize hardware and Java2D resources.
     * <p>
     * Uses var for local inference and final for immutables.
     * </p>
     *
     * @return Exit code.
     * @throws Exception Possible hardware or native memory exception.
     */
    @Override
    public Integer call() throws Exception {
        log.info("Initializing SSD1331 on {} (speed: {}Hz)", device, speed);

        // FFM driver initialization
        oled = new Ssd1331(device, mode, speed, gpioDevice, dc, res);

        // Cache dimensions locally
        width = oled.getWidth();
        height = oled.getHeight();

        // Initialize Java2D resources using optimized TYPE_INT_RGB
        final var bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        this.image = bufferedImage;
        this.g2d = bufferedImage.createGraphics();

        log.info("Display initialized: {}x{}", width, height);
        return 0;
    }

    /**
     * Close hardware resources and dispose of graphics context.
     * <p>
     * Safely closes the FFM-managed Ssd1331 driver.
     * </p>
     */
    public void done() {
        if (g2d != null) {
            g2d.dispose();
        }
        if (oled != null) {
            oled.close();
        }
        log.info("Hardware resources released.");
    }
}
