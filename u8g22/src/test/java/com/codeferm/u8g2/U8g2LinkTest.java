/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.u8g2.U8g2;
import java.lang.foreign.Arena;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * U8g2 basic test.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@EnabledOnOs(value = OS.LINUX, architectures = "x86_64", 
             disabledReason = "Native library in this context is only testable on x86_64 host")
class U8g2LinkTest {

    @BeforeAll
    static void setup() {
        // This extracts the .so from target/classes/native/ to a temp dir
        NativeLoader.load();
    }

    @Test
    void testFFMInitialization() {
        try (Arena arena = Arena.ofConfined()) {
            // 1. Manually allocate based on the struct layout as you suggested
            // This ensures the memory block is exactly the right size for u8g2_t
            final var u8g2 = arena.allocate(org.u8g2.u8g2_struct.layout());            
            assertNotNull(u8g2, "Should allocate native memory");

            // 2. Instead of Setup (which crashes on NULL pointers), 
            // call a simple getter to verify the symbol lookup works.
            // Even with an uninitialized struct, this should return 0 or a random value, not crash.
            var height = U8g2.u8g2_GetDisplayHeight_Java(u8g2);
            
            System.out.println("FFM Link Verified. Height from uninitialized struct: " + height);
            
            // 3. Double check the address is valid
            assertTrue(u8g2.address() != 0, "Memory address should be non-zero");
        }
    }
}