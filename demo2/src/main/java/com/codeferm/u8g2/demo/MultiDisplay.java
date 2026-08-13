/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import com.codeferm.u8g2.NativeLoader;
import com.codeferm.u8g2.U8g2Factory;
import static com.codeferm.u8g2.U8g2Factory.Transport.SDL;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Multiple displays using threads (FFM API version).
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
@Command(name = "MultiDisplay", mixinStandardHelpOptions = true, version = "1.0.0-SNAPSHOT",
        description = "Multiple display demo")
public class MultiDisplay implements Callable<Integer> {

    /**
     * Integer regex.
     */
    private final Pattern pattern = Pattern.compile("-?\\d+(\\.\\d+)?");

    /**
     * Input file.
     */
    @CommandLine.Option(names = {"-f", "--file"}, description = "Input property file name, ${DEFAULT-VALUE} by default.")
    private String fileName = "sdl.properties";

    /**
     * Type allows hardware and software I2C and SPI plus SDL.
     */
    @Option(names = {"--type"}, description = "Type of display, ${DEFAULT-VALUE} by default.")
    private U8g2Factory.Transport type = SDL;

    /**
     * Map of display number and type.
     */
    private final HashMap<Integer, U8g2Factory.Transport> typeMap = new HashMap<>();

    /**
     * Load properties file from file path or fail back to class path.
     *
     * @param propertyFile Name of property file.
     * @return Properties.
     */
    public Properties loadProperties(final String propertyFile) {
        final var props = new Properties();
        try {
            props.load(new FileInputStream(propertyFile));
            log.atDebug().log("Properties loaded from file {}", propertyFile);
        } catch (final IOException e1) {
            log.warn("Properties file not found {}", propertyFile);
            try (final var stream = MultiDisplay.class.getClassLoader().getResourceAsStream(propertyFile)) {
                props.load(stream);
                log.atDebug().log("Properties loaded from class path {}", propertyFile);
            } catch (final IOException e2) {
                throw new RuntimeException("No properties found", e2);
            }
        }
        return props;
    }

    /**
     * Configure display based on number in property file.
     *
     * @param displayNum Display number (suffix in property file).
     * @param properties Properties.
     * @param arena Arena for memory allocation.
     * @return MemorySegment pointer to u8g2 struct.
     */
    public MemorySegment setup(final int displayNum, final Properties properties, final Arena arena) {
        log.atDebug().log("Display {}", displayNum);
        final var keys = properties.stringPropertyNames();
        final var intMap = new HashMap<String, Integer>();
        final var strMap = new HashMap<String, String>();

        keys.forEach(key -> {
            final var split = key.split("\\.");
            final var number = Integer.parseInt(split[split.length - 1]);
            if (number == displayNum) {
                if (pattern.matcher(properties.getProperty(key)).matches()) {
                    intMap.put(split[0], Integer.parseInt(properties.getProperty(key)));
                } else {
                    strMap.put(split[0], properties.getProperty(key));
                }
            }
        });

        final var displayType = U8g2Factory.Transport.valueOf(strMap.get("type"));
        typeMap.put(displayNum, displayType);
        log.atDebug().log("Setup {}", strMap.get("setup"));
        log.atDebug().log("Type {}", displayType);
        log.atDebug().log("Font {}", strMap.get("font"));

        final MemorySegment u8g2 = arena.allocate(org.u8g2.u8g2_struct.layout());
        final var rotation = intMap.getOrDefault("rotation", 0);

        switch (displayType) {
            case I2CHW ->
                U8g2Factory.initHwI2c(u8g2, strMap.get("setup"), rotation, intMap.get("bus"), intMap.get("address"));
            case I2CSW ->
                U8g2Factory.initSwI2c(u8g2, strMap.get("setup"), rotation, intMap.get("gpio"), intMap.get("scl"),
                        intMap.get("sda"), intMap.get("reset"), intMap.get("delay"));
            case SPIHW ->
                U8g2Factory.initHwSpi(u8g2, strMap.get("setup"), rotation, intMap.get("gpio"), intMap.get("bus"),
                        intMap.get("dc"), intMap.get("reset"), intMap.get("cs"), intMap.get("mode"), intMap.get("speed"));
            case SPISW ->
                U8g2Factory.initSwSpi(u8g2, strMap.get("setup"), rotation, intMap.get("gpio"), intMap.get("dc"),
                        intMap.get("reset"), intMap.get("mosi"), intMap.get("sck"), intMap.get("cs"), intMap.get("delay"));
            case SDL ->
                U8g2Factory.initSdl(u8g2, strMap.get("setup"), rotation);
            default ->
                throw new RuntimeException("%s is not a valid type".formatted(strMap.get("setup")));
        }

        U8g2.u8g2_SetFont(u8g2, U8g2Factory.getFont(strMap.get("font")));
        U8g2.u8g2_InitDisplay_Java(u8g2);
        U8g2.u8g2_SetPowerSave_Java(u8g2, (byte) 0);
        U8g2.u8g2_ClearBuffer(u8g2);
        U8g2.u8g2_SendBuffer(u8g2);

        return u8g2;
    }

