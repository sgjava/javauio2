/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import com.codeferm.u8g2.NativeLoader;
import com.codeferm.u8g2.U8g2Factory;
import static com.codeferm.u8g2.U8g2Factory.Transport.I2CHW;
import static com.codeferm.u8g2.U8g2Factory.Transport.I2CSW;
import static com.codeferm.u8g2.U8g2Factory.Transport.SDL;
import static com.codeferm.u8g2.U8g2Factory.Transport.SPIHW;
import static com.codeferm.u8g2.U8g2Factory.Transport.SPISW;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;

/**
 * Base CLI gives you a fully configured display with default font.
 * <p>
 * Orchestrates the Project Panama FFM hardware lifecycle using shared arenas to allow thread-safe hardware teardown routines during
 * unexpected shutdowns.
 * </p>
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since 1.0.0
 */
@Data
@Slf4j
public abstract class Base implements Callable<Integer> {

    /**
     * Type allows hardware and software I2C and SPI.
     */
    @CommandLine.Option(names = {"--setup"}, description
            = "Setup function to call, ${DEFAULT-VALUE} by default.")
    private String setup = "sdl_128x64_1";

    /**
     * Rotation of the display.
     */
    @CommandLine.Option(names = {"--rotation"}, description
            = "Rotation 0, 90, 180, 270 degrees, ${DEFAULT-VALUE} by default.")
    private int rotation = 0;

    /**
     * Font to use.
     */
    @CommandLine.Option(names = {"--font"}, description
            = "Font, ${DEFAULT-VALUE} by default.")
    private String font = "courB10_tf";

    /**
     * Type allows hardware and software I2C and SPI plus SDL.
     */
    @CommandLine.Option(names = {"--type"}, description = "Type of display, ${DEFAULT-VALUE} by default.")
    private U8g2Factory.Transport type = SDL;

    /**
     * GPIO chip number.
     */
    @CommandLine.Option(names = {"--gpio"}, description = "GPIO chip number, ${DEFAULT-VALUE} by default.")
    private int gpio = 0x0;

    /**
     * I2C or SPI bus number.
     */
    @CommandLine.Option(names = {"--bus"}, description = "I2C or SPI bus number, ${DEFAULT-VALUE} by default.")
    private int bus = 0x0;

    /**
     * I2C address.
     */
    @CommandLine.Option(names = {"--address"}, description = "I2C address, ${DEFAULT-VALUE} by default.")
    private int address = 0x3c;

    /**
     * I2C SCL.
     */
    @CommandLine.Option(names = {"--scl"}, description = "I2C software SCL pin, ${DEFAULT-VALUE} by default.")
    private int scl = 11;

    /**
     * I2C SDA.
     */
    @CommandLine.Option(names = {"--sda"}, description = "I2C software SDA pin, ${DEFAULT-VALUE} by default.")
    private int sda = 12;

    /**
     * DC pin for SPI.
     */
    @CommandLine.Option(names = {"--dc"}, description = "SPI DC pin, ${DEFAULT-VALUE} by default.")
    private int dc = 199;

    /**
     * RESET pin for SPI.
     */
    @CommandLine.Option(names = {"--reset"}, description = "I2C/SPI RESET pin, ${DEFAULT-VALUE} by default.")
    private int reset = 198;

    /**
     * MOSI pin for SPI.
     */
    @CommandLine.Option(names = {"--mosi"}, description = "SPI MOSI pin, ${DEFAULT-VALUE} by default.")
    private int mosi = 15;

    /**
     * SCK pin for SPI.
     */
    @CommandLine.Option(names = {"--sck"}, description = "SPI SCK pin, ${DEFAULT-VALUE} by default.")
    private int sck = 14;

    /**
     * CS pin for SPI.
     */
    @CommandLine.Option(names = {"--cs"}, description = "SPI CS pin, ${DEFAULT-VALUE} by default.")
    private int cs = 13;

    /**
     * Mode for SPI.
     */
    @CommandLine.Option(names = {"--mode"}, description = "SPI mode, ${DEFAULT-VALUE} by default.")
    private short mode = 0;

    /**
     * SPI maximum speed.
     */
    @CommandLine.Option(names = {"--speed"}, description = "SPI maximum speed, ${DEFAULT-VALUE} by default.")
    private int speed = 500000;

    /**
     * Nanosecond delay or 0 for none for software I2C and SPI.
     */
    @CommandLine.Option(names = {"--delay"}, description = "Nanosecond delay for software I2C and SPI, ${DEFAULT-VALUE} by default.")
    private long delay = 0;

    /**
     * Milliseconds to sleep for text and graphics.
     */
    @CommandLine.Option(names = {"--sleep"}, description
            = " Milliseconds to sleep for text and graphics, ${DEFAULT-VALUE} by default.")
    private long sleep = 5000;

    /**
     * Guard to prevent concurrent/duplicate execution between shutdown hooks and main loops.
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Display width.
     */
    private int width;

    /**
     * Display height.
     */
    private int height;

