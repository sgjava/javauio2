/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.mmio;

import com.codeferm.periphery.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;
import org.periphery.mmio_handle;
import picocli.CommandLine;

/**
 * Scan memory for changes based on start address, range and GPIO chip and line.
 *
 * Make sure you disable all hardware in armbian-config System, Hardware and remove console=serial from /boot/armbianEnv.txt. You
 * want multi-function pins to act as GPIO pins.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@CommandLine.Command(name = "MemScan", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Use GPIO device to detect memory changes")
public class MemScan implements Callable<Integer> {

    static {
        // Load the native library before any FFM calls
        NativeLoader.load();
    }

    /**
     * MMIO path.
     */
    @CommandLine.Option(names = {"-p", "--path"}, description = "Path defaults to /dev/mem, ${DEFAULT-VALUE} by default.")
    private String path = "/dev/mem";
    /**
     * Memory address.
     */
    @CommandLine.Option(names = {"-a", "--address"}, description = "Memorry address, ${DEFAULT-VALUE} by default.")
    private long address = 0x00;
    /**
     * Memory size to scan.
     */
    @CommandLine.Option(names = {"-w", "--words"}, description = "32 bit words to read, ${DEFAULT-VALUE} by default.")
    private long words = 0x01;
    /**
     * Device option.
     */
    @CommandLine.Option(names = {"-d", "--device"}, description = "GPIO device, ${DEFAULT-VALUE} by default.")
    private int device = 0;
    /**
     * Line option.
     */
    @CommandLine.Option(names = {"-l", "--line"}, description = "GPIO line, ${DEFAULT-VALUE} by default.")
    private int line = 1;

    /**
     * Return values from all registers.
     *
     * @param arena Arena for temporary allocations.
     * @param mmioHandle MMIO handle.
     * @return List of register values.
     */
    public List<Integer> getRegValues(final Arena arena, final MemorySegment mmioHandle) {
        var list = new ArrayList<Integer>();
        var valueBuffer = arena.allocate(ValueLayout.JAVA_INT);
        for (long i = 0; i < words; i++) {
            if (Periphery.mmio_read32(mmioHandle, i * 4, valueBuffer) < 0) {
                log.error("MMIO read failed at offset 0x{:08x}", i * 4);
                list.add(0);
            } else {
                list.add(valueBuffer.get(ValueLayout.JAVA_INT, 0));
            }
        }
        return list;
    }

    /**
     * Compare list values and log difference.
     *
     * @param list1 First list.
     * @param list2 Second list.
     * @param text Description text.
     */
    public void listDiff(final List<Integer> list1, final List<Integer> list2, final String text) {
        for (var i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equals(list2.get(i))) {
                int diff;
                if (list1.get(i) > list2.get(i)) {
                    diff = list1.get(i) - list2.get(i);
                } else {
                    diff = list2.get(i) - list1.get(i);
                }
                log.info(String.format("%s difference found at offset 0x%08x before 0x%08x after 0x%08x difference 0x%08x", text,
                        i * 4, list1.get(i), list2.get(i), diff));
            }
        }
    }

    /**
     * Use GPIO device to detect configuration changes.
     *
     * @param arena Arena for native allocations.
     * @param mmioHandle MMIO handle.
     */
    public void detectMode(final Arena arena, final MemorySegment mmioHandle) {
        var dev = String.format("/dev/gpiochip%d", device);
        var handle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(dev);
        // Set pin for input, output and look for delta
        if (Periphery.gpio_open(handle, cDev, line, 0) >= 0) {
            try {
                var list1 = getRegValues(arena, mmioHandle);
                Periphery.gpio_set_direction(handle, 1); // 1 = GPIO_DIR_OUT
                var list2 = getRegValues(arena, mmioHandle);
                // Show the register delta
                listDiff(list1, list2, "Mode");
            } finally {
                Periphery.gpio_close(handle);
            }
        } else {
            log.error(String.format("Device %d line %d failed to open for Mode detection", device, line));
        }
    }

    /**
     * Use GPIO device to detect data changes.
     *
     * @param arena Arena for native allocations.
     * @param mmioHandle MMIO handle.
     */
    public void detectData(final Arena arena, final MemorySegment mmioHandle) {
        var dev = String.format("/dev/gpiochip%d", device);
        var handle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(dev);
        // Set pin for output and look for delta
        if (Periphery.gpio_open(handle, cDev, line, 1) >= 0) {
            try {
                Periphery.gpio_write(handle, false);
                var list1 = getRegValues(arena, mmioHandle);
                Periphery.gpio_write(handle, true);
                var list2 = getRegValues(arena, mmioHandle);
                // Show the register delta
                listDiff(list1, list2, "Data");
            } finally {
                Periphery.gpio_close(handle);
            }
        } else {
            log.error(String.format("Device %d line %d failed to open for Data detection", device, line));
        }
    }

    /**
     * Use GPIO device to detect pull changes.
     *
     * @param arena Arena for native allocations.
     * @param mmioHandle MMIO handle.
     */
    public void detectPull(final Arena arena, final MemorySegment mmioHandle) {
        var dev = String.format("/dev/gpiochip%d", device);
        var handle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(dev);
        // Set pin for input and look for delta
        if (Periphery.gpio_open(handle, cDev, line, 0) >= 0) {
            try {
                var list1 = getRegValues(arena, mmioHandle);
                Periphery.gpio_set_bias(handle, 2); // 2 = GPIO_BIAS_PULL_UP
                var list2 = getRegValues(arena, mmioHandle);
                // Show the register delta
                listDiff(list1, list2, "Pull up");

                list1 = getRegValues(arena, mmioHandle);
                Periphery.gpio_set_bias(handle, 3); // 3 = GPIO_BIAS_PULL_DOWN
                list2 = getRegValues(arena, mmioHandle);
                // Show the register delta
                listDiff(list1, list2, "Pull down");
            } finally {
                Periphery.gpio_close(handle);
            }
        } else {
            log.error(String.format("Device %d line %d failed to open for Pull detection", device, line));
        }
    }

    /**
     * Detect changes made by GPIO at register level.
     *
     * @return Exit code.
     * @throws InterruptedException Possible exception.
     */
    @Override
    public Integer call() throws InterruptedException {
        var exitCode = 0;
        log.atDebug().log(String.format("Memory address 0x%08x words 0x%08x", address, words));
        try (var arena = Arena.ofConfined()) {
            var handle = arena.allocate(mmio_handle.layout());
            if (Periphery.mmio_open(handle, address, words * 4) >= 0) {
                try {
                    detectMode(arena, handle);
                    detectData(arena, handle);
                    detectPull(arena, handle);
                } finally {
                    Periphery.mmio_close(handle);
                }
            } else {
                log.error("Failed to open MMIO at 0x{}", Long.toHexString(address));
                exitCode = 1;
            }
        } catch (Exception e) {
            log.error("Error during memory scanning: {}", e.getMessage());
            exitCode = 1;
        }
        return exitCode;
    }

    /**
     * Main parsing, error handling and handling user requests for usage help or version help are done with one line of code.
     *
     * @param args Argument list.
     */
    public static void main(String... args) {
        System.exit(new CommandLine(new MemScan()).registerConverter(Byte.class, Byte::decode).registerConverter(Byte.TYPE,
                Byte::decode).registerConverter(Short.class, Short::decode).registerConverter(Short.TYPE, Short::decode).
                registerConverter(Integer.class, Integer::decode).registerConverter(Integer.TYPE, Integer::decode).
                registerConverter(Long.class, Long::decode).registerConverter(Long.TYPE, Long::decode).execute(args));
    }
}
