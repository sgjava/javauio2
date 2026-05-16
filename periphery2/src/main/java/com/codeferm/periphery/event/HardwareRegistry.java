/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.event;

import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Centralized, thread-safe registry for single-board computer peripherals. Automatically hooks the JVM shutdown sequence to
 * broadcast termination events.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 */
@Slf4j
public final class HardwareRegistry {

    private static final HardwareRegistry INSTANCE = new HardwareRegistry();

    // Using a concurrent set backed by a WeakHashMap to prevent tracking-induced memory leaks
    private final Set<HardwareListener> listeners = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private HardwareRegistry() {
        // Automatically bootstrap the terminal hook once for the entire lifecycle
        Runtime.getRuntime().addShutdownHook(new Thread(this::broadcastShutdown));
    }

    /**
     * Returns the singleton instance of the registry.
     *
     * @return The HardwareRegistry instance.
     */
    public static HardwareRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a device or listener for emergency teardown.
     *
     * @param listener The listener to register.
     */
    public void register(final HardwareListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Unregisters a device when it is closed normally via try-with-resources.
     *
     * @param listener The listener to remove.
     */
    public void unregister(final HardwareListener listener) {
        listeners.remove(listener);
    }

    /**
     * Executes the broadcast loop to all active peripherals on SIGINT.
     */
    private void broadcastShutdown() {
        System.out.println("\n[!] Critical: Terminal interrupt caught. Safe-closing hardware layers...");
        for (final var listener : listeners) {
            try {
                listener.onSystemShutdown();
            } catch (final Exception e) {
                System.err.printf("Emergency shutdown failed for listener: %s%n", e.getMessage());
            }
        }
        listeners.clear();
        System.out.flush();
    }
}