    /**
     * Subclasses implement this to perform the actual demo drawing.
     *
     * @param u8g2 Handle to the u8g2 structure.
     */
    protected abstract void run(final MemorySegment u8g2);

    /**
     * Show text with delay. Everything is calculated each time as font can differ between calls. String is wrapped if too long for
     * one line.
     *
     * @param u8g2 MemorySegment handle to the u8g2 struct.
     * @param text Text to show.
     */
    public void showText(final MemorySegment u8g2, final String text) {
        log.atDebug().log(text);
        try (final var localArena = Arena.ofConfined()) {
            final var maxHeight = U8g2.u8g2_GetMaxCharHeight_Java(u8g2);
            U8g2.u8g2_ClearBuffer(u8g2);
            final String[] words = text.split(" ");
            var currentLine = new StringBuilder();
            var y = maxHeight;
            for (final String word : words) {
                final String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
                final MemorySegment testSegment = localArena.allocateFrom(testLine);
                if (U8g2.u8g2_GetStrWidth(u8g2, testSegment) < width) {
                    currentLine = new StringBuilder(testLine);
                } else {
                    if (currentLine.length() > 0) {
                        U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) y, localArena.allocateFrom(currentLine.toString()));
                        y += maxHeight;
                    }
                    if (y > height) {
                        break;
                    }
                    currentLine = new StringBuilder(word);
                }
            }
            if (currentLine.length() > 0 && y <= height) {
                U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) y, localArena.allocateFrom(currentLine.toString()));
            }
            U8g2.u8g2_SendBuffer(u8g2);

            try {
                TimeUnit.MILLISECONDS.sleep(sleep);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sub class should call this to setup display.
     * <p>
     * Leverages a shared memory arena instead of a confined context to guarantee cross-thread permission accessibility for
     * unmanaged platform resources on application termination events.
     * </p>
     *
     * @return Exit code.
     * @throws InterruptedException Possible exception.
     */
    @Override
    public Integer call() throws InterruptedException {
        NativeLoader.load();
        final var exitCode = 0;
        log.atDebug().log("Setup {}", setup);
        log.atDebug().log("Rotation {}", rotation);
        log.atDebug().log("Type {}", type);
        log.atDebug().log("Font {}", font);

        try (final var arena = Arena.ofShared()) {
            final var u8g2 = arena.allocate(org.u8g2.u8g2_struct.layout());

            try {
                switch (type) {
                    case I2CHW ->
                        U8g2Factory.initHwI2c(u8g2, setup, rotation, bus, address);
                    case I2CSW ->
                        U8g2Factory.initSwI2c(u8g2, setup, rotation, gpio, scl, sda, reset, delay);
                    case SPIHW ->
                        U8g2Factory.initHwSpi(u8g2, setup, rotation, gpio, bus, dc, reset, cs, mode, speed);
                    case SPISW ->
                        U8g2Factory.initSwSpi(u8g2, setup, rotation, gpio, dc, reset, mosi, sck, cs, delay);
                    case SDL ->
                        U8g2Factory.initSdl(u8g2, setup, rotation);
                    default ->
                        throw new RuntimeException("%s is not a valid type".formatted(type));
                }
                width = U8g2.u8g2_GetDisplayWidth_Java(u8g2);
                height = U8g2.u8g2_GetDisplayHeight_Java(u8g2);
                final var f = U8g2Factory.getFont(font);
                U8g2.u8g2_SetFont(u8g2, f);
                U8g2.u8g2_InitDisplay_Java(u8g2);
                U8g2.u8g2_SetPowerSave_Java(u8g2, (byte) 0);
                U8g2.u8g2_ClearBuffer(u8g2);

                run(u8g2);

            } finally {
                executeTeardown(u8g2, arena);
            }
        }
        return exitCode;
    }

    /**
     * Isolated structural teardown block executed sequentially by the main thread.
     *
     * @param u8g2 The display memory context reference.
     * @param arena The parent unmanaged memory allocator context.
     */
    private void executeTeardown(final MemorySegment u8g2, final Arena arena) {
        if (closed.compareAndSet(false, true)) {
            if (arena.scope().isAlive() && u8g2 != null && u8g2.address() != 0) {
                log.atDebug().log("Executing hardware clean up and display power sequence...");
                try {
                    U8g2.u8g2_ClearBuffer(u8g2);
                    U8g2.u8g2_SendBuffer(u8g2);
                    U8g2.u8g2_SetPowerSave_Java(u8g2, (byte) 1);

                    switch (type) {
                        case I2CHW, I2CSW -> {
                            U8g2.done_i2c.makeInvoker().handle().invokeExact();
                        }
                        case SPIHW, SPISW -> {
                            U8g2.done_spi.makeInvoker().handle().invokeExact();
                        }
                        default -> {
                            // SDL simulator requires no additional low-level kernel close mapping out calls
                        }
                    }
                } catch (final Throwable t) {
                    System.err.printf("Failed to safely shut down hardware bus infrastructure: %s%n", t.getMessage());
                }
                U8g2.done_user_data(u8g2);
            }
        }
    }
}
