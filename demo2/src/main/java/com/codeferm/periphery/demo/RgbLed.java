/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.SoftPwm;
import com.codeferm.periphery.device.MultiRgbLed;
import com.codeferm.periphery.device.PwmLed;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * High-performance RGB PWM fading demo using the unified PwmDevice interface.
 * <p>
 * Supports both hardware and software PWM modes. This version offloads the timing logic to the PwmDevice implementation,
 * significantly reducing CPU load compared to manual bit-banging in the main loop.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "RgbFfmPwm", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT")
public class RgbLed implements Callable<Integer> {

    static {
        NativeLoader.load();
    }

    @Option(names = {"-m", "--mode"}, description = "Mode: HW or SW.", defaultValue = "SW")
    private String mode;

    @Option(names = {"-d", "--device"}, description = "GPIO device or PWM chip.", defaultValue = "/dev/gpiochip0")
    private String device;

    @Option(names = {"-r", "--red"}, description = "Red line/channel.", defaultValue = "17")
    private int redLine;

    @Option(names = {"-g", "--green"}, description = "Green line/channel.", defaultValue = "27")
    private int greenLine;

    @Option(names = {"-b", "--blue"}, description = "Blue line/channel.", defaultValue = "22")
    private int blueLine;

    @Option(names = {"-t", "--timeout"}, description = "Run duration in seconds.", defaultValue = "60")
    private int timeout;

    /**
     * 1ms period (1kHz) in nanoseconds for smooth fading without flicker.
     */
    private static final long PERIOD_NS = 1_000_000L;

    @Override
    public Integer call() {
        log.info("Starting RGB PWM Demo [Mode: {}, R:{}, G:{}, B:{}]", mode, redLine, greenLine, blueLine);

        // Factory-style initialization based on mode
        try (final var led = createLed()) {
            led.enable();
            final var endTime = System.currentTimeMillis() + (timeout * 1000L);

            while (System.currentTimeMillis() < endTime && !Thread.currentThread().isInterrupted()) {
                // Fade Red
                fade(led, 0, endTime);
                // Fade Green
                fade(led, 1, endTime);
                // Fade Blue
                fade(led, 2, endTime);
            }

            led.off(PERIOD_NS);
            led.disable();
            log.info("Demo completed successfully.");
        } catch (final Exception e) {
            log.error("PWM Demo failed: {}", e.getMessage());
            return 1;
        }
        return 0;
    }

    /**
     * Fades a specific channel up and down.
     *
     * @param led MultiRgbLed instance.
     * @param channel 0=R, 1=G, 2=B.
     * @param endTime Cutoff time for execution.
     * @throws InterruptedException If sleep is interrupted.
     */
    private void fade(final MultiRgbLed led, final int channel, final long endTime) throws InterruptedException {
        // Fade In
        for (var i = 0L; i <= 100; i++) {
            updateChannel(led, channel, i);
            TimeUnit.MILLISECONDS.sleep(10);
            if (System.currentTimeMillis() >= endTime) {
                return;
            }
        }
        // Fade Out
        for (var i = 100L; i >= 0; i--) {
            updateChannel(led, channel, i);
            TimeUnit.MILLISECONDS.sleep(10);
            if (System.currentTimeMillis() >= endTime) {
                return;
            }
        }
    }

    /**
     * Updates the duty cycle for a specific channel while keeping others off.
     */
    private void updateChannel(final MultiRgbLed led, final int channel, final long percent) {
        final var dc = (PERIOD_NS * percent) / 100;
        final var r = (channel == 0) ? dc : 0L;
        final var g = (channel == 1) ? dc : 0L;
        final var b = (channel == 2) ? dc : 0L;
        led.setRgb(PERIOD_NS, r, g, b);
    }

    /**
     * Helper to create the device based on user options.
     */
    private MultiRgbLed createLed() {
        if (mode.equalsIgnoreCase("HW")) {
            final var chip = Integer.parseInt(device.replaceAll("[^0-9]", ""));
            return new MultiRgbLed(
                    new PwmLed(chip, redLine),
                    new PwmLed(chip, greenLine),
                    new PwmLed(chip, blueLine)
            );
        } else {
            return new MultiRgbLed(
                    new SoftPwm(device, redLine),
                    new SoftPwm(device, greenLine),
                    new SoftPwm(device, blueLine)
            );
        }
    }

    public static void main(final String... args) {
        System.exit(new CommandLine(new RgbLed()).execute(args));
    }
}
