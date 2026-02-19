#!/bin/bash
#
# Created on February 1, 2026
#
# @author: sgoldsmith
#
# 1. Compiles c-periphery for target ARCH with CDEV support.
# 2. Uses QEMU to probe handle sizes and exports them to shell.
# 3. Generates hardware-accurate Java FFM bindings including all functions.
#
# Steven P. Goldsmith
# sgjava@gmail.com

set -e

ARCH=$1
if [ -z "$ARCH" ]; then ARCH="x86_64"; fi

MODULE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PROJECT_ROOT=$(cd "$MODULE_ROOT/.." && pwd)
WORK_DIR="$PROJECT_ROOT/work/periphery"
C_SRC_DIR="$WORK_DIR/c-periphery"
WORK_ARTIFACTS="$WORK_DIR/build-artifacts/$ARCH"
GEN_DIR="$MODULE_ROOT/target/generated-sources/jextract"
RES_DIR="$MODULE_ROOT/target/classes/native"

LLVM_PATH=$(ls -d /usr/lib/llvm-* | sort -V | tail -n 1)/lib
export LD_LIBRARY_PATH=$LLVM_PATH

echo "--- Architecture:    $ARCH ---"

# --------------------------------------------------
# STEP 1: Build Native C-Periphery
# --------------------------------------------------
mkdir -p "$WORK_DIR"
if [ ! -d "$C_SRC_DIR" ]; then
    git clone https://github.com/vsergeev/c-periphery.git "$C_SRC_DIR"
fi

mkdir -p "$C_SRC_DIR/build"
cd "$C_SRC_DIR/build"

case $ARCH in
    arm64) 
        export CC=aarch64-linux-gnu-gcc
        CMAKE_OPTS="-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64"
        ;;
    arm32) 
        export CC=arm-linux-gnueabihf-gcc
        CMAKE_OPTS="-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=arm"
        ;;
    *)     
        export CC=gcc
        CMAKE_OPTS=""
        ;;
esac

# 1. We removed CMAKE_C_FLAGS redefinition to stop "redefined" warnings
# 2. Added -DBUILD_TESTS=OFF to stop the fgets warnings
cmake -DBUILD_SHARED_LIBS=ON -DBUILD_TESTS=OFF $CMAKE_OPTS .. > /dev/null
make -j$(nproc) > /dev/null

mkdir -p "$RES_DIR"
cp "libperiphery.so" "$RES_DIR/"

# --------------------------------------------------
# STEP 2: Deep Probe Handle Sizes
# --------------------------------------------------
echo "Probing exact handle sizes for $ARCH..."
mkdir -p "$WORK_ARTIFACTS"

cat <<EOF > "$WORK_ARTIFACTS/sizer.c"
#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#ifdef _XOPEN_SOURCE
#undef _XOPEN_SOURCE
#endif
#define PERIPHERY_GPIO_CDEV_SUPPORT 1
#include "gpio.c"
#include "i2c.c"
#include "led.c"
#include "mmio.c"
#include "pwm.c"
#include "serial.c"
#include "spi.c"
const struct gpio_ops gpio_cdev_ops = {0};
const struct gpio_ops gpio_sysfs_ops = {0};
int main() {
    printf("export GPIO_SIZE=%zu\n", sizeof(struct gpio_handle));
    printf("export I2C_SIZE=%zu\n", sizeof(struct i2c_handle));
    printf("export LED_SIZE=%zu\n", sizeof(struct led_handle));
    printf("export MMIO_SIZE=%zu\n", sizeof(struct mmio_handle));
    printf("export PWM_SIZE=%zu\n", sizeof(struct pwm_handle));
    printf("export SERIAL_SIZE=%zu\n", sizeof(struct serial_handle));
    printf("export SPI_SIZE=%zu\n", sizeof(struct spi_handle));
    return 0;
}
EOF

$CC -w -D_GNU_SOURCE -DPERIPHERY_GPIO_CDEV_SUPPORT=1 -I "$C_SRC_DIR/src" "$WORK_ARTIFACTS/sizer.c" -o "$WORK_ARTIFACTS/sizer"

HOST_ARCH=$(uname -m)
case $ARCH in
    arm64)
        if [ "$HOST_ARCH" != "aarch64" ]; then
            eval "$(QEMU_LD_PREFIX=/usr/aarch64-linux-gnu qemu-aarch64 $WORK_ARTIFACTS/sizer)"
        else
            eval "$($WORK_ARTIFACTS/sizer)"
        fi
        ;;
    arm32)
        if [[ "$HOST_ARCH" != arm* ]]; then
            eval "$(QEMU_LD_PREFIX=/usr/arm-linux-gnueabihf qemu-arm $WORK_ARTIFACTS/sizer)"
        else
            eval "$($WORK_ARTIFACTS/sizer)"
        fi
        ;;
    *)
        eval "$($WORK_ARTIFACTS/sizer)"
        ;;
esac

echo "Discovered Sizes: GPIO=$GPIO_SIZE, I2C=$I2C_SIZE, LED=$LED_SIZE"

# --------------------------------------------------
# STEP 3: Generate Jextract Bindings
# --------------------------------------------------
WRAPPER="$WORK_ARTIFACTS/wrapper.h"
INCLUDES="$WORK_ARTIFACTS/includes.txt"
FILTERED="$WORK_ARTIFACTS/filtered_includes.txt"

cat <<EOF > "$WRAPPER"
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
struct gpio_handle   { unsigned char reserved[$GPIO_SIZE]; };
struct i2c_handle    { unsigned char reserved[$I2C_SIZE]; };
struct led_handle    { unsigned char reserved[$LED_SIZE]; };
struct mmio_handle   { unsigned char reserved[$MMIO_SIZE]; };
struct pwm_handle    { unsigned char reserved[$PWM_SIZE]; };
struct serial_handle { unsigned char reserved[$SERIAL_SIZE]; };
struct spi_handle    { unsigned char reserved[$SPI_SIZE]; };
EOF

ls "$C_SRC_DIR/src/"*.h | grep -v "_internal.h" | sed 's|.*|#include "&"|' >> "$WRAPPER"

jextract --header-class-name Periphery -I "$C_SRC_DIR/src" --dump-includes "$INCLUDES" "$WRAPPER" 2>/dev/null
grep "c-periphery/src/" "$INCLUDES" | grep -v "_ops" | grep -v "unnamed" > "$FILTERED"

jextract --output "$GEN_DIR" \
         --target-package org.periphery \
         --header-class-name Periphery \
         --include-struct gpio_handle \
         --include-struct i2c_handle \
         --include-struct i2c_msg \
         --include-struct led_handle \
         --include-struct mmio_handle \
         --include-struct pwm_handle \
         --include-struct serial_handle \
         --include-struct spi_handle \
         --include-struct periphery_version \
         -I "$C_SRC_DIR/src" \
         "@$FILTERED" "$WRAPPER" 2>/dev/null

touch "$GEN_DIR/.arch_$ARCH"
echo "Build Successful for $ARCH."
