/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.periphery.demo;

import java.nio.file.Files;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.periphery.Periphery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Version test using FFM (Foreign Function & Memory API).
 * <p>
 * This demo verifies that the {@code c-periphery} native library is correctly linked via Project Panama and checks for necessary
 * hardware permissions (sysfs/dev) required for low-level I/O.
 * </p>
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
public final class Version extends AbstractDemo {

    /**
     * Display detailed environment info.
     */
    @Option(names = {"-v", "--verbose"}, description = "Display detailed environment info.")
    private boolean verbose = false;

    /**
     * Executes the version and permission check.
     *
     * * @return Exit code (0 for success, 1 for failure).
     * @throws Exception On hardware or execution error.
     */
    @Override
    public Integer call() throws Exception {
        addTerminalHook();
        log.info("========================================");
        log.info("    C-PERIPHERY FFM BINDING TEST");
        log.info("========================================");

        try {
            // Native C calls via jextract/FFM bindings
            final var major = Periphery.PERIPHERY_VERSION_MAJOR();
            final var minor = Periphery.PERIPHERY_VERSION_MINOR();
            final var patch = Periphery.PERIPHERY_VERSION_PATCH();
            final var info = Periphery.periphery_version_info();

            log.info("Architecture:    {}", System.getProperty("os.arch"));
            log.info("Java Version:    {}", System.getProperty("java.version"));
            log.info("FFM Binding:     {}.{}.{}", major, minor, patch);
            log.info("C-Build Info:    {}", info.getString(0));

            if (verbose) {
                checkPermissions();
            }

            log.info("Status:          SUCCESS");
            log.info("========================================");
            return 0;
        } catch (final Throwable t) {
            log.error("Status:          FAILURE - Native link failed", t);
            log.info("========================================");
            return 1;
        }
    }

    /**
     * Probes the filesystem for hardware device node access.
     */
    private void checkPermissions() {
        log.info("--- Hardware Access Check ---");

        // Checking /dev/mem for MMIO (Crucial for FFM-based memory mapping)
        final var devMemPath = Paths.get("/dev/mem");
        final var devMem = Files.isWritable(devMemPath);
        log.info("/dev/mem (MMIO):  {}", devMem ? "WRITABLE" : "ACCESS DENIED");

        // Check for any gpiochips
        try (final var chips = Files.list(Paths.get("/dev/")).filter(p -> p.getFileName().toString().startsWith("gpiochip"))) {
            chips.forEach(p -> log.info("Found Chip:      {} (Writable: {})", p.getFileName(), Files.isWritable(p)));
        } catch (final Exception e) {
            log.warn("Could not list /dev/ nodes: {}", e.getMessage());
        }
    }

    /**
     * Main entry point for the Version check application.
     *
     * @param args Command line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new Version()).execute(args));
    }
}
