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
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import org.periphery.gpio_handle;
import org.periphery.mmio_handle;
import picocli.CommandLine;

/**
 * GPIO data register offset and mask generator using GPIO device and MMIO.
 *
 * Make sure you disable all hardware in armbian-config System, Hardware and remove console=serial from /boot/armbianEnv.txt. You
 * want multi-function pins to act as GPIO pins. The idea here is to generate data register offsets and masks, so you can build MMIO
 * based GPIO code without doing it manually from the datasheet. The only thing you need the datasheet for is building the property
 * file and validation.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@CommandLine.Command(name = "Gen", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "GPIO data register offset and mask generator")
public class Gen implements Callable<Integer> {

    static {
        // Load the native library before any FFM calls
        NativeLoader.load();
    }
    /**
     * Input property file.
     */
    @CommandLine.Option(names = {"-i", "--in"}, description = "Input property file name, ${DEFAULT-VALUE} by default.")
    private String inFileName = "duo.properties";
    /**
     * Output property file.
     */
    @CommandLine.Option(names = {"-o", "--out"}, description = "Output property file name, ${DEFAULT-VALUE} by default.")
    private String outFileName = "out.properties";

    /**
     * Return values from all registers.
     *
     * @param arena Arena for temporary allocations.
     * @param mmioHandle MMIO handles.
     * @param groupChip Chip group is on.
     * @param dataOffset Data register offsets in chip.
     * @return List of register values.
     */
    public List<Integer> getRegValues(final Arena arena, final List<MemorySegment> mmioHandle,
            final List<Integer> groupChip, final List<Integer> dataOffset) {
        var list = new ArrayList<Integer>();
        var valueBuffer = arena.allocate(ValueLayout.JAVA_INT);
        // Read all groups
        for (var i = 0; i < groupChip.size(); i++) {
            var chipIdx = groupChip.get(i);
            var offset = dataOffset.get(i).longValue();
            if (Periphery.mmio_read32(mmioHandle.get(chipIdx), (int) offset, valueBuffer) < 0) {
                log.error("MMIO read failed at offset {}", offset);
                list.add(0);
            } else {
                list.add(valueBuffer.get(ValueLayout.JAVA_INT, 0));
            }
        }
        return list;
    }

    /**
     * Compare list values and return index where difference is found. Filter is used to select on desired register name.
     *
     * @param list1 First list.
     * @param list2 Second list.
     * @return Index of difference.
     */
    public int listDiff(final List<Integer> list1, final List<Integer> list2) {
        var i = 0;
        // Look for difference based on filter and exit on first instance
        while (i < list1.size() && list1.get(i).equals(list2.get(i))) {
            i++;
        }
        // No difference
        if (i == list1.size()) {
            i = -1;
        }
        return i;
    }

    /**
     * Return positive difference between 2 values for bit mask.
     *
     * @param value1 First value.
     * @param value2 Second value.
     * @return Diff value.
     */
    public int valueDiff(final int value1, final int value2) {
        int diff;
        if (value1 > value2) {
            diff = value1 - value2;
        } else {
            diff = value2 - value1;
        }
        return diff;
    }

    /**
     * Set data register info in pin DTO.
     *
     * @param arena Arena for native allocations.
     * @param pin Pin DTO.
     * @param mmioHandle MMIO handles.
     * @param groupChip Chips ports are on.
     * @param groupName Pin group names.
     * @param dataInOnOffset Data register in on offsets.
     * @param dataInOffOffset Data register in 0ff offsets.
     * @param dataOutOnOffset Data register out on offsets.
     * @param dataOutOffOffset Data register out off offsets.
     * @param useInputDataReg Use input register instead of output register.
     */
    public void setDataReg(final Arena arena, final Pin pin, final List<MemorySegment> mmioHandle,
            final List<Integer> groupChip, final List<String> groupName,
            final List<Integer> dataInOnOffset, final List<Integer> dataInOffOffset, final List<Integer> dataOutOnOffset,
            final List<Integer> dataOutOffOffset, final boolean useInputDataReg) {
        final List<Integer> dataOffset;
        // Use input data register to check for changes
        if (useInputDataReg) {
            dataOffset = dataInOnOffset;
        } else {
            dataOffset = dataOutOnOffset;
        }
        var dev = String.format("/dev/gpiochip%d", pin.key().chip());
        var handle = arena.allocate(gpio_handle.layout());
        var cDev = arena.allocateFrom(dev);

        // Open GPIO for output: 1 = GPIO_DIR_OUT
        if (Periphery.gpio_open(handle, cDev, pin.key().pin(), 1) < 0) {
            log.error(String.format("Chip %d Pin %d failed to open", pin.key().chip(), pin.key().pin()));
            return;
        }

        try {
            Periphery.gpio_write(handle, false);
            var list1 = getRegValues(arena, mmioHandle, groupChip, dataOffset);
            Periphery.gpio_write(handle, true);
            var list2 = getRegValues(arena, mmioHandle, groupChip, dataOffset);
            // Find the register delta
            var reg = listDiff(list1, list2);
            // Make sure a delta is detected
            if (reg >= 0) {
                pin.groupName(groupName.get(reg)).dataInOn(new Register("IN_ON", dataInOnOffset.get(reg % dataInOnOffset.
                        size()), valueDiff(list1.get(reg), list2.get(reg)))).dataInOff(new Register("IN_OFF", dataInOffOffset.
                        get(reg % dataInOffOffset.size()), valueDiff(list1.get(reg), list2.get(reg)))).dataOutOn(new Register(
                        "OUT_ON", dataOutOnOffset.get(reg % dataOutOnOffset.size()), valueDiff(list1.get(reg), list2.get(reg)))).
                        dataOutOff(new Register("OUT_OFF", dataOutOffOffset.get(reg % dataOutOffOffset.size()), valueDiff(list1.
                                get(reg), list2.get(reg))));
                // If data out uses same register for on/off then generate AND mask for off.
                if (pin.dataOutOn().offset().equals(pin.dataOutOff().offset())) {
                    pin.dataOutOff().mask(pin.dataOutOff().mask() ^ 0xffffffff);
                }
            } else {
                log.warn(String.format("Chip %d Pin %d data register change not detected", pin.key().chip(),
                        pin.key().pin()));
            }
        } finally {
            Periphery.gpio_close(handle);
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
        var file = new File();
        final Map<PinKey, Pin> pinMap = file.parseInput(inFileName);
        // Make sure we have pins loaded
        if (!pinMap.isEmpty()) {
            try (var arena = Arena.ofConfined()) {
                var mmioHandle = new ArrayList<MemorySegment>();
                // Open MMIO for each chip
                for (var i = 0; i < file.chips().size(); i++) {
                    var handle = arena.allocate(mmio_handle.layout());
                    // Signature: mmio_open(MemorySegment, long base, long size)
                    if (Periphery.mmio_open(handle, file.chips().get(i).intValue(), file.mmioSize().get(i).intValue()) < 0) {
                        log.error("Failed to open MMIO for chip 0x{}", Long.toHexString(file.chips().get(i)));
                        return 1;
                    }
                    mmioHandle.add(handle);
                }
                // Set register offset and mask for each pin
                pinMap.entrySet().stream().map((entry) -> entry.getValue()).forEachOrdered((value) -> {
                    setDataReg(arena, value, mmioHandle, file.groupChip(), file.groupName(), file.dataInOnOffset(), file.
                            dataInOffOffset(), file.dataOutOnOffset(), file.dataOutOffOffset(), file.useInputDataReg());
                });
                // Generate properties file
                file.genProperties(pinMap, inFileName, outFileName);
                // Close MMIO for each handle
                mmioHandle.forEach(Periphery::mmio_close);
            } catch (Exception e) {
                log.error("Error during MMIO/GPIO processing", e);
                exitCode = 1;
            }
        } else {
            log.error("Pin map empty. Make sure you have a valid property file.");
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
        System.exit(new CommandLine(new Gen()).execute(args));
    }
}
