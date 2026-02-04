/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import lombok.extern.slf4j.Slf4j;
import org.periphery.NativeLoader;
import org.periphery.Periphery;
import org.periphery.gpio_handle;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.time.Instant;

@Slf4j
public class GpioPerf {

    static {
        NativeLoader.load();
    }

    public static void main(String[] args) {
        String chipPath = "/dev/gpiochip0";
        int line = 77;
        int samples = 10_000_000;

        try (var arena = Arena.ofConfined()) {
            MemorySegment gpioHandle = arena.allocate(gpio_handle.layout());
            MemorySegment path = arena.allocateFrom(chipPath);

            log.info("Starting FFM GPIO Perf on {} line {}", chipPath, line);

            // --- WRITE TEST ---
            int ret = Periphery.gpio_open(gpioHandle, path, line, Periphery.GPIO_DIR_OUT());
            if (ret < 0) {
                log.error("Open Failed: {}", Periphery.gpio_errmsg(gpioHandle).getString(0));
                return;
            }

            log.info("Running write test with {} samples...", samples);
            Instant start = Instant.now();
            for (int i = 0; i < samples; i++) {
                Periphery.gpio_write(gpioHandle, true);
                Periphery.gpio_write(gpioHandle, false);
            }
            Instant finish = Instant.now();

            long ms = Duration.between(start, finish).toMillis();
            log.info(String.format("%.2f toggles per second", ((double) samples * 2 / ms) * 1000));
            Periphery.gpio_close(gpioHandle);

            // --- READ TEST ---
            // Re-open in INPUT mode
            Periphery.gpio_open(gpioHandle, path, line, Periphery.GPIO_DIR_IN());
            MemorySegment valuePtr = arena.allocate(ValueLayout.JAVA_BOOLEAN);

            log.info("Running read test with {} samples...", samples);
            start = Instant.now();
            for (int i = 0; i < samples; i++) {
                Periphery.gpio_read(gpioHandle, valuePtr);
            }
            finish = Instant.now();

            ms = Duration.between(start, finish).toMillis();
            log.info(String.format("%.2f reads per second", ((double) samples / ms) * 1000));

            Periphery.gpio_close(gpioHandle);
            log.info("Performance test completed.");

        } catch (Exception e) {
            log.error("Hardware error", e);
        }
    }
}
