/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;

import static org.u8g2.U8g2.C_POINTER;

/**
 * Universal U8g2 Engine Factory for FFM.
 *
 * @author Steven P. Goldsmith
 * @version 1.2.6
 * @since 25
 */
@Slf4j
public final class U8g2Factory {

    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * Use loaderLookup to find symbols in the library loaded by NativeLoader. This avoids the "Cannot open library: libu8g2.so"
     * error by looking in libraries already loaded into the JVM.
     */
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    public enum Transport {
        I2CHW, I2CSW, SPIHW, SPISW, SDL
    }

    private U8g2Factory() {
    }

    /**
     * Initialize I2C hardware driven display.
     *
     * @param u8g2 u8g2_t structure.
     * @param setup Setup type.
     * @param bus I2C bus number.
     * @param address I2C address.
     */
    public static void initHwI2c(final MemorySegment u8g2, final String setup, final int bus, final int address) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_hw_i2c");
        U8g2.init_i2c_hw(u8g2, (byte) bus);
        U8g2.u8g2_SetI2CAddress_Java(u8g2, (byte) (address << 1));
        log.atDebug().log(String.format("Size %d x %d, draw color %d", U8g2.u8g2_GetDisplayWidth_Java(u8g2), U8g2.
                u8g2_GetDisplayHeight_Java(u8g2), U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("Bus 0x%02x, Address %02x", bus, address));
    }

    /**
     * Initialize I2C software driven display.
     *
     * @param u8g2 u8g2_t structure.
     * @param setup Setup type.
     * @param gpio GPIO chip number.
     * @param scl SCL.
     * @param sda SDA.
     * @param res RESET pin.
     * @param delay Nanosecond delay or 0 for none.
     */
    public static void initSwI2c(final MemorySegment u8g2, final String setup, final int gpio, final int scl, final int sda,
            final int res, final long delay) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_sw_i2c");
        U8g2.init_i2c_sw(u8g2, (byte) gpio, (byte) scl, (byte) sda, (byte) res, delay);
        log.atDebug().log(String.format("Size %d x %d, draw color %d", U8g2.u8g2_GetDisplayWidth_Java(u8g2), U8g2.
                u8g2_GetDisplayHeight_Java(u8g2), U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("GPIO chip %d, SCL %d, SDA %d, RES %d, Delay %d", gpio, scl, sda, res, delay));

    }

    /**
     * Initialize SPI hardware driven display.
     *
     * @param u8g2 u8g2_t structure.
     * @param setup Setup type.
     * @param gpio GPIO chip number.
     * @param bus SPI bus number.
     * @param dc DC pin.
     * @param res RESET pin.
     * @param cs CS pin.
     * @param spiMode SPI mode.
     * @param maxSpeed Maximum speed.
     */
    public static void initHwSpi(final MemorySegment u8g2, final String setup, final int gpio, final int bus, final int dc,
            final int res, final int cs, final short spiMode, final int maxSpeed) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_hw_spi");
        U8g2.init_spi_hw_advanced(u8g2, (byte) gpio, (byte) bus, (byte) dc, (byte) res, (byte) cs, spiMode, maxSpeed);
        log.atDebug().log(String.format("Size %d x %d, draw color %d", U8g2.u8g2_GetDisplayWidth_Java(u8g2), U8g2.
                u8g2_GetDisplayHeight_Java(u8g2), U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("GPIO chip %d, bus 0x%02x, DC %d, RES %d, CS %d", gpio, bus, dc, res, cs));
    }

