/*
 * Copyright (c) Steven P. Goldsmith. All rights reserved.
 */
package com.codeferm.u8g2;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import lombok.extern.slf4j.Slf4j;
import org.u8g2.U8g2;

import static org.u8g2.U8g2.C_POINTER;

/**
 * Universal U8g2 Engine Factory for Foreign Function & Memory (FFM) API.
 * <p>
 * This factory provides static methods to initialize various display transports including Hardware/Software I2C, SPI, and SDL.
 *
 * @author Steven P. Goldsmith
 * @version 1.0.0
 * @since  1.0.0
 */
@Slf4j
public final class U8g2Factory {

    /**
     * Native linker used to create downcall handles.
     */
    private static final Linker LINKER = Linker.nativeLinker();

    /**
     * Use loaderLookup to find symbols in the library loaded by NativeLoader. This avoids the "Cannot open library: libu8g2.so"
     * error by looking in libraries already loaded into the JVM.
     */
    private static final SymbolLookup LOOKUP = SymbolLookup.loaderLookup();

    /**
     * Supported transport protocols for the U8g2 engine.
     */
    public enum Transport {
        /**
         * Hardware I2C.
         */
        I2CHW,
        /**
         * Software I2C (Bit-banged).
         */
        I2CSW,
        /**
         * Hardware SPI.
         */
        SPIHW,
        /**
         * Software SPI (Bit-banged).
         */
        SPISW,
        /**
         * Simple DirectMedia Layer (Simulation).
         */
        SDL
    }

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private U8g2Factory() {
    }

    /**
     * Initialize I2C hardware driven display.
     *
     * @param u8g2 The {@link MemorySegment} representing the u8g2_t structure.
     * @param setup The setup type string (e.g., "ssd1306_i2c_128x64_noname_f").
     * @param bus The I2C bus number (e.g., 1 for /dev/i2c-1).
     * @param address The I2C address of the display.
     */
    public static void initHwI2c(final MemorySegment u8g2, final String setup, final int bus, final int address) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_hw_i2c");
        U8g2.init_i2c_hw(u8g2, (byte) bus);
        U8g2.u8g2_SetI2CAddress_Java(u8g2, (byte) (address << 1));
        log.atDebug().log(String.format("Size %d x %d, draw color %d",
                U8g2.u8g2_GetDisplayWidth_Java(u8g2),
                U8g2.u8g2_GetDisplayHeight_Java(u8g2),
                U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("Bus 0x%02x, Address %02x", bus, address));
    }

    /**
     * Initialize I2C software driven display.
     *
     * @param u8g2 The {@link MemorySegment} representing the u8g2_t structure.
     * @param setup The setup type string.
     * @param gpio The GPIO chip number.
     * @param scl The SCL pin number.
     * @param sda The SDA pin number.
     * @param res The RESET pin number.
     * @param delay Nanosecond delay or 0 for none.
     */
    public static void initSwI2c(final MemorySegment u8g2, final String setup, final int gpio, final int scl, final int sda,
            final int res, final long delay) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_sw_i2c");
        U8g2.init_i2c_sw(u8g2, (byte) gpio, (byte) scl, (byte) sda, (byte) res, delay);
        log.atDebug().log(String.format("Size %d x %d, draw color %d",
                U8g2.u8g2_GetDisplayWidth_Java(u8g2),
                U8g2.u8g2_GetDisplayHeight_Java(u8g2),
                U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("GPIO chip %d, SCL %d, SDA %d, RES %d, Delay %d", gpio, scl, sda, res, delay));
    }

    /**
     * Initialize SPI hardware driven display.
     *
     * @param u8g2 The {@link MemorySegment} representing the u8g2_t structure.
     * @param setup The setup type string.
     * @param gpio The GPIO chip number.
     * @param bus The SPI bus number.
     * @param dc The Data/Command pin number.
     * @param res The RESET pin number.
     * @param cs The Chip Select pin number.
     * @param spiMode The SPI mode (0-3).
     * @param maxSpeed The maximum SPI speed in Hz.
     */
    public static void initHwSpi(final MemorySegment u8g2, final String setup, final int gpio, final int bus, final int dc,
            final int res, final int cs, final short spiMode, final int maxSpeed) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_hw_spi");
        U8g2.init_spi_hw_advanced(u8g2, (byte) gpio, (byte) bus, (byte) dc, (byte) res, (byte) cs, spiMode, maxSpeed);
        log.atDebug().log(String.format("Size %d x %d, draw color %d",
                U8g2.u8g2_GetDisplayWidth_Java(u8g2),
                U8g2.u8g2_GetDisplayHeight_Java(u8g2),
                U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("GPIO chip %d, bus 0x%02x, DC %d, RES %d, CS %d", gpio, bus, dc, res, cs));
    }

