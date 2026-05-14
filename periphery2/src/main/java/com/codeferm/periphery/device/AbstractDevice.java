/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * Base class for all FFM-backed native hardware devices.
 * <p>
 * This class centralizes the boilerplate for native memory management, handle allocation, and lifecycle control (DRY).
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractDevice implements AutoCloseable {

    static {
        NativeLoader.load();
    }

    @Getter(AccessLevel.PROTECTED)
    private final Arena arena;

    @Getter(AccessLevel.PROTECTED)
    private final MemorySegment handle;

    /**
     * Initializes a shared arena and allocates the native handle segment.
     *
     * @param layout The MemoryLayout of the specific C struct (e.g., gpio_handle.layout()).
     */
    protected AbstractDevice(final MemoryLayout layout) {
        this.arena = Arena.ofShared();
        this.handle = this.arena.allocate(layout);
    }

    /**
     * Centralized error check for native calls.
     *
     * @param result The return code from the native C function.
     * @param message The context for the exception if the call failed.
     * @throws RuntimeException if the result is less than 0.
     */
    protected void checkError(final int result, final String message) {
        if (result < 0) {
            // Retrieve error message from the native handle
            final var errorMsg = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException(String.format("%s: %s", message, errorMsg));
        }
    }

    /**
     * Closes the native handle and invalidates the arena. Child classes must implement {@code closeNative()} to call their specific
     * C-periphery close function.
     */
    @Override
    public final void close() {
        try {
            closeNative();
        } finally {
            if (arena.scope().isAlive()) {
                arena.close();
            }
            log.debug("Native resources released for {}", this.getClass().getSimpleName());
        }
    }

    /**
     * Template method for calling the specific protocol's close function.
     */
    protected abstract void closeNative();
}
