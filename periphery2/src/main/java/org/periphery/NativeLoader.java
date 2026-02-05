package org.periphery;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.lang.foreign.SymbolLookup;

@Slf4j
public class NativeLoader {
    
    // This provides the symbols to FFM
    public static final SymbolLookup PERIPHERY_LOOKUP = SymbolLookup.loaderLookup();

    public static void load() {
        String resourcePath = "/native/libperiphery.so";
        try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new FileNotFoundException("Missing: " + resourcePath);

            Path tempDir = Files.createTempDirectory("periphery-native-");
            Path tempFile = tempDir.resolve("libperiphery.so");
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
