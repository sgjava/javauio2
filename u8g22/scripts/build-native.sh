#!/bin/bash
#
# Created on February 1, 2026
# Updated for dynamic SDL setup and externalized native source
# @author: sgoldsmith

set -e

ARCH=$1
if [ -z "$ARCH" ]; then ARCH="x86_64"; fi

MODULE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PROJECT_ROOT=$(cd "$MODULE_ROOT/.." && pwd)
WORK_DIR="$PROJECT_ROOT/work/u8g2"
FLAT_DIR="$WORK_DIR/u8g2_flat"
U8G2_REPO_DIR="$WORK_DIR/u8g2_repo"
GEN_DIR="$MODULE_ROOT/target/generated-sources/jextract"
RES_DIR="$MODULE_ROOT/src/main/resources/native"
NATIVE_SRC_DIR="$MODULE_ROOT/src/main/native"

LLVM_PATH=$(ls -d /usr/lib/llvm-* | sort -V | tail -n 1)/lib
export LD_LIBRARY_PATH=$LLVM_PATH

echo "--- Architecture: $ARCH ---"

mkdir -p "$WORK_DIR"
if [ ! -d "$U8G2_REPO_DIR" ]; then
    git clone --depth 1 --recurse-submodules https://github.com/olikraus/u8g2.git "$U8G2_REPO_DIR"
fi

rm -rf "$FLAT_DIR" && mkdir -p "$FLAT_DIR"
cp -a "$U8G2_REPO_DIR/csrc/." "$FLAT_DIR/"
cp -a "$U8G2_REPO_DIR/sys/sdl/common/." "$FLAT_DIR/"
cp -a "$U8G2_REPO_DIR/sys/arm-linux/c-periphery/src/." "$FLAT_DIR/"
cp -a "$U8G2_REPO_DIR/sys/arm-linux/port/." "$FLAT_DIR/"

# Patch u8g2port.c for longer device filenames (e.g. /dev/gpiochipN)
sed -i 's/char filename\[16\];/char filename\[32\];/g' "$FLAT_DIR/u8g2port.c"
sed -i 's/char filename\[11\];/char filename\[32\];/g' "$FLAT_DIR/u8g2port.c"
sed -i 's/char filename\[15\];/char filename\[32\];/g' "$FLAT_DIR/u8g2port.c"

# --- Pull Externalized Helper Files ---

echo "Copying native wrappers from $NATIVE_SRC_DIR..."
if [ ! -f "$NATIVE_SRC_DIR/helper.c" ] || [ ! -f "$NATIVE_SRC_DIR/helper.h" ]; then
    echo "ERROR: Native source files (helper.c or helper.h) not found in $NATIVE_SRC_DIR"
    exit 1
fi

cp "$NATIVE_SRC_DIR/helper.c" "$FLAT_DIR/"
cp "$NATIVE_SRC_DIR/helper.h" "$FLAT_DIR/"

cd "$FLAT_DIR"

mkdir -p gnu
case $ARCH in
    arm64)
        ln -sf /usr/aarch64-linux-gnu/include/gnu/stubs-lp64.h gnu/stubs-soft.h
        export CC="aarch64-linux-gnu-gcc"
        JEXT_INC="-I. -I/usr/aarch64-linux-gnu/include"
        rm -f u8x8_d_sdl_128x64.c u8x8_sdl_key.c 
        SDL_LIBS=""
        SDL_FLAGS=""
        ;;
    arm32)
        ln -sf /usr/arm-linux-gnueabihf/include/gnu/stubs-hard.h gnu/stubs-soft.h
        export CC="arm-linux-gnueabihf-gcc"
        JEXT_INC="-I. -I/usr/arm-linux-gnueabihf/include"
        rm -f u8x8_d_sdl_128x64.c u8x8_sdl_key.c 
        SDL_LIBS=""
        SDL_FLAGS=""
        ;;
    *)
        export CC="gcc"
        JEXT_INC="-I."
        SDL_CFLAGS=$(pkg-config --cflags sdl2)
        SDL_LIBS=$(pkg-config --libs sdl2)
        SDL_FLAGS="-DUSE_SDL $SDL_CFLAGS"
        ;;
esac

COMMON_DEFS="-DPERIPHERY_GPIO_CDEV_SUPPORT=1 -D__ARM_LINUX__ -DU8G2_WITH_FULL_BUFFER=1"

echo "Compiling..."
$CC -I. $SDL_FLAGS $COMMON_DEFS -fPIC -O3 -c *.c

echo "Linking..."
$CC -shared -rdynamic -Wl,--no-as-needed -o libu8g2.so *.o $SDL_LIBS

mkdir -p "$RES_DIR"
cp "libu8g2.so" "$RES_DIR/"

echo "Running jextract..."
# jextract now points directly to helper.h
jextract --header-class-name U8g2 $JEXT_INC $COMMON_DEFS --dump-includes "$WORK_DIR/includes.txt" helper.h
grep "u8g2_flat/" "$WORK_DIR/includes.txt" | grep -v "unnamed" > "$WORK_DIR/filtered.txt"

jextract --output "$GEN_DIR" \
         --target-package org.u8g2 \
         --header-class-name U8g2 \
         $JEXT_INC $COMMON_DEFS \
         "@$WORK_DIR/filtered.txt" helper.h

echo "----------------------------------------------------"
echo "Build Successful for $ARCH."
echo "----------------------------------------------------"
