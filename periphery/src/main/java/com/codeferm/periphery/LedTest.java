package com.codeferm.periphery;

import lombok.extern.slf4j.Slf4j;
import org.periphery.NativeLoader;
import org.periphery.Periphery;
import org.periphery.gpio_handle; // Now available thanks to the dummy wrapper fix
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

@Slf4j
public class LedTest {

    static {
        NativeLoader.load();
    }

    public static void main(String[] args) {
        String chipPath = "/dev/gpiochip0";
        int line = 77;

        try (var arena = Arena.ofConfined()) {
            // Correct: Allocate using the actual C struct layout
            // This ensures perfect alignment and size (avoiding the '8 byte' guess)
            MemorySegment gpioHandle = arena.allocate(gpio_handle.layout());

            var path = arena.allocateFrom(chipPath);

            log.info("Opening {} line {}...", chipPath, line);

            // Use the verified constant: GPIO_DIR_OUT
            int ret = Periphery.gpio_open(gpioHandle, path, line, Periphery.GPIO_DIR_OUT());

            if (ret < 0) {
                log.error("Failed: {}", Periphery.gpio_errmsg(gpioHandle).getString(0));
                return;
            }

            log.info("GPIO opened successfully.");

            for (int i = 0; i < 10; i++) {
                boolean state = (i % 2 == 0);
                Periphery.gpio_write(gpioHandle, state);
                log.info("LED State: {}", state ? "HIGH" : "LOW");
                Thread.sleep(500);
            }

            // This will now exit cleanly because the pointer metadata is intact
            Periphery.gpio_close(gpioHandle);
            log.info("Test completed and handle closed.");

        } catch (Exception e) {
            log.error("Hardware error", e);
        }
    }
}
