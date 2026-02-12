/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import com.codeferm.periphery.NativeLoader;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Version test using FFM (Foreign Function & Memory API).
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "version-test", 
         mixinStandardHelpOptions = true, 
         version = "Java UIO 2 - 1.0.0",
         description = "Tests FFM bindings for c-periphery and hardware permissions.")
public class Version implements Callable<Integer> {

    static {
        // This MUST run first to populate the SymbolLookup for FFM
        NativeLoader.load();
    }

    @Option(names = {"-v", "--verbose"}, description = "Display detailed environment info.")
    private boolean verbose = false;

    @Override
    public Integer call() {
        log.info("========================================");
        log.info("    C-PERIPHERY FFM BINDING TEST");
        log.info("========================================");
        try {
            // Native C calls via jextract bindings
            int major = Periphery.PERIPHERY_VERSION_MAJOR();
            int minor = Periphery.PERIPHERY_VERSION_MINOR();
            int patch = Periphery.PERIPHERY_VERSION_PATCH();
            var info = Periphery.periphery_version_info();
            log.info("Architecture:   {}", System.getProperty("os.arch"));
            log.info("Java Version:   {}", System.getProperty("java.version"));
            log.info("FFM Binding:    {}.{}.{}", major, minor, patch);
            log.info("C-Build Info:   {}", info.getString(0));
            if (verbose) {
                checkPermissions();
            }
            log.info("Status:         SUCCESS");
            log.info("========================================");
            return 0;
        } catch (Throwable t) {
            log.error("Status:         FAILURE - Native link failed", t);
            log.info("========================================");
            return 1;
        }
    }

    private void checkPermissions() {
        log.info("--- Hardware Access Check ---");
        // Checking /dev/mem for MMIO (Relevant for your gpio-data-command 199 logic)
        boolean devMem = Files.isWritable(Paths.get("/dev/mem"));
        log.info("/dev/mem (MMIO):  {}", devMem ? "WRITABLE" : "ACCESS DENIED");        
        // Check for any gpiochips
        try (var chips = Files.list(Paths.get("/dev/")).filter(p -> p.getFileName().toString().startsWith("gpiochip"))) {
            chips.forEach(p -> log.info("Found Chip:      {} (Writable: {})", p.getFileName(), Files.isWritable(p)));
        } catch (Exception e) {
            log.warn("Could not list /dev/ nodes");
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Version()).execute(args);
        System.exit(exitCode);
    }
}
