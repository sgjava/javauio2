/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.MultiRgbLed;
import com.codeferm.periphery.device.PwmDeviceFactory;
import com.codeferm.periphery.device.PwmLed;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance RGB PWM fading demo using the unified PwmDevice interface.
 * <p>
 * This demo utilizes the Single-Ownership pattern where the {@link MultiRgbLed} acts as the root controller for three
 * {@link PwmLed} instances. The use of {@link PwmDeviceFactory} ensures that the fading logic is transport-agnostic, supporting
 * both native hardware PWM and software-timed GPIO toggling.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.1.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "RgbFfmPwm",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "Fades an RGB LED using FFM-backed PWM.")
public final class RgbLed extends AbstractDemo {

    /**
     * Operation mode: HW (Hardware Sysfs) or SW (Software GPIO Bit-bang).
     */
    @Option(names = {"-m", "--mode"}, description = "Mode: HW or SW.", defaultValue = "HW")
    private String mode;

    /**
     * Hardware PWM chip index or Software GPIO chip device path.
     */
    @Option(names = {"-d", "--device"}, description = "GPIO device or PWM chip.", defaultValue = "0")
    private String device;

    /**
     * Red channel index or GPIO line.
     */
    @Option(names = {"-r", "--red"}, description = "Red line/channel.", defaultValue = "0")
    private int redLine;

    /**
     * Green channel index or GPIO line.
     */
    @Option(names = {"-g", "--green"}, description = "Green line/channel.", defaultValue = "1")
    private int greenLine;

    /**
     * Blue channel index or GPIO line.
     */
    @Option(names = {"-b", "--blue"}, description = "Blue line/channel.", defaultValue = "2")
    private int blueLine;

    /**
     * Total demo duration in seconds.
     */
    @Option(names = {"-t", "--timeout"}, description = "Run duration in seconds.", defaultValue = "60")
    private int timeout;

    /**
     * 1ms period (1kHz) in nanoseconds for flicker-free fading.
     */
    private static final long PERIOD_NS = 1_000_000L;

    /**
     * Orchestrates the RGB fading sequence.
     * <p>
     * Initializes the composite LED device and iterates through color fades until the timeout is reached or the thread is
     * interrupted.
     * </p>
     *
     * @return Exit code (0 for success, 1 for failure).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        addTerminalHook();
        log.info("Starting RGB PWM Demo [Mode: {}, Device: {}]", mode, device);

        // Single Ownership via createLed() factory helper
        try (final var led = createLed()) {
            led.enable();
            final var endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeout);

            while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
                log.debug("Fading Red...");
                fade(led, 0, endTime);
                log.debug("Fading Green...");
                fade(led, 1, endTime);
                log.debug("Fading Blue...");
                fade(led, 2, endTime);
            }

            led.off(PERIOD_NS);
            led.disable();
            log.info("Demo completed successfully.");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Demo interrupted during sleep.");
            return 1;
        } catch (final Exception e) {
            log.error("PWM Demo failed: {}", e.getMessage());
            return 1;
        }
        return 0;
    }

    /**
     * Fades a specific color channel up and down.
     *
     * @param led The MultiRgbLed instance.
     * @param channel Channel index (0=R, 1=G, 2=B).
     * @param endTime Cutoff timestamp for the demo.
     * @throws InterruptedException If the thread is interrupted during the 10ms step sleep.
     */
    private void fade(final MultiRgbLed led, final int channel, final long endTime) throws InterruptedException {
        // Fade In
        for (var i = 0L; i <= 100; i++) {
            if (System.currentTimeMillis() >= endTime) {
                return;
            }
            updateChannel(led, channel, i);
            TimeUnit.MILLISECONDS.sleep(10);
        }
        // Fade Out
        for (var i = 100L; i >= 0; i--) {
            if (System.currentTimeMillis() >= endTime) {
                return;
            }
            updateChannel(led, channel, i);
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    /**
     * Calculates and applies duty cycles for a specific channel while silencing others.
     *
     * @param led The RGB LED device.
     * @param channel The target color channel.
     * @param percent Brightness percentage (0-100).
     */
    private void updateChannel(final MultiRgbLed led, final int channel, final long percent) {
        final var dc = (PERIOD_NS * percent) / 100;
        final var r = (channel == 0) ? dc : 0L;
        final var g = (channel == 1) ? dc : 0L;
        final var b = (channel == 2) ? dc : 0L;
        led.setRgb(PERIOD_NS, r, g, b);
    }

    /**
     * Factory helper to construct a MultiRgbLed with injected transports.
     * <p>
     * This method creates three distinct PWM transports based on CLI options and wraps them into a single high-level controller.
     * </p>
     *
     * @return Fully initialized MultiRgbLed instance.
     */
    private MultiRgbLed createLed() {
        return new MultiRgbLed(
                new PwmLed(PwmDeviceFactory.create(mode, device, redLine)),
                new PwmLed(PwmDeviceFactory.create(mode, device, greenLine)),
                new PwmLed(PwmDeviceFactory.create(mode, device, blueLine))
        );
    }

    /**
     * Main entry point for the RGB FFM Demo.
     *
     * @param args CLI arguments.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new RgbLed()).execute(args));
    }
}
