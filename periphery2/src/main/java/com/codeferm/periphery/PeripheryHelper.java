/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * Generic native helper for high-speed GPIO operations using c-periphery.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class PeripheryHelper {

    private PeripheryHelper() {
    }

    /**
     * Bit-bang an entire byte array through a c-periphery GPIO handle natively.
     *
     * @param gpioHandle MemorySegment pointer to the initialized gpio_handle struct.
     * @param data Java byte array containing pattern/data to bang.
     */
    public static void bitbangBuffer(final MemorySegment gpioHandle, final byte[] data) {
        try (final var arena = Arena.ofConfined()) {
            // Allocate off-heap memory segment and copy Java byte array contents into it
            final var nativeBuffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            // Invoke the compiled native C helper function via FFM downcall handle
            final var result = (int) Periphery.periphery_gpio_bitbang(gpioHandle, nativeBuffer, data.length
            );
            if (result < 0) {
                log.error("Native bit-bang operation failed with code: {}", result);
                throw new RuntimeException("Native bit-bang failed");
            }
        } catch (final Throwable t) {
            log.error("Failed to execute native bit-bang routine", t);
            throw new RuntimeException(t);
        }
    }
}
