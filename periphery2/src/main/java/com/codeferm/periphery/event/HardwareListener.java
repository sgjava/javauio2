/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.event;

/**
 * Listener interface for intercepting low-level system lifecycle events.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
public interface HardwareListener {
    
    /**
     * Invoked when the system receives a termination signal (e.g., SIGINT / Ctrl+C).
     */
    void onSystemShutdown();
}