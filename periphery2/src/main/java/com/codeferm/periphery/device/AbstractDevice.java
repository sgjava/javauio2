/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.device;

import com.codeferm.periphery.NativeLoader;
import com.codeferm.periphery.event.HardwareListener;
import com.codeferm.periphery.event.HardwareRegistry;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;

/**
 * Base class for all FFM-backed native hardware devices with automated shutdown tracking.
 * <p>
 * This class centralizes the boilerplate for native memory management, handle allocation, lifecycle control, and automated
 * safe-teardown on system interrupts (SIGINT).
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractDevice implements AutoCloseable, HardwareListener {

    static {
        NativeLoader.load();
    }

    @Getter(AccessLevel.PROTECTED)
    private final Arena arena;

    @Getter(AccessLevel.PROTECTED)
    private final MemorySegment handle;

    /**
     * Initializes a shared arena, allocates the native handle segment, and registers with the hardware observer.
     *
     * @param layout The MemoryLayout of the specific C struct (e.g., gpio_handle.layout()).
     */
    protected AbstractDevice(final MemoryLayout layout) {
        this.arena = Arena.ofShared();
        this.handle = this.arena.allocate(layout);

        // Self-register with the global singleton listener upon birth
        HardwareRegistry.getInstance().register(this);
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
            final var errorMsg = Periphery.gpio_errmsg(handle).getString(0);
            throw new RuntimeException(String.format("%s: %s", message, errorMsg));
        }
    }

    /**
     * Listener callback invoked when the terminal traps a SIGINT (Ctrl+C). Delegates immediately to the standard close routing.
     */
    @Override
    public final void onSystemShutdown() {
        log.warn("Emergency interrupt caught for {}; forcing resource teardown.", this.getClass().getSimpleName());
        close();
    }

    /**
     * Closes the native handle, unregisters from system events, and invalidates the arena.
     */
    @Override
    public final void close() {
        try {
            // Unregister first so we don't clear it twice if closed normally via try-with-resources
            HardwareRegistry.getInstance().unregister(this);
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
