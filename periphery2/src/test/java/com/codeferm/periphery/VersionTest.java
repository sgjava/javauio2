/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.periphery.Periphery;

/**
 * Version test.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@EnabledOnOs(value = OS.LINUX, architectures = "x86_64", 
             disabledReason = "Native library in this context is only testable on x86_64 host")
class VersionTest {

    @BeforeAll
    static void setup() {
        // This extracts the .so from target/classes/native/ to a temp dir
        NativeLoader.load();
    }

    @Test
    void testNativeVersion() {
        // Validate version components are non-negative (basic link test)
        final var major = Periphery.PERIPHERY_VERSION_MAJOR();
        final var minor = Periphery.PERIPHERY_VERSION_MINOR();
        final var patch = Periphery.PERIPHERY_VERSION_PATCH();
        assertTrue(major == 2, "Major version should be 2");
        assertTrue(minor == 5, "Minor version should be 5");
        assertTrue(patch == 0, "Patch version should be 0");
        // Test string return from FFM
        final var info = Periphery.periphery_version_info();
        assertNotNull(info);
        assertFalse(info.getString(0).isEmpty(), "Version info string should not be empty");
    }
}
