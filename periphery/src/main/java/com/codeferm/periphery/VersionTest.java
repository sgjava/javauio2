package com.codeferm.periphery;

import lombok.extern.slf4j.Slf4j;
import org.periphery.NativeLoader;
import org.periphery.Periphery;

@Slf4j
public class VersionTest {

    static {
        // This MUST run first to populate the SymbolLookup
        NativeLoader.load();
    }

    public static void main(String[] args) {
        log.info("========================================");
        log.info("   C-PERIPHERY FFM BINDING TEST");
        log.info("========================================");

        try {
            // Calling the generated jextract methods
            int major = Periphery.PERIPHERY_VERSION_MAJOR();
            int minor = Periphery.PERIPHERY_VERSION_MINOR();
            int patch = Periphery.PERIPHERY_VERSION_PATCH();

            log.info("Architecture: {}", System.getProperty("os.arch"));
            log.info("FFM Binding Version: {}.{}.{}", major, minor, patch);

            // Prove the Linker is working by calling a C function
            var info = Periphery.periphery_version_info();
            log.info("C-Periphery Build Info: {}", info.getString(0));

            log.info("Status: SUCCESS");

        } catch (Throwable t) {
            log.error("Status: FAILURE - Native link failed", t);
        }
        log.info("========================================");
    }
}
