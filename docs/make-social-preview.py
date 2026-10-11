"""Builds the GitHub social preview card (1280x640) for SoftSSTP.

Uses the app palette (dark theme) and two real screenshots so the card matches
the product. Run from the repository root:

    python docs/make-social-preview.py
"""

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1280, 640
BG_TOP = (0, 0, 0)
BG_BOTTOM = (28, 28, 30)
PRIMARY = (124, 124, 255)
TEXT_PRIMARY = (255, 255, 255)
TEXT_SECONDARY = (142, 142, 147)

FONT_DIR = "C:/Windows/Fonts"
BOLD = f"{FONT_DIR}/segoeuib.ttf"
SEMI = f"{FONT_DIR}/seguisb.ttf"
REGULAR = f"{FONT_DIR}/segoeui.ttf"


def vertical_gradient(size, top, bottom):
    w, h = size
    base = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(h - 1, 1)
        base.putpixel(
            (0, y),
            tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)),
        )
    return base.resize((w, h))


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, img.size[0] - 1, img.size[1] - 1), radius=radius, fill=255
    )
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(path, height, radius=34, crop_top=0.0, crop_bottom=1.0):
    """Loads a screenshot, crops a vertical band and scales it to `height`."""
    im = Image.open(path).convert("RGB")
    w, h = im.size
    im = im.crop((0, int(h * crop_top), w, int(h * crop_bottom)))
    scale = height / im.size[1]
    im = im.resize((round(im.size[0] * scale), height), Image.LANCZOS)
    return rounded(im, radius)


def paste_with_shadow(canvas, card, xy, blur=26, offset=(0, 16), alpha=170):
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer = Image.new("RGBA", card.size, (0, 0, 0, 0))
    layer.paste((0, 0, 0, alpha), (0, 0), card.split()[3])
    shadow.paste(
        layer, (xy[0] + offset[0], xy[1] + offset[1]), layer
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(card, xy)


def main():
    canvas = vertical_gradient((W, H), BG_TOP, BG_BOTTOM).convert("RGBA")

    # Accent glow behind the phones.
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        (700, -60, 1420, 660), fill=(*PRIMARY, 46)
    )
    glow = glow.filter(ImageFilter.GaussianBlur(150))
    canvas.alpha_composite(glow)

    # Left column: title block.
    eyebrow = ImageFont.truetype(SEMI, 26)
    title = ImageFont.truetype(BOLD, 122)
    tagline = ImageFont.truetype(REGULAR, 34)
    badge = ImageFont.truetype(SEMI, 23)

    draw = ImageDraw.Draw(canvas)
    x = 80
    draw.text((x, 150), "ANDROID VPN CLIENT", font=eyebrow, fill=PRIMARY)
    draw.text((x - 6, 190), "SoftSSTP", font=title, fill=TEXT_PRIMARY)
    draw.text(
        (x, 352),
        "Microsoft SSTP and SoftEther",
        font=tagline,
        fill=TEXT_PRIMARY,
    )
    draw.text(
        (x, 396),
        "SSL-VPN over TCP",
        font=tagline,
        fill=TEXT_PRIMARY,
    )

    # Protocol / feature chips.
    chips = ["SSTP + SoftEther", "Split tunneling", "VPN Gate"]
    cx, cy = x, 476
    for label in chips:
        tw = draw.textlength(label, font=badge)
        pad_x, pad_y = 20, 11
        box = (cx, cy, cx + tw + pad_x * 2, cy + 24 + pad_y * 2)
        draw.rounded_rectangle(
            box, radius=18, fill=(44, 44, 46, 255), outline=(60, 60, 62, 255)
        )
        draw.text((cx + pad_x, cy + pad_y), label, font=badge, fill=TEXT_PRIMARY)
        cx = box[2] + 14

    # Keep the text column clear of the phones.
    assert cx <= 720, f"text column overflows into the phones: {cx:.0f}px"

    # Right column: two overlapping phones.
    back = phone("docs/screenshots/screenshot-3.jpg", 560, crop_bottom=0.72)
    front = phone("docs/screenshots/screenshot-2.jpg", 600, crop_bottom=0.70)

    paste_with_shadow(canvas, back, (772, 70))
    paste_with_shadow(canvas, front, (995, 50))

    out = canvas.convert("RGB")
    out.save("docs/social-preview.png", "PNG", optimize=True)
    print("wrote docs/social-preview.png", out.size)


if __name__ == "__main__":
    main()
