#!/bin/bash
#
# Created on February 1, 2026
#
# @author: sgoldsmith
#
# 1. Compiles c-periphery and custom native helpers for target ARCH with CDEV support.
# 2. Uses QEMU to probe handle sizes and exports them to shell.
# 3. Generates hardware-accurate Java FFM bindings including all functions and custom helpers.
# 4. Applies precise ARM32 ILP32 `C_LONG` layout patch to target bindings.
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
NATIVE_SRC_DIR="$MODULE_ROOT/src/main/native"

# Lock to LLVM 19 path explicitly
if [ -d "/usr/lib/llvm-19/lib" ]; then
    LLVM_PATH="/usr/lib/llvm-19/lib"
else
    LLVM_PATH="/usr/lib/x86_64-linux-gnu"
fi

export LD_LIBRARY_PATH="$LLVM_PATH:/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export JAVA_LIBRARY_PATH="$LLVM_PATH"

echo "--- Architecture:    $ARCH ---"
echo "--- Using LLVM Path: $LLVM_PATH ---"

# --------------------------------------------------
# STEP 1: Build Native C-Periphery & Custom Helpers
# --------------------------------------------------
mkdir -p "$WORK_DIR"
if [ ! -d "$C_SRC_DIR" ]; then
    git clone https://github.com/vsergeev/c-periphery.git "$C_SRC_DIR"
fi

# Ensure custom native sources exist
if [ ! -f "$NATIVE_SRC_DIR/helper.c" ] || [ ! -f "$NATIVE_SRC_DIR/helper.h" ]; then
    echo "ERROR: Native source files (helper.c or helper.h) not found in $NATIVE_SRC_DIR"
    exit 1
fi

# Copy custom helper files into c-periphery src tree so CMake compiles them automatically
cp "$NATIVE_SRC_DIR/helper.c" "$C_SRC_DIR/src/"
cp "$NATIVE_SRC_DIR/helper.h" "$C_SRC_DIR/src/"

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
FLAGS_FILE="$WORK_ARTIFACTS/compile_flags.txt"

cat <<EOF > "$WRAPPER"
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
struct gpio_handle    { unsigned char reserved[$GPIO_SIZE]; };
struct i2c_handle     { unsigned char reserved[$I2C_SIZE]; };
struct led_handle     { unsigned char reserved[$LED_SIZE]; };
struct mmio_handle    { unsigned char reserved[$MMIO_SIZE]; };
struct pwm_handle     { unsigned char reserved[$PWM_SIZE]; };
struct serial_handle { unsigned char reserved[$SERIAL_SIZE]; };
struct spi_handle     { unsigned char reserved[$SPI_SIZE]; };
EOF

ls "$C_SRC_DIR/src/"*.h | grep -v "_internal.h" | sed 's|.*|#include "&"|' >> "$WRAPPER"
# Explicitly include custom helper header for jextract binding generation
echo '#include "helper.h"' >> "$WRAPPER"

# Setup compile_flags.txt for LibClang AND JAVA_TOOL_OPTIONS for jextract JVM layout generator
JEXTRACT_SYS_INCLUDES=""

case $ARCH in
    arm64)
        cat <<EOF > "$FLAGS_FILE"
--target=aarch64-linux-gnu
-I/usr/aarch64-linux-gnu/include
EOF
        JEXTRACT_SYS_INCLUDES="-I /usr/aarch64-linux-gnu/include"
        export JAVA_TOOL_OPTIONS="-Djextract.target.arch=aarch64"
        ;;
    arm32)
        cat <<EOF > "$FLAGS_FILE"
--target=arm-linux-gnueabihf
-m32
-I/usr/arm-linux-gnueabihf/include
EOF
        JEXTRACT_SYS_INCLUDES="-I /usr/arm-linux-gnueabihf/include"
        export JAVA_TOOL_OPTIONS="-Djextract.target.arch=arm"
        ;;
    *)
        rm -f "$FLAGS_FILE"
        touch "$FLAGS_FILE"
        unset JAVA_TOOL_OPTIONS
        ;;
esac

# Execute jextract from the directory containing compile_flags.txt
cd "$WORK_ARTIFACTS"

jextract --header-class-name Periphery \
         -I "$C_SRC_DIR/src" \
         $JEXTRACT_SYS_INCLUDES \
         --dump-includes "$INCLUDES" "$WRAPPER"

grep "c-periphery/src/" "$INCLUDES" | grep -v "_ops" | grep -v "unnamed" > "$FILTERED"
# Ensure helper functions are included in the filter list
grep "helper.h" "$INCLUDES" >> "$FILTERED" || true

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
         $JEXTRACT_SYS_INCLUDES \
         "@$FILTERED" "$WRAPPER"

# --------------------------------------------------
# STEP 4: ARM32 Post-Processing Patches
# --------------------------------------------------
if [ "$ARCH" = "arm32" ]; then
    echo "Applying ARM32 C_LONG layout patch to generated sources..."

    # 1. Patch C_LONG canonical layout cast in Periphery$shared.java precisely
    SHARED_JAVA=$(find "$GEN_DIR" -name "Periphery\$shared.java" -o -name "*shared*.java" | head -n 1)
    if [ -f "$SHARED_JAVA" ]; then
        echo "  -> Patching C_LONG layout cast in $SHARED_JAVA"
        sed -i 's/public static final ValueLayout\.OfLong C_LONG = (ValueLayout\.OfLong)/public static final ValueLayout\.OfInt C_LONG = (ValueLayout\.OfInt)/g' "$SHARED_JAVA"
    fi
fi

touch "$GEN_DIR/.arch_$ARCH"
echo "Build Successful for $ARCH."
