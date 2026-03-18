#!/usr/bin/env zsh
# Regenerates Android app icon resources from branding source images.
# Uses macOS `sips` — no extra dependencies needed.
#
# Usage: ./branding/generate_icons.sh

set -e

SCRIPT_DIR="${0:A:h}"
PROJECT_DIR="${SCRIPT_DIR:h}"
RES_DIR="$PROJECT_DIR/app/src/main/res"

MAIN_ICON="$SCRIPT_DIR/app_icon_main.png"
FOREGROUND="$SCRIPT_DIR/app_icon_foreground.png"
MONOCHROME="$SCRIPT_DIR/app_icon_monochrome.png"

resize_to_webp() {
    local src="$1" size="$2" dest="$3"
    local tmp="${dest}.tmp.png"
    cp "$src" "$tmp"
    sips -z "$size" "$size" "$tmp" --out "$tmp" > /dev/null 2>&1
    if command -v cwebp &> /dev/null; then
        cwebp -q 90 "$tmp" -o "$dest" > /dev/null 2>&1
        rm "$tmp"
    else
        mv "$tmp" "$dest"
    fi
}

resize_to_png() {
    local src="$1" size="$2" dest="$3"
    cp "$src" "$dest"
    sips -z "$size" "$size" "$dest" --out "$dest" > /dev/null 2>&1
}

# Mipmap: launcher icon densities
mipmap_densities=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
mipmap_sizes=(48 72 96 144 192)

# Drawable: adaptive icon foreground/monochrome (108dp base)
drawable_densities=(mdpi hdpi xhdpi xxhdpi xxxhdpi)
drawable_sizes=(108 162 216 324 432)

echo "Generating mipmap icons..."
for i in {1..${#mipmap_densities}}; do
    density=${mipmap_densities[$i]}
    size=${mipmap_sizes[$i]}
    dir="$RES_DIR/mipmap-$density"
    mkdir -p "$dir"
    resize_to_webp "$MAIN_ICON" "$size" "$dir/ic_launcher.webp"
    resize_to_webp "$MAIN_ICON" "$size" "$dir/ic_launcher_round.webp"
    echo "  mipmap-$density: ${size}x${size}"
done

echo "Generating foreground drawables..."
for i in {1..${#drawable_densities}}; do
    density=${drawable_densities[$i]}
    size=${drawable_sizes[$i]}
    dir="$RES_DIR/drawable-$density"
    mkdir -p "$dir"
    resize_to_png "$FOREGROUND" "$size" "$dir/ic_launcher_foreground.png"
    echo "  drawable-$density: ${size}x${size}"
done

echo "Generating monochrome drawables..."
for i in {1..${#drawable_densities}}; do
    density=${drawable_densities[$i]}
    size=${drawable_sizes[$i]}
    dir="$RES_DIR/drawable-$density"
    mkdir -p "$dir"
    resize_to_png "$MONOCHROME" "$size" "$dir/ic_launcher_monochrome.png"
    echo "  drawable-$density: ${size}x${size}"
done

echo "Done! All icon resources regenerated."
