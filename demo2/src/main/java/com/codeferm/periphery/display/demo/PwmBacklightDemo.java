/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.display.demo;

import com.codeferm.periphery.device.PwmBacklight;
import com.codeferm.periphery.device.PwmDeviceFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * PWM backlight demonstration extending the unified {@link Base} class.
 * <p>
 * Ramps backlight brightness from 10% to 100% in step increments, respects inversion, and cleanly shuts down.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Command(name = "pwm-backlight", description = "Test PWM backlight brightness ramp and inversion via device abstraction factory.")
@Slf4j
public final class PwmBacklightDemo extends Base {

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
                getPwmMode(), getPwmDevice(), getPwmChannel(), isPwmInverted());

        try (final var backlight = new PwmBacklight(PwmDeviceFactory.create(getPwmMode(), getPwmDevice(), getPwmChannel()),
                isPwmInverted())) {

            log.info("Configuring initial period ({}) and turning backlight off...", getPwmPeriod());
            backlight.setBrightness(getPwmPeriod(), 0.0);

            backlight.enable();

            log.info("PWM enabled. Starting brightness ramp from 10% to 100%...");

            for (var brightness = 10; brightness <= 100; brightness += 10) {
                final var percentage = brightness / 100.0;
                log.info("Setting brightness to {}%", brightness);
                backlight.setBrightness(getPwmPeriod(), percentage);

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
     * Main entry point for the PWM backlight demo.
     *
     * @param commandArgs Command line arguments.
     */
    public static void main(final String... commandArgs) {
        System.exit(new picocli.CommandLine(new PwmBacklightDemo()).execute(commandArgs));
    }
}
