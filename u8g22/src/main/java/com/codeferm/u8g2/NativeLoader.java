/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import lombok.extern.slf4j.Slf4j;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Load native library.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class NativeLoader {

    // This provides the symbols to FFM
    public static final SymbolLookup U8G2_LOOKUP = SymbolLookup.loaderLookup();

    public static void load() {
        String resourcePath = "/native/libu8g2.so";
        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new FileNotFoundException("Missing: " + resourcePath);
            }
            Path tempDir = Files.createTempDirectory("u8g2-native-");
            Path tempFile = tempDir.resolve("libu8g2.so");
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            tempFile.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            // Register the .so with the JVM's ClassLoader
            System.load(tempFile.toAbsolutePath().toString());
            log.info("Native library loaded and registered with ClassLoader.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