    /**
     * Initialize SPI software driven display.
     *
     * @param u8g2 The {@link MemorySegment} representing the u8g2_t structure.
     * @param setup The setup type string.
     * @param gpio The GPIO chip number.
     * @param dc The Data/Command pin number.
     * @param res The RESET pin number.
     * @param mosi The MOSI pin number.
     * @param sck The SCK pin number.
     * @param cs The Chip Select pin number.
     * @param delay Nanosecond delay or 0 for none.
     */
    public static void initSwSpi(final MemorySegment u8g2, final String setup, final int gpio, final int dc, final int res,
            final int mosi, final int sck, final int cs, final long delay) {
        invokeSetup(u8g2, setup, "u8x8_byte_arm_linux_hw_spi");
        U8g2.init_spi_sw(u8g2, (byte) gpio, (byte) dc, (byte) res, (byte) mosi, (byte) sck, (byte) cs, delay);
        log.atDebug().log(String.format("Size %d x %d, draw color %d",
                U8g2.u8g2_GetDisplayWidth_Java(u8g2),
                U8g2.u8g2_GetDisplayHeight_Java(u8g2),
                U8g2.u8g2_GetDrawColor_Java(u8g2)));
        log.atDebug().log(String.format("GPIO chip %d, DC %d, RES %d, MOSI %d, SCK %d, CS %d, Delay %d", gpio, dc, res, mosi, sck,
                cs, delay));
    }

    /**
     * Initialize SDL driven display (simulation).
     *
     * @param u8g2 The {@link MemorySegment} representing the u8g2_t structure.
     * @param setup The SDL setup type string (e.g., "sdl_128x64_1").
     * @throws IllegalArgumentException if the setup string is not supported.
     */
    public static void initSdl(final MemorySegment u8g2, final String setup) {
        switch (setup) {
            case "sdl_128x64_1" ->
                U8g2.u8g2_SetupBuffer_SDL_128x64(u8g2, U8g2.u8g2_cb_r0());
            case "sdl_256x128_1" ->
                U8g2.u8g2_SetupBuffer_SDL_256x128(u8g2, U8g2.u8g2_cb_r0());
            default ->
                throw new IllegalArgumentException("Unsupported SDL setup: " + setup);
        }
        U8g2.init_sdl(u8g2);
        log.atDebug().log(String.format("Size %d x %d, draw color %d",
                U8g2.u8g2_GetDisplayWidth_Java(u8g2),
                U8g2.u8g2_GetDisplayHeight_Java(u8g2),
                U8g2.u8g2_GetDrawColor_Java(u8g2)));
    }

    /**
     * Resolve a font address by its name.
     *
     * @param fontName The name of the font (prefix "u8g2_font_" is optional).
     * @return A {@link MemorySegment} pointing to the native font data.
     * @throws RuntimeException if the font symbol cannot be found.
     */
    public static MemorySegment getFont(final String fontName) {
        final var name = fontName.startsWith("u8g2_font_") ? fontName : "u8g2_font_" + fontName;
        return LOOKUP.find(name).orElseThrow(() -> new RuntimeException("Font not found: " + name));
    }

    /**
     * Invokes the native u8g2_Setup function dynamically.
     *
     * @param u8g2 The {@link MemorySegment} representing the u8g2_t structure.
     * @param setup The name of the setup function.
     * @param callback The name of the communication callback function.
     * @throws RuntimeException if native symbol lookup or invocation fails.
     */
    private static void invokeSetup(final MemorySegment u8g2, final String setup, final String callback) {
        final var setupName = setup.startsWith("u8g2_Setup") ? setup : "u8g2_Setup_" + setup;
        final var setupAddr = LOOKUP.find(setupName).orElseThrow(() -> new RuntimeException("Setup not found: " + setupName));
        final var cbAddr = LOOKUP.find(callback).orElseThrow(() -> new RuntimeException("Callback not found: " + callback));
        try {
            final var handle = LINKER.downcallHandle(setupAddr, FunctionDescriptor.ofVoid(
                    C_POINTER, C_POINTER, C_POINTER, C_POINTER
            ));
            handle.invokeExact(u8g2, U8g2.u8g2_cb_r0(), cbAddr, U8g2.u8x8_arm_linux_gpio_and_delay$address());
        } catch (Throwable t) {
            throw new RuntimeException("Native mapping failure for " + setupName, t);
        }
    }
}