    /**
     * Return a Set of display numbers based on property key suffix.
     *
     * @param properties Properties.
     * @return Set of display numbers.
     */
    public Set<Integer> getDisplays(final Properties properties) {
        final var set = new HashSet<Integer>();
        final var keys = properties.stringPropertyNames();
        keys.stream().map(key -> key.split("\\.")).forEachOrdered(split -> {
            set.add(Integer.valueOf(split[split.length - 1]));
        });
        return set;
    }

    /**
     * Update multiple displays using threads.
     *
     * @return Exit code.
     * @throws InterruptedException Possible exception.
     */
    @Override
    public Integer call() throws InterruptedException {
        NativeLoader.load();
        var exitCode = 0;
        final var properties = loadProperties(fileName);
        final var set = getDisplays(properties);

        try (final var arena = Arena.ofShared()) {
            final var map = new TreeMap<Integer, MemorySegment>();
            set.forEach(displayNum -> {
                map.put(displayNum, setup(displayNum, properties, arena));
                try {
                    TimeUnit.MILLISECONDS.sleep(2000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            final var executor = Executors.newFixedThreadPool(set.size());
            for (final Map.Entry<Integer, MemorySegment> entry : map.entrySet()) {
                executor.execute(() -> {
                    final var u8g2 = entry.getValue();
                    final var height = (int) U8g2.u8g2_GetDisplayHeight_Java(u8g2);
                    final var width = (int) U8g2.u8g2_GetDisplayWidth_Java(u8g2);

                    for (var i = 0; i < 10; i++) {
                        U8g2.u8g2_SetDrawColor(u8g2, (byte) 1);
                        for (var r = 4; r < height; r += 4) {
                            U8g2.u8g2_DrawDisc(u8g2, (short) (width / 2), (short) (height / 2), (short) (r / 2),
                                    (byte) U8g2.U8G2_DRAW_ALL());
                            U8g2.u8g2_SendBuffer(u8g2);
                            if (typeMap.get(entry.getKey()) == U8g2Factory.Transport.SDL) {
                                try {
                                    TimeUnit.MILLISECONDS.sleep(50);
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                        U8g2.u8g2_SetDrawColor(u8g2, (byte) 0);
                        for (var r = 4; r < height; r += 4) {
                            U8g2.u8g2_DrawDisc(u8g2, (short) (width / 2), (short) (height / 2), (short) (r / 2),
                                    (byte) U8g2.U8G2_DRAW_ALL());
                            U8g2.u8g2_SendBuffer(u8g2);
                            if (typeMap.get(entry.getKey()) == U8g2Factory.Transport.SDL) {
                                try {
                                    TimeUnit.MILLISECONDS.sleep(50);
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                    log.atDebug().log(Long.toString(u8g2.address()));
                });
            }

            try {
                executor.shutdown();
                if (!executor.isTerminated()) {
                    log.info("Waiting for threads to finish");
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                }
            } catch (final InterruptedException e) {
                log.error("Tasks interrupted");
                Thread.currentThread().interrupt();
            } finally {
                executor.shutdownNow();
            }

            map.entrySet().stream().map(entry -> {
                U8g2.u8g2_SetPowerSave_Java(entry.getValue(), (byte) 1);
                U8g2.u8g2_ClearBuffer(entry.getValue());
                U8g2.u8g2_SendBuffer(entry.getValue());
                return entry;
            }).forEachOrdered(entry -> {
                switch (typeMap.get(entry.getKey())) {
                    case I2CHW, I2CSW -> {
                        try {
                            U8g2.done_i2c.makeInvoker().handle().invokeExact();
                        } catch (final Throwable t) {
                            log.error("Failed to close I2C bus", t);
                        }
                    }
                    case SPIHW, SPISW -> {
                        try {
                            U8g2.done_spi.makeInvoker().handle().invokeExact();
                        } catch (final Throwable t) {
                            log.error("Failed to close SPI bus", t);
                        }
                    }
                    default -> {
                        // SDL simulator requires no additional kernel close calls
                    }
                }
                U8g2.done_user_data(entry.getValue());
            });
        }
        return exitCode;
    }

    /**
     * Main parsing, error handling and handling user requests for usage help or version help.
     *
     * @param args Argument list.
     */
    public static void main(final String... args) {
        System.exit(new CommandLine(new MultiDisplay())
                .registerConverter(Byte.class, Byte::decode)
                .registerConverter(Byte.TYPE, Byte::decode)
                .registerConverter(Short.class, Short::decode)
                .registerConverter(Short.TYPE, Short::decode)
                .registerConverter(Integer.class, Integer::decode)
                .registerConverter(Integer.TYPE, Integer::decode)
                .registerConverter(Long.class, Long::decode)
                .registerConverter(Long.TYPE, Long::decode)
                .execute(args));
    }
}
