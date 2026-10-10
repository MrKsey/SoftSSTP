"""Сборка ресурсов иконки SoftSSTP для всех платформ.

Использует генератор из generate_icons.py (ключ на синем фоне) и раскладывает
готовые файлы по проекту SoftSSTP:
  assets/icon/*            - исходники для flutter_launcher_icons
  android/app/src/main/res - mipmap + adaptive icon
  ios / macos / web / windows - иконки платформ

Запуск: python make_app_icons.py
"""

import os

from PIL import Image, ImageDraw

import generate_icons as g

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "..", "SoftSSTP")
S = g.S

# Доли габарита ключа: safe-зона для адаптивной, крупнее для legacy
FOREGROUND_FIT = 0.27
LEGACY_FIT = 0.42
MASKABLE_FIT = 0.30

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def blue_bg():
    return g.diag_gradient(g.BLUE_TOP, g.BLUE_BOTTOM).convert("RGBA")


def key_rgba(fit, color):
    return g.mask_to_rgba(g.key_mask(fit=fit), color)


def composed(fit):
    """Полная иконка: синий фон + белый ключ."""
    bg = blue_bg()
    bg.alpha_composite(key_rgba(fit, g.WHITE))
    return bg


def ensure(path):
    os.makedirs(path, exist_ok=True)
    return path


def save(img, path, size):
    if img.size != (size, size):
        img = img.resize((size, size), Image.LANCZOS)
    img.save(path)


def build_source_assets():
    out = ensure(os.path.join(APP, "assets", "icon"))
    save(composed(LEGACY_FIT).convert("RGB"), os.path.join(out, "icon.png"), 1024)
    save(key_rgba(FOREGROUND_FIT, g.WHITE), os.path.join(out, "icon_foreground.png"), 1024)
    save(blue_bg(), os.path.join(out, "icon_background.png"), 1024)
    save(key_rgba(FOREGROUND_FIT, g.MONO_TINT), os.path.join(out, "icon_monochrome.png"), 1024)
    print("source assets ->", out)


def build_android():
    res = os.path.join(APP, "android", "app", "src", "main", "res")
    legacy = composed(LEGACY_FIT)
    fg = key_rgba(FOREGROUND_FIT, g.WHITE)
    bg = blue_bg()
    mono = key_rgba(FOREGROUND_FIT, g.MONO_TINT)
    for d, k in DENSITIES.items():
        folder = ensure(os.path.join(res, f"mipmap-{d}"))
        save(legacy.convert("RGB"), os.path.join(folder, "ic_launcher.png"), int(48 * k))
        save(fg, os.path.join(folder, "ic_launcher_foreground.png"), int(108 * k))
        save(bg, os.path.join(folder, "ic_launcher_background.png"), int(108 * k))
        save(mono, os.path.join(folder, "ic_launcher_monochrome.png"), int(108 * k))
    anydpi = ensure(os.path.join(res, "mipmap-anydpi-v26"))
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(xml)
    print("android res ->", res)


def build_web():
    icons = ensure(os.path.join(APP, "web", "icons"))
    legacy = composed(LEGACY_FIT)
    maskable = composed(MASKABLE_FIT)
    save(legacy.convert("RGB"), os.path.join(icons, "Icon-192.png"), 192)
    save(legacy.convert("RGB"), os.path.join(icons, "Icon-512.png"), 512)
    save(maskable.convert("RGB"), os.path.join(icons, "Icon-maskable-192.png"), 192)
    save(maskable.convert("RGB"), os.path.join(icons, "Icon-maskable-512.png"), 512)
    save(legacy.convert("RGB"), os.path.join(APP, "web", "favicon.png"), 32)
    print("web icons ->", icons)


def build_ios():
    folder = ensure(os.path.join(
        APP, "ios", "Runner", "Assets.xcassets", "AppIcon.appiconset"))
    legacy = composed(LEGACY_FIT).convert("RGB")
    sizes = {
        "Icon-App-20x20@1x.png": 20, "Icon-App-20x20@2x.png": 40,
        "Icon-App-20x20@3x.png": 60, "Icon-App-29x29@1x.png": 29,
        "Icon-App-29x29@2x.png": 58, "Icon-App-29x29@3x.png": 87,
        "Icon-App-40x40@1x.png": 40, "Icon-App-40x40@2x.png": 80,
        "Icon-App-40x40@3x.png": 120, "Icon-App-60x60@2x.png": 120,
        "Icon-App-60x60@3x.png": 180, "Icon-App-76x76@1x.png": 76,
        "Icon-App-76x76@2x.png": 152, "Icon-App-83.5x83.5@2x.png": 167,
        "Icon-App-1024x1024@1x.png": 1024,
    }
    for name, size in sizes.items():
        save(legacy, os.path.join(folder, name), size)
    print("ios icons ->", folder)


def build_macos():
    folder = ensure(os.path.join(
        APP, "macos", "Runner", "Assets.xcassets", "AppIcon.appiconset"))
    legacy = composed(LEGACY_FIT).convert("RGB")
    for size in (16, 32, 64, 128, 256, 512, 1024):
        save(legacy, os.path.join(folder, f"app_icon_{size}.png"), size)
    print("macos icons ->", folder)


def build_windows():
    folder = ensure(os.path.join(APP, "windows", "runner", "resources"))
    legacy = composed(LEGACY_FIT).convert("RGBA")
    path = os.path.join(folder, "app_icon.ico")
    legacy.save(path, sizes=[(16, 16), (32, 32), (48, 48), (64, 64),
                             (128, 128), (256, 256)])
    print("windows icon ->", path)


if __name__ == "__main__":
    build_source_assets()
    build_android()
    build_web()
    build_ios()
    build_macos()
    build_windows()
