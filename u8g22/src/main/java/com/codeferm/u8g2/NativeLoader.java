/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility for loading and registering the native U8g2 library.
 * <p>
 * This class extracts the native shared object from the classpath resources,
 * saves it to a temporary directory, and loads it into the JVM to make
 * symbols available for the Foreign Function & Memory (FFM) API.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class NativeLoader {

    /**
     * Symbol lookup instance providing access to symbols in the loaded library.
     */
    public static final SymbolLookup U8G2_LOOKUP = SymbolLookup.loaderLookup();

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private NativeLoader() {
    }

    /**
     * Loads the native library from resources.
     * <p>
     * The library is extracted from {@code /native/libu8g2.so} to a temporary 
     * location and loaded via {@link System#load(String)}. Temporary files 
     * are marked for deletion on JVM exit.
     *
     * @throws UncheckedIOException if an I/O error occurs during extraction or loading.
     * @throws RuntimeException if the resource is missing.
     */
    public static void load() {
        final var resourcePath = "/native/libu8g2.so";
        try (final InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new FileNotFoundException("Missing: " + resourcePath);
            }
            final var tempDir = Files.createTempDirectory("u8g2-native-");
            final var tempFile = tempDir.resolve("libu8g2.so");         
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);            
            final var file = tempFile.toFile();
            file.deleteOnExit();
            tempDir.toFile().deleteOnExit();          
            // Register the .so with the JVM's ClassLoader
            System.load(tempFile.toAbsolutePath().toString());
            log.info("Native library loaded and registered with ClassLoader.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
