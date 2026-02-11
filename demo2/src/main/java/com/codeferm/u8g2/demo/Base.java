/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2.demo;

import com.codeferm.u8g2.NativeLoader;
import com.codeferm.u8g2.U8g2Factory;
import static com.codeferm.u8g2.U8g2Factory.Transport.SDL;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.Callable;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;
import picocli.CommandLine;

/**
 * Base CLI gives you a fully configured display with default font.
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
    private int dc = 198;
    /**
     * RESET pin for SPI.
     */
    @CommandLine.Option(names = {"--reset"}, description = "I2C/SPI RESET pin, ${DEFAULT-VALUE} by default.")
    private int reset = 199;
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
     * CS pin for SPI.
     */
    @CommandLine.Option(names = {"--speed"}, description = "SPI maximum speed, ${DEFAULT-VALUE} by default.")
    private long speed = 500000;
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
     * one line. This method uses a local confined arena to ensure all temporary C-strings used for wrapping and drawing are
     * deallocated immediately after the buffer is sent.
     *
     * @param u8g2 MemorySegment handle to the u8g2 struct (allocated in Base arena).
     * @param text Text to show.
     */
    public void showText(final MemorySegment u8g2, final String text) {
        log.atDebug().log(text);

        try (final var localArena = Arena.ofConfined()) {
            final int displayWidth = U8g2.u8g2_GetDisplayWidth_Java(u8g2);
            final int displayHeight = U8g2.u8g2_GetDisplayHeight_Java(u8g2);
            final int maxHeight = U8g2.u8g2_GetMaxCharHeight_Java(u8g2);

            U8g2.u8g2_ClearBuffer(u8g2);

            String[] words = text.split(" ");
            StringBuilder currentLine = new StringBuilder();
            int y = maxHeight;

            for (String word : words) {
                // Check if adding this word (plus a space) exceeds width
                String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;

                // Allocate temporary string for width measurement
                MemorySegment testSegment = localArena.allocateFrom(testLine);

                if (U8g2.u8g2_GetStrWidth(u8g2, testSegment) < displayWidth) {
                    // Word fits, update the current line
                    currentLine = new StringBuilder(testLine);
                } else {
                    // Word doesn't fit, draw the current line and start a new one
                    if (currentLine.length() > 0) {
                        U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) y, localArena.allocateFrom(currentLine.toString()));
                        y += maxHeight;
                    }

                    // If we've run out of vertical space, stop
                    if (y > displayHeight) {
                        break;
                    }

                    currentLine = new StringBuilder(word);
                }
            }

            // Draw the very last line if there's room
            if (currentLine.length() > 0 && y <= displayHeight) {
                U8g2.u8g2_DrawStr(u8g2, (short) 1, (short) y, localArena.allocateFrom(currentLine.toString()));
            }

            U8g2.u8g2_SendBuffer(u8g2);

            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sub class should call this to setup display.
     *
     * @return Exit code.
     * @throws InterruptedException Possible exception.
     */
    @Override
    public Integer call() throws InterruptedException {
        NativeLoader.load();
        var exitCode = 0;
        log.atDebug().log(String.format("Setup %s", setup));
        log.atDebug().log(String.format("Type %s", type));
        log.atDebug().log(String.format("Font %s", font));
        // Confined arena manages the lifecycle of the u8g2 struct and all drawing strings, etc.
        try (final var arena = Arena.ofConfined()) {
            // Allocate the u8g2 structure based on the jextract layout
            final var u8g2 = arena.allocate(org.u8g2.u8g2_struct.layout());
            switch (type) {
                case I2CHW ->
                    U8g2Factory.initHwI2c(u8g2, setup, bus, address);
                case I2CSW ->
                    U8g2Factory.initSwI2c(u8g2, setup, gpio, scl, sda, bus, delay);
                case SPIHW ->
                    U8g2Factory.initHwSpi(u8g2, setup, gpio, bus, dc, bus, cs, mode, address);
                case SPISW ->
                    U8g2Factory.initSwSpi(u8g2, setup, gpio, dc, bus, mosi, sck, cs, delay);
                case SDL ->
                    U8g2Factory.initSdl(u8g2, setup);
                default ->
                    throw new RuntimeException(String.format("%s is not a valid type", type));
            }
            width = U8g2.u8g2_GetDisplayWidth_Java(u8g2);
            height = U8g2.u8g2_GetDisplayHeight_Java(u8g2);
            final var f = U8g2Factory.getFont(font);
            U8g2.u8g2_SetFont(u8g2, f);
            U8g2.u8g2_InitDisplay_Java(u8g2);
            U8g2.u8g2_SetPowerSave_Java(u8g2, (byte) 0);
            U8g2.u8g2_ClearBuffer(u8g2);
            run(u8g2);
            U8g2.u8g2_SetPowerSave_Java(u8g2, (byte) 1);
        }
        return exitCode;
    }
}
