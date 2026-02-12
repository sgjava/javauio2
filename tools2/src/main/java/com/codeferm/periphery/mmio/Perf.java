/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.mmio;

import com.codeferm.periphery.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;
import org.periphery.mmio_handle;
import picocli.CommandLine;

/**
 * GPIO performance using MMIO.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@CommandLine.Command(name = "Perf", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Show performance of MMIO based GPIO")
public class Perf implements Callable<Integer> {

    static {
        // Load the native library before any FFM calls
        NativeLoader.load();
    }

    /**
     * Input file.
     */
    @CommandLine.Option(names = {"-i", "--in"}, description = "Input property file name, ${DEFAULT-VALUE} by default.")
    private String inFileName = "duo-map.properties";
    /**
     * Device option.
     */
    @CommandLine.Option(names = {"-d", "--device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private int device = 0;
    /**
     * Line option.
     */
    @CommandLine.Option(names = {"-l", "--line"}, description = "GPIO line, ${DEFAULT-VALUE} by default.")
    private int line = 203;
    /**
     * How many samples to run.
     */
    @CommandLine.Option(names = {"-s", "--samples"}, description = "Samples to run, ${DEFAULT-VALUE} by default.")
    private int samples = 10000000;
    /**
     * Run fast only.
     */
    @CommandLine.Option(names = {"-f", "--fast"}, description = "Fast test only, ${DEFAULT-VALUE} by default.")
    private boolean fast = true;

    /**
     * Read pin value.
     *
     * @param arena Arena for temporary allocation.
     * @param pin Pin.
     * @return True = on, false = off.
     */
    public boolean read(final Arena arena, final Pin pin) {
        var valueBuffer = arena.allocate(ValueLayout.JAVA_INT);
        Periphery.mmio_read32(pin.mmioHandle(), pin.dataInOn().offset(), valueBuffer);
        return (valueBuffer.get(ValueLayout.JAVA_INT, 0) & pin.dataInOn().mask()) != 0;
    }

    /**
     * Write pin value.
     *
     * @param arena Arena for temporary allocation.
     * @param pin Pin.
     * @param value True = on, false = off.
     */
    public void write(final Arena arena, final Pin pin, final boolean value) {
        var reg = arena.allocate(ValueLayout.JAVA_INT);
        var dataOutOnOffset = pin.dataOutOn().offset().longValue();
        var dataOutOffOffset = pin.dataOutOff().offset().longValue();
        if (!value) {
            // Get current register value
            Periphery.mmio_read32(pin.mmioHandle(), dataOutOffOffset, reg);
            var currentVal = reg.get(ValueLayout.JAVA_INT, 0);
            // If on and off registers are the same use AND
            if (dataOutOffOffset == dataOutOnOffset) {
                Periphery.mmio_write32(pin.mmioHandle(), dataOutOffOffset, currentVal & pin.dataOutOff().mask());
            } else {
                // If on and off registers are different use OR like Raspberry Pi
                Periphery.mmio_write32(pin.mmioHandle(), dataOutOffOffset, currentVal | pin.dataOutOff().mask());
            }
        } else {
            // Get current register value
            Periphery.mmio_read32(pin.mmioHandle(), dataOutOnOffset, reg);
            var currentVal = reg.get(ValueLayout.JAVA_INT, 0);
            Periphery.mmio_write32(pin.mmioHandle(), dataOutOnOffset, currentVal | pin.dataOutOn().mask());
        }
    }

    /**
     * Performance test using GPIOD.
     *
     * @param arena Arena for native handle allocation.
     * @param pin Pin number.
     * @param samples How many samples to run.
     */
    public void perfGpiod(final Arena arena, final Pin pin, final long samples) {
        var devPath = String.format("/dev/gpiochip%d", pin.key().chip());
        var handle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(devPath);

        if (Periphery.gpio_open(handle, cDev, pin.key().pin(), 1) >= 0) { // 1 = GPIO_DIR_OUT
            try {
                log.info("Running GPIOD write test with {} samples", samples);
                var start = Instant.now();
                for (var i = 0; i < samples; i++) {
                    Periphery.gpio_write(handle, true);
                    Periphery.gpio_write(handle, false);
                }
                var finish = Instant.now();
                var timeElapsed = Duration.between(start, finish).toMillis();
                log.info(String.format("%.2f KHz", ((double) samples / (double) timeElapsed)));
            } finally {
                Periphery.gpio_close(handle);
            }
        }
    }

    /**
     * Performance test using MMIO write method.
     *
     * @param arena Arena for temporary allocations.
     * @param pin Pin number.
     * @param samples How many samples to run.
     */
    public void perfGood(final Arena arena, final Pin pin, final long samples) {
        var devPath = String.format("/dev/gpiochip%d", pin.key().chip());
        var handle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(devPath);

        if (Periphery.gpio_open(handle, cDev, pin.key().pin(), 1) >= 0) {
            try {
                log.info("Running good MMIO write test with {} samples", samples);
                var start = Instant.now();
                for (var i = 0; i < samples; i++) {
                    write(arena, pin, true);
                    write(arena, pin, false);
                }
                var finish = Instant.now();
                var timeElapsed = Duration.between(start, finish).toMillis();
                log.info(String.format("%.2f KHz", ((double) samples / (double) timeElapsed)));
            } finally {
                Periphery.gpio_close(handle);
            }
        }
    }

    /**
     * Performance test using raw MMIO and only reading register once before writes.
     *
     * @param arena Arena for temporary allocations.
     * @param pin Pin number.
     * @param samples How many samples to run.
     */
    public void perfBest(final Arena arena, final Pin pin, final long samples) {
        var devPath = String.format("/dev/gpiochip%d", pin.key().chip());
        var gHandle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(devPath);

        if (Periphery.gpio_open(gHandle, cDev, pin.key().pin(), 1) >= 0) {
            try {
                var handle = pin.mmioHandle();
                var valBuf = arena.allocate(ValueLayout.JAVA_INT);
                var dataOutOnOffset = pin.dataOutOn().offset().longValue();
                var dataOutOffOffset = pin.dataOutOff().offset().longValue();

                // Only do read one time to get current value
                Periphery.mmio_read32(handle, dataOutOnOffset, valBuf);
                var regOnVal = valBuf.get(ValueLayout.JAVA_INT, 0);

                Periphery.mmio_read32(handle, dataOutOffOffset, valBuf);
                var regOffVal = valBuf.get(ValueLayout.JAVA_INT, 0);

                log.info("Running best MMIO write test with {} samples", samples);
                var start = Instant.now();

                if (dataOutOffOffset == dataOutOnOffset) {
                    var on = regOffVal | pin.dataOutOn().mask();
                    var off = regOffVal & (pin.dataOutOff().mask());
                    for (var i = 0; i < samples; i++) {
                        Periphery.mmio_write32(handle, dataOutOnOffset, on);
                        Periphery.mmio_write32(handle, dataOutOffOffset, off);
                    }
                } else {
                    var on = regOnVal | pin.dataOutOn().mask();
                    var off = regOnVal | pin.dataOutOff().mask();
                    for (var i = 0; i < samples; i++) {
                        Periphery.mmio_write32(handle, dataOutOnOffset, on);
                        Periphery.mmio_write32(handle, dataOutOffOffset, off);
                    }
                }
                var finish = Instant.now();
                var timeElapsed = Duration.between(start, finish).toMillis();
                log.info(String.format("%.2f KHz", ((double) samples / (double) timeElapsed)));
            } finally {
                Periphery.gpio_close(gHandle);
            }
        }
    }

    /**
     * Read pin map properties and run performance test.
     *
     * @return Exit code.
     * @throws InterruptedException Possible exception.
     */
    @Override
    public Integer call() throws InterruptedException {
        var exitCode = 0;
        var file = new File();
        final Map<PinKey, Pin> pinMap = file.loadPinMap(inFileName);

        if (!pinMap.isEmpty()) {
            try (var arena = Arena.ofConfined()) {
                final Map<Integer, MemorySegment> mmioHandles = new HashMap<>();

                // Open MMIO for each chip
                for (var i = 0; i < file.chips().size(); i++) {
                    var handle = arena.allocate(mmio_handle.layout());
                    if (Periphery.mmio_open(handle, file.chips().get(i), file.mmioSize().get(i)) >= 0) {
                        mmioHandles.put(file.gpioDev().get(i), handle);
                    } else {
                        log.error("Failed to open MMIO for chip 0x{}", Long.toHexString(file.chips().get(i)));
                    }
                }

                // Set MMIO handle for each pin
                pinMap.values().forEach(p -> p.mmioHandle(mmioHandles.get(p.key().chip())));

                var pin = pinMap.get(new PinKey(device, line));
                if (pin != null) {
                    if (!fast) {
                        perfGpiod(arena, pin, samples);
                        perfGood(arena, pin, samples);
                    }
                    perfBest(arena, pin, samples);
                } else {
                    log.error("Pin not found in map: device {} line {}", device, line);
                }

                // Cleanup
                mmioHandles.values().forEach(Periphery::mmio_close);
            } catch (Exception e) {
                log.error("Performance test error: {}", e.getMessage());
                exitCode = 1;
            }
        } else {
            log.error("Pin map empty. Make sure you have a valid property file.");
            exitCode = 1;
        }
        return exitCode;
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new Perf()).execute(args));
    }
}
