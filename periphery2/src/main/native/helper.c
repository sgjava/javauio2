#include "helper.h"

int periphery_gpio_bitbang(gpio_t *gpio, const uint8_t *buffer, size_t length) {
    for (size_t i = 0; i < length; i++) {
        // Example: bit-banging bits of each byte out to the GPIO pin using c-periphery
        for (int bit = 7; bit >= 0; bit--) {
            uint8_t val = (buffer[i] >> bit) & 1;
            if (gpio_write(gpio, val) < 0) {
                return -1; // Handle error
            }
        }
    }
    return 0;
}
