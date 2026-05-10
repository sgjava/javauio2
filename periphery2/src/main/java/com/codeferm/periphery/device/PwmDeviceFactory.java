/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.Pwm;
import com.codeferm.periphery.SoftPwm;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating {@link PwmDevice} implementations.
 * <p>
 * This factory abstracts the creation of Pulse Width Modulation transport layers, allowing the application to toggle between
 * kernel-level hardware PWM (sysfs) and high-priority software-timed GPIO PWM without changing device-level logic.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class PwmDeviceFactory {

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private PwmDeviceFactory() {
        throw new AssertionError("Utility class should not be instantiated.");
    }

    /**
     * Creates a {@link PwmDevice} implementation based on the specified mode.
     * <p>
     * If the mode is "HW", the {@code device} parameter is expected to be a string representing the PWM chip (e.g., "/dev/pwmchip0"
     * or "0"). If the mode is "SW", the {@code device} parameter should be the GPIO chip path (e.g., "/dev/gpiochip0").
     * </p>
     *
     * @param mode Execution mode: "HW" for hardware-backed, "SW" for software-timed.
     * @param device The path to the hardware device or the chip identifier.
     * @param line The channel index for hardware or the GPIO line offset for software.
     * @return A concrete implementation of the PwmDevice interface.
     * @throws IllegalArgumentException If an unsupported mode is provided.
     * @throws NumberFormatException If the hardware device string does not contain a valid chip index.
     */
    public static PwmDevice create(final String mode, final String device, final int line) {
        final var transportMode = mode.toUpperCase();

        return switch (transportMode) {
            case "HW" -> {
                // Extracts the first numeric sequence found in the string
                final var chipIndex = Integer.parseInt(device.replaceAll("[^0-9]", ""));
                log.atDebug().log("Initializing Hardware PWM: chip {}, channel {}", chipIndex, line);
                yield new Pwm(chipIndex, line);
            }
            case "SW" -> {
                log.atDebug().log("Initializing Software PWM: {} line {}", device, line);
                yield new SoftPwm(device, line);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported PWM mode: %s. Use 'HW' or 'SW'.".formatted(mode)
                );
        };
    }
}
