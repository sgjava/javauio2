#ifndef HELPER_H
#define HELPER_H

#include <stdint.h>
#include <stddef.h>
#include "gpio.h"

// Custom routine using c-periphery's gpio_t to bit-bang a buffer
int periphery_gpio_bitbang(gpio_t *gpio, const uint8_t *buffer, size_t length);

#endif
