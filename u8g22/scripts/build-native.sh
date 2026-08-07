#!/bin/bash
#
# Created on February 1, 2026
#
# @author: sgoldsmith
#
# 1. Flattens U8g2 source.
# 2. Injects Bridge functions for Java FFM.
# 3. SDL2 enabled ONLY for x86_64 (Testing).
# 4. Pure Periphery for ARM (Hardware).
# 5. Applies precise ARM32 ILP32 `C_LONG` layout patch.
#
# Steven P. Goldsmith
# sgjava@gmail.com

set -e

ARCH=$1
if [ -z "$ARCH" ]; then ARCH="x86_64"; fi

# Resolve project structure
MODULE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PROJECT_ROOT=$(cd "$MODULE_ROOT/.." && pwd)

# Workspace and target paths
WORK_DIR="$PROJECT_ROOT/work/u8g2"
FLAT_DIR="$WORK_DIR/u8g2_flat"
U8G2_REPO_DIR="$WORK_DIR/u8g2_repo"
GEN_DIR="$MODULE_ROOT/target/generated-sources/jextract"
NATIVE_SRC_DIR="$MODULE_ROOT/src/main/native"

# Place directly in target/classes so Maven Assembly finds it
RES_DIR="$MODULE_ROOT/target/classes/native"

# Lock to LLVM 19 path explicitly to match system toolchain
if [ -d "/usr/lib/llvm-19/lib" ]; then
    LLVM_PATH="/usr/lib/llvm-19/lib"
else
    LLVM_PATH="/usr/lib/x86_64-linux-gnu"
fi
export LD_LIBRARY_PATH="$LLVM_PATH:/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
export JAVA_LIBRARY_PATH="$LLVM_PATH"

echo "--- Architecture:    $ARCH ---"
echo "--- Using LLVM Path: $LLVM_PATH ---"

# --- Setup Work Directory ---
mkdir -p "$WORK_DIR"
if [ ! -d "$U8G2_REPO_DIR" ]; then
    git clone --depth 1 --recurse-submodules https://github.com/olikraus/u8g2.git "$U8G2_REPO_DIR"
fi

rm -rf "$FLAT_DIR" && mkdir -p "$FLAT_DIR"
cp -a "$U8G2_REPO_DIR/csrc/." "$FLAT_DIR/"
cp -a "$U8G2_REPO_DIR/sys/sdl/common/." "$FLAT_DIR/"
cp -a "$U8G2_REPO_DIR/sys/arm-linux/c-periphery/src/." "$FLAT_DIR/"
cp -a "$U8G2_REPO_DIR/sys/arm-linux/port/." "$FLAT_DIR/"

# Patch u8g2port.c for longer device filenames
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

# --- Toolchain Selection ---
mkdir -p gnu
JEXTRACT_SYS_INCLUDES=""
FLAGS_FILE="$WORK_DIR/compile_flags.txt"

case $ARCH in
    arm64)
        ln -sf /usr/aarch64-linux-gnu/include/gnu/stubs-lp64.h gnu/stubs-soft.h
        export CC="aarch64-linux-gnu-gcc"
        JEXT_INC="-I. -I/usr/aarch64-linux-gnu/include"
        JEXTRACT_SYS_INCLUDES="-I /usr/aarch64-linux-gnu/include"
        export JAVA_TOOL_OPTIONS="-Djextract.target.arch=aarch64"
        cat <<EOF > "$FLAGS_FILE"
--target=aarch64-linux-gnu
-I/usr/aarch64-linux-gnu/include
EOF
        rm -f u8x8_d_sdl_128x64.c u8x8_sdl_key.c 
        SDL_LIBS=""
        SDL_FLAGS=""
        ;;
    arm32)
        ln -sf /usr/arm-linux-gnueabihf/include/gnu/stubs-hard.h gnu/stubs-soft.h
        export CC="arm-linux-gnueabihf-gcc"
        JEXT_INC="-I. -I/usr/arm-linux-gnueabihf/include"
        JEXTRACT_SYS_INCLUDES="-I /usr/arm-linux-gnueabihf/include"
        export JAVA_TOOL_OPTIONS="-Djextract.target.arch=arm"
        cat <<EOF > "$FLAGS_FILE"
--target=arm-linux-gnueabihf
-m32
-I/usr/arm-linux-gnueabihf/include
EOF
        rm -f u8x8_d_sdl_128x64.c u8x8_sdl_key.c 
        SDL_LIBS=""
        SDL_FLAGS=""
        ;;
    *)
        export CC="gcc"
        JEXT_INC="-I."
        rm -f "$FLAGS_FILE"
        touch "$FLAGS_FILE"
        unset JAVA_TOOL_OPTIONS
        SDL_CFLAGS=$(pkg-config --cflags sdl2)
        SDL_LIBS=$(pkg-config --libs sdl2)
        SDL_FLAGS="-DUSE_SDL $SDL_CFLAGS"
        ;;
esac

COMMON_DEFS="-DPERIPHERY_GPIO_CDEV_SUPPORT=1 -D__ARM_LINUX__ -DU8G2_WITH_FULL_BUFFER=1"

echo "Compiling..."
$CC -I. $SDL_FLAGS $COMMON_DEFS -fPIC -O3 -c *.c > /dev/null 2>&1

echo "Linking..."
$CC -shared -rdynamic -Wl,--no-as-needed -o libu8g2.so *.o $SDL_LIBS > /dev/null 2>&1

# --- Deploy to Target ---
mkdir -p "$RES_DIR"
cp "libu8g2.so" "$RES_DIR/"

echo "Running jextract..."
jextract --header-class-name U8g2 \
         -I . \
         $JEXTRACT_SYS_INCLUDES \
         $COMMON_DEFS \
         --dump-includes "$WORK_DIR/includes.txt" helper.h 2>/dev/null

grep "u8g2_flat/" "$WORK_DIR/includes.txt" | grep -v "unnamed" > "$WORK_DIR/filtered.txt"

cd "$WORK_DIR"
jextract --output "$GEN_DIR" \
         --target-package org.u8g2 \
         --header-class-name U8g2 \
         -I "$FLAT_DIR" \
         $JEXTRACT_SYS_INCLUDES \
         $COMMON_DEFS \
         "@filtered.txt" "$FLAT_DIR/helper.h" 2>/dev/null

# --------------------------------------------------
# ARM32 Post-Processing Patches
# --------------------------------------------------
if [ "$ARCH" = "arm32" ]; then
    echo "Applying ARM32 C_LONG layout patch to generated U8g2 sources..."
    SHARED_JAVA=$(find "$GEN_DIR" -name "U8g2\$shared.java" -o -name "*shared*.java" | head -n 1)
    if [ -f "$SHARED_JAVA" ]; then
        echo "  -> Patching C_LONG layout cast in $SHARED_JAVA"
        sed -i 's/public static final ValueLayout\.OfLong C_LONG = (ValueLayout\.OfLong)/public static final ValueLayout\.OfInt C_LONG = (ValueLayout\.OfInt)/g' "$SHARED_JAVA"
    fi
fi

echo "----------------------------------------------------"
echo "Build Successful for $ARCH."
echo "----------------------------------------------------"
