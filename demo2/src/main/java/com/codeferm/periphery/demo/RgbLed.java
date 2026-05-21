/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.device.MultiRgbLed;
import com.codeferm.periphery.device.PwmDeviceFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance Polymorphic RGB PWM Demo using the unified PwmDevice interface.
 * <p>
 * This demo utilizes the Single-Ownership pattern where the {@link MultiRgbLed} acts as the root controller for three polymorphic
 * channels. The use of {@link PwmDeviceFactory} ensures that the execution logic is transport-agnostic, supporting both native
 * hardware PWM and software-timed GPIO toggling across multiple animation styles.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "RgbLed",
        mixinStandardHelpOptions = true,
        version = "1.1.0",
        description = "Fades or sweeps an RGB LED spectrum using FFM-backed PWM.")
public final class RgbLed extends AbstractDemo {

    /**
     * Animation pattern choices.
     */
    public enum Pattern {
        FADE, SPECTRUM
    }

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
     * Active animation sequence strategy choice. Defaults to the comprehensive multi-channel spectrum mix.
     */
    @Option(names = {"-p", "--pattern"}, description = "Pattern: FADE or SPECTRUM.", defaultValue = "SPECTRUM")
    private Pattern pattern;

    /**
     * 1ms period (1kHz) in nanoseconds for flicker-free fading.
     */
    private static final long PERIOD_NS = 1_000_000L;

    /**
     * Orchestrates the RGB fading or spectrum sequence.
     * <p>
     * Initializes the composite LED device and routes execution to the chosen runtime loop pattern until the timeout is crossed.
     * </p>
     *
     * @return Exit code (0 for success, 1 for failure).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        addTerminalHook();
        log.info("Starting RGB PWM Demo [Mode: {}, Pattern: {}, Device: {}]", this.mode, this.pattern, this.device);

        // Single Ownership via polymorphic factory mappings
        try (final var led = createLed()) {
            led.enable();
            final var endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(this.timeout);

            if (this.pattern == Pattern.SPECTRUM) {
                runSpectrumLoop(led, endTime);
            } else {
                runFadeLoop(led, endTime);
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
     * Runs the sequential single-line discrete fading loop pattern.
     *
     * @param led The multi-channel composite manager.
     * @param endTime Cutoff timestamp threshold context.
     * @throws InterruptedException On execution loop break.
     */
    private void runFadeLoop(final MultiRgbLed led, final long endTime) throws InterruptedException {
        while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
            log.debug("Fading Red...");
            fade(led, 0, endTime);
            log.debug("Fading Green...");
            fade(led, 1, endTime);
            log.debug("Fading Blue...");
            fade(led, 2, endTime);
        }
    }

    /**
     * Runs the zero-allocation color spectrum wheel blend loop.
     *
     * @param led The multi-channel composite manager.
     * @param endTime Cutoff timestamp threshold context.
     * @throws InterruptedException On execution loop break.
     */
    private void runSpectrumLoop(final MultiRgbLed led, final long endTime) throws InterruptedException {
        log.info("Beginning zero-allocation spectrum sweep...");
        var hue = 0;

        while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
            long rNs = 0L;
            long gNs = 0L;
            long bNs = 0L;

            // Map color sectors directly to nanosecond ratios without object transformations
            if (hue < 60) {
                rNs = PERIOD_NS;
                gNs = (PERIOD_NS * hue) / 60;
            } else if (hue < 120) {
                rNs = (PERIOD_NS * (120 - hue)) / 60;
                gNs = PERIOD_NS;
            } else if (hue < 180) {
                gNs = PERIOD_NS;
                bNs = (PERIOD_NS * (hue - 120)) / 60;
            } else if (hue < 240) {
                gNs = (PERIOD_NS * (240 - hue)) / 60;
                bNs = PERIOD_NS;
            } else if (hue < 300) {
                rNs = (PERIOD_NS * (hue - 240)) / 60;
                bNs = PERIOD_NS;
            } else {
                rNs = PERIOD_NS;
                bNs = (PERIOD_NS * (360 - hue)) / 60;
            }

            led.setRgb(PERIOD_NS, rNs, gNs, bNs);
            hue = (hue + 1) % 360;

            TimeUnit.MILLISECONDS.sleep(15);
        }
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
     * Feeds the factory polymorphic interfaces directly into the composite holder.
     * </p>
     *
     * @return Fully initialized MultiRgbLed instance.
     */
    private MultiRgbLed createLed() {
        return new MultiRgbLed(
                PwmDeviceFactory.create(this.mode, this.device, this.redLine),
                PwmDeviceFactory.create(this.mode, this.device, this.greenLine),
                PwmDeviceFactory.create(this.mode, this.device, this.blueLine)
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