    /**
     * Initialize SPI software driven display.
     *
     * @param u8g2 u8g2_t structure.
     * @param setup Setup type.
     * @param dc DC pin.
     * @param res RESET pin.
     * @param mosi MOSI pin.
     * @param sck SCK pin.
     * @param cs CS pin.
     * @param delay Nanosecond delay or 0 for none.
     */
    public static void initSwSpi(final MemorySegment u8g2, final String setup, final int gpio, final int dc, final int res,
            final int mosi, final int sck, final int cs, final long delay) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_hw_spi");
        U8g2.init_spi_sw(u8g2, (byte) gpio, (byte) dc, (byte) res, (byte) mosi, (byte) sck, (byte) cs, delay);
        log.atDebug().log(String.format("Size %d x %d, draw color %d", U8g2.u8g2_GetDisplayWidth_Java(u8g2), U8g2.
                u8g2_GetDisplayHeight_Java(u8g2), U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("GPIO chip %d, DC %d, RES %d, MOSI %d, SCK %d, CS %d, Delay %d", gpio, dc, res, mosi, sck,
                cs, delay));
    }

    /**
     * Initialize SDL driven display.
     *
     * @param u8g2 u8g2_t structure.
     * @param setup Setup type.
     */
    public static void initSdl(final MemorySegment u8g2, final String setup) {
        // 1. Choose the setup buffer based on the string
        // We call the jextract methods directly. They only need (u8g2, rotation)
        switch (setup) {
            case "sdl_128x64_1" ->
                U8g2.u8g2_SetupBuffer_SDL_128x64(u8g2, U8g2.u8g2_cb_r0());
            case "sdl_256x128_1" ->
                U8g2.u8g2_SetupBuffer_SDL_256x128(u8g2, U8g2.u8g2_cb_r0());
            default ->
                throw new IllegalArgumentException("Unsupported SDL setup: " + setup);
        }
        // 2. Call the C helper to initialize the display window and state
        U8g2.init_sdl(u8g2);
        log.atDebug().log(String.format("Size %d x %d, draw color %d", U8g2.u8g2_GetDisplayWidth_Java(u8g2), U8g2.
                u8g2_GetDisplayHeight_Java(u8g2), U8g2.u8g2_GetDrawColor_Java(u8g2)));
    }

    public static MemorySegment getFont(final String fontName) {
        final var name = fontName.startsWith("u8g2_font_") ? fontName : "u8g2_font_" + fontName;
        return LOOKUP.find(name).orElseThrow(() -> new RuntimeException("Font not found: " + name));
    }

    /**
     * Draws text with word-wrapping.
     */
    public static void drawWrappedText(final MemorySegment u8g2, final Arena arena,
            final int x, final int y, final int lineH,
            final String text, final int width) {
        final var words = text.split(" ");
        var currentLine = new StringBuilder();
        var currentY = y;

        for (final var word : words) {
            final var testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (U8g2.u8g2_GetStrWidth(u8g2, arena.allocateFrom(testLine)) <= width) {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            } else {
                U8g2.u8g2_DrawStr(u8g2, (short) x, (short) currentY, arena.allocateFrom(currentLine.toString()));
                currentLine = new StringBuilder(word);
                currentY += lineH;
            }
        }
        if (currentLine.length() > 0) {
            U8g2.u8g2_DrawStr(u8g2, (short) x, (short) currentY, arena.allocateFrom(currentLine.toString()));
        }
    }

    private static void invokeSetup(final MemorySegment u8g2, final String setup, final String callback) {
        final var setupName = setup.startsWith("u8g2_Setup") ? setup : "u8g2_Setup_" + setup;

        final var setupAddr = LOOKUP.find(setupName).orElseThrow(() -> new RuntimeException("Setup not found: " + setupName));
        final var cbAddr = LOOKUP.find(callback).orElseThrow(() -> new RuntimeException("Callback not found: " + callback));

        try {
            LINKER.downcallHandle(setupAddr, FunctionDescriptor.ofVoid(
                    C_POINTER, C_POINTER, C_POINTER, C_POINTER
            )).invokeExact(u8g2, U8g2.u8g2_cb_r0(), cbAddr, U8g2.u8x8_arm_linux_gpio_and_delay$address());
        } catch (Throwable t) {
            throw new RuntimeException("Native mapping failure for " + setupName, t);
        }
    }
}
