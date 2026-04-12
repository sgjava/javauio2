/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * GPIO performance test using FFM (Foreign Function & Memory API).
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "GpioPerf", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Test GPIO performance using FFM bindings.")
public class GpioPerf implements Callable<Integer> {

    static {
        // Load the native library before any FFM calls
        NativeLoader.load();
    }

    @Option(names = {"-d", "--device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private String device = "/dev/gpiochip0";

    @Option(names = {"-l", "--line"}, description = "GPIO line, ${DEFAULT-VALUE} by default.")
    private int line = 77;

    @Option(names = {"-s", "--samples"}, description = "Samples to run, ${DEFAULT-VALUE} by default.")
    private int samples = 10000000;

    @Override
    public Integer call() {
        // Use Arena to manage the lifecycle of native handles and strings
        try (var arena = Arena.ofConfined()) {
            // Allocate the gpio_handle struct (size discovered by sizer at build time)
            var handle = arena.allocate(org.periphery.gpio_handle.layout());
            var cDevice = arena.allocateFrom(device);

            log.info("Opening {} line {}", device, line);

            // 1. Write Test
            // Direction: 1 = OUT (defined in c-periphery/src/gpio.h)
            if (Periphery.gpio_open(handle, cDevice, line, 1) < 0) {
                log.error("Failed to open GPIO for write");
                return 1;
            }

            log.info("Running write test with {} samples", samples);
            var start = Instant.now();
            for (var i = 0; i < samples; i++) {
                Periphery.gpio_write(handle, true);
                Periphery.gpio_write(handle, false);
            }
            var finish = Instant.now();

            log.info(String.format("%.2f writes per second (on/off)",
                    ((double) samples / Duration.between(start, finish).toMillis()) * 1000));

            Periphery.gpio_close(handle);

            // 2. Read Test
            // Direction: 0 = IN
            if (Periphery.gpio_open(handle, cDevice, line, 0) < 0) {
                log.error("Failed to open GPIO for read");
                return 1;
            }

            log.info("Running read test with {} samples", samples);
            start = Instant.now();
            var stateBuffer = arena.allocate(java.lang.foreign.ValueLayout.JAVA_BOOLEAN);
            for (var i = 0; i < samples; i++) {
                Periphery.gpio_read(handle, stateBuffer);
            }
            finish = Instant.now();

            log.info(String.format("%.2f reads per second",
                    ((double) samples / Duration.between(start, finish).toMillis()) * 1000));

            Periphery.gpio_close(handle);

        } catch (Exception e) {
            log.error("GPIO test failed", e);
            return 1;
        }
        return 0;
    }

    public static void main(String... args) {
        var exitCode = new CommandLine(new GpioPerf()).execute(args);
        System.exit(exitCode);
    }
}
