/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.device.PwmBacklight;
import com.codeferm.periphery.device.PwmDeviceFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Standalone PWM backlight demonstration using the unified PwmDeviceFactory and PwmBacklight wrapper.
 * <p>
 * Ramps backlight brightness from 10% to 100% in step increments, respects inversion, and cleanly shuts down via single-ownership
 * try-with-resources.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Command(name = "pwm-backlight", description = "Test PWM backlight brightness ramp and inversion via device abstraction factory.")
@Slf4j
public final class PwmBacklightDemo implements java.util.concurrent.Callable<Integer> {

    static {
        NativeLoader.load();
    }

    /**
     * Operation mode: HW (Hardware Sysfs) or SW (Software GPIO Bit-bang).
     */
    @Option(names = {"-m", "--mode"}, description = "Mode: HW or SW.", defaultValue = "HW")
    private String mode;

    /**
     * Hardware PWM chip index or Software GPIO chip device path.
     */
    @Option(names = {"-d", "--device"}, description = "PWM Chip or GPIO Dev.", defaultValue = "0")
    private String device;

    /**
     * Hardware PWM channel or Software GPIO line index.
     */
    @Option(names = {"-c", "--channel"}, description = "PWM Channel or GPIO Line.", defaultValue = "0")
    private int channel;

    /**
     * Enable PWM backlight active-low inversion.
     */
    @Option(names = {"--pwm-inverted"}, description = "Enable PWM backlight active-low inversion, ${DEFAULT-VALUE} by default.")
    private boolean pwmInverted = false;

    /**
     * PWM signal period in nanoseconds (default 1ms / 1kHz).
     */
    @Option(names = {"-p", "--period"}, description = "PWM period in nanoseconds, ${DEFAULT-VALUE} by default.", defaultValue
            = "1000000")
    private long pwmPeriod;

    /**
     * Delay in milliseconds between brightness steps.
     */
    @Option(names = {"--step-delay"}, description = "Delay in milliseconds between brightness steps, ${DEFAULT-VALUE} by default.",
            defaultValue = "300")
    private long stepDelay;

    /**
     * Executes the PWM backlight brightness ramp demonstration using single-ownership management.
     *
     * @return Exit code (0 for success, 1 for failure).
     */
    @Override
    public Integer call() {
        log.info("Starting PwmBacklightDemo [Mode: {}, Device: {}, Channel: {}, Inverted: {}]",
                mode, device, channel, pwmInverted);

        // Single Ownership via try-with-resources ensures proper cleanup and unexporting
        try (final var backlight = new PwmBacklight(PwmDeviceFactory.create(mode, device, channel), pwmInverted)) {

            // 1. Set initial period and turn off (safe state) BEFORE enabling, matching bash flow
            log.info("Configuring initial period ({ns}) and turning backlight off...", pwmPeriod);
            backlight.setBrightness(pwmPeriod, 0.0); // 0% perceived brightness (100% duty for inverted, 0% for normal)

            // 2. Enable PWM after configuration is set
            backlight.enable();

            log.info("PWM enabled. Starting brightness ramp from 10% to 100%...");

            // Sweep brightness from 10% to 100% in steps of 10%
            for (var brightness = 10; brightness <= 100; brightness += 10) {
                final var percentage = brightness / 100.0;
                log.info("Setting brightness to {}%", brightness);
                backlight.setBrightness(pwmPeriod, percentage);

                TimeUnit.MILLISECONDS.sleep(stepDelay);
            }

            log.info("Ramp complete. Holding full brightness for 2 seconds...");
            TimeUnit.MILLISECONDS.sleep(2000);

            log.info("Disabling PWM backlight.");
            backlight.disable();

            return 0;
        } catch (final Exception e) {
            log.error("PwmBacklightDemo failure: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Main entry point for the standalone PWM backlight demo.
     *
     * @param commandArgs Command line arguments.
     */
    public static void main(final String... commandArgs) {
        System.exit(new picocli.CommandLine(new PwmBacklightDemo()).execute(commandArgs));
    }
}
