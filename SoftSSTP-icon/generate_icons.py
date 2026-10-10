"""Генератор примера адаптивной иконки для приложения SoftSSTP.

Рисует иконку программно (Pillow), без внешних ассетов.

Адаптивная иконка Android: холст 108x108dp, видимая зона 72x72dp,
безопасная зона 66x66dp. Поэтому символ держим внутри круга
диаметром 66dp от центра - иначе лаунчер срежет углы.

Выход:
  foreground-1024.png   - передний слой (символ) с отступом безопасной зоны
  background-1024.png   - задний слой (градиент)
  monochrome-1024.png   - монохромный слой для themed icons (Android 13+)
  ic_launcher-512.png   - растровый значок (legacy / Google Play), символ крупнее
  preview-adaptive.png  - как иконку покажет лаунчер (маска 72dp)
  preview-circle.png    - legacy-круг, squircle, квадрат (full-bleed)
  compare.png           - сравнение трёх вариантов символа

Запуск: python generate_icons.py
"""

import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))
S = 1024                 # итоговый размер холста
SS = 4                   # суперсэмплинг для сглаживания
B = S * SS               # рабочий размер отрисовки
CX = CY = B / 2          # центр

# Палитра
GRAD_TOP = (79, 70, 229)     # indigo-600
GRAD_BOTTOM = (6, 182, 212)  # cyan-500
WHITE = (255, 255, 255)
MONO_TINT = (27, 27, 31)
SURFACE = (254, 247, 255)    # Material You light surface

# Синий фон под ключ
BLUE_TOP = (33, 118, 255)    # яркий синий
BLUE_BOTTOM = (13, 71, 161)  # глубокий синий

# Доли от холста
SHIELD_HW = 0.200   # половина ширины щита
SHIELD_HH = 0.250   # половина высоты щита (верхний угол ложится в safe-зону)
SHIELD_R = 0.075    # радиус верхних углов


def quad(p0, p1, p2, n=40):
    """Точки квадратичной кривой Безье."""
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        pts.append((x, y))
    return pts


def shield_poly():
    a = SHIELD_HW * B
    b = SHIELD_HH * B
    r = SHIELD_R * B
    left, right = CX - a, CX + a
    top, bot = CY - b, CY + b
    y1 = CY + 0.05 * b

    pts = []
    pts += quad((left, top + r), (left, top), (left + r, top), 24)
    pts.append((right - r, top))
    pts += quad((right - r, top), (right, top), (right, top + r), 24)
    pts.append((right, y1))
    pts += quad((right, y1), (right, CY + 0.75 * b), (CX, bot), 60)
    pts += quad((CX, bot), (left, CY + 0.75 * b), (left, y1), 60)
    pts.append((left, top + r))
    return pts


def arch_poly(cx, cy, radius, base, n=80):
    """Арка (туннель): полукруг сверху + прямые стенки до base."""
    pts = [(cx - radius, base), (cx - radius, cy)]
    for i in range(n + 1):
        t = math.pi + math.pi * i / n
        pts.append((cx + radius * math.cos(t), cy + radius * math.sin(t)))
    pts.append((cx + radius, base))
    return pts


def new_mask():
    return Image.new("L", (B, B), 0)


def draw_poly(mask, pts, value):
    ImageDraw.Draw(mask).polygon(pts, fill=value)


def variant_shield_arch():
    m = new_mask()
    draw_poly(m, shield_poly(), 255)
    draw_poly(m, arch_poly(CX, CY + 0.02 * B, 0.105 * B, CY + 0.19 * B), 0)
    return m


def variant_tunnel():
    m = new_mask()
    cy = CY - 0.05 * B
    base = CY + 0.25 * B
    draw_poly(m, arch_poly(CX, cy, 0.230 * B, base), 255)
    draw_poly(m, arch_poly(CX, cy, 0.150 * B, base + 1), 0)
    draw_poly(m, arch_poly(CX, cy, 0.075 * B, base + 2), 255)
    return m


def variant_shield_arrow():
    m = new_mask()
    draw_poly(m, shield_poly(), 255)
    top = CY - 0.09 * B
    bottom = CY + 0.19 * B
    half_shaft = 0.042 * B
    half_head = 0.11 * B
    head_top = CY + 0.04 * B
    draw_poly(m, [
        (CX - half_shaft, top), (CX + half_shaft, top),
        (CX + half_shaft, head_top), (CX + half_head, head_top),
        (CX, bottom), (CX - half_head, head_top),
        (CX - half_shaft, head_top),
    ], 0)
    return m


FONT_CANDIDATES = [
    "C:/Windows/Fonts/arialbd.ttf",
    "C:/Windows/Fonts/segoeuib.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
]


def load_bold_font(px):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, px)
    return ImageFont.load_default()


def letter_mask(char, ratio):
    """Чёрно-белая маска буквы, отцентрованной на холсте."""
    tmp = new_mask()
    font = load_bold_font(int(B * ratio))
    d = ImageDraw.Draw(tmp)
    bbox = d.textbbox((0, 0), char, font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    d.text((CX - w / 2 - bbox[0], CY - h / 2 - bbox[1]), char, font=font, fill=255)
    return tmp


def variant_shield_letter_s():
    m = new_mask()
    draw_poly(m, shield_poly(), 255)
    lm = letter_mask("S", 0.30)
    lm = ImageChops.offset(lm, 0, -int(0.025 * B))
    return ImageChops.subtract(m, lm)


# --- Варианты «буква со стрелками» (без щита) --------------------------

# Кривая Безье одной дугой: гладкая S без изломов и перехлёстов.
# Управляющие точки в единичной сетке (центр 0, ось Y вниз).
S_CUBIC = [(0.45, -0.80), (-1.10, -0.80), (1.10, 0.80), (-0.45, 0.80)]
# Зеркальная Z: только прямые отрезки, углы скруглены при отрисовке.
Z_MIRROR = [(0.70, -0.75), (-0.70, -0.75), (0.70, 0.75), (-0.70, 0.75)]

S_STROKE = 0.19      # толщина штриха в единицах
S_ARROW_L = 0.42     # длина наконечника
S_ARROW_W = 0.20     # половина ширины наконечника


def sample_cubic(p0, p1, p2, p3, n=160):
    """Точки кубической кривой Безье."""
    out = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        out.append((
            mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0]
            + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
            mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1]
            + 3 * mt * t * t * p2[1] + t ** 3 * p3[1],
        ))
    return out


def arrowhead(end, tangent, length, half_width):
    """Треугольник-наконечник, направленный наружу по касательной."""
    tx, ty = tangent
    px, py = -ty, tx
    tip = (end[0] + length * tx, end[1] + length * ty)
    b1 = (end[0] + half_width * px, end[1] + half_width * py)
    b2 = (end[0] - half_width * px, end[1] - half_width * py)
    return [tip, b1, b2]


def unit_dir(a, b):
    dx, dy = a[0] - b[0], a[1] - b[1]
    n = math.hypot(dx, dy) or 1.0
    return (dx / n, dy / n)


def stroke_glyph(centerline, fit=0.300, stroke=S_STROKE,
                 arrow_l=S_ARROW_L, arrow_w=S_ARROW_W):
    """Рисует ленту по осевой линии с круглыми концами и двумя стрелками.

    Знак масштабируется так, чтобы вписаться в безопасную зону иконки.
    fit - доля холста под весь габарит знака, stroke - толщина ленты.
    """
    t_start = unit_dir(centerline[0], centerline[1])    # наружу от начала
    t_end = unit_dir(centerline[-1], centerline[-2])    # наружу от конца
    a_start = arrowhead(centerline[0], t_start, arrow_l, arrow_w)
    a_end = arrowhead(centerline[-1], t_end, arrow_l, arrow_w)

    reach = max(
        [math.hypot(*p) for p in centerline]
        + [math.hypot(*p) for p in a_start]
        + [math.hypot(*p) for p in a_end]
    ) + stroke / 2
    scale = (fit * B) / reach

    def m(pt):
        return (CX + pt[0] * scale, CY + pt[1] * scale)

    width = max(2, int(stroke * scale))
    mask = new_mask()
    dr = ImageDraw.Draw(mask)
    dr.line([m(p) for p in centerline], fill=255, width=width, joint="curve")
    # Круглые концы, чтобы лента и наконечник срастались без шва.
    for p in (centerline[0], centerline[-1]):
        x, y = m(p)
        dr.ellipse((x - width / 2, y - width / 2, x + width / 2, y + width / 2),
                   fill=255)
    dr.polygon([m(p) for p in a_start], fill=255)
    dr.polygon([m(p) for p in a_end], fill=255)
    return mask


def variant_s_arrows():
    return stroke_glyph(sample_cubic(*S_CUBIC))


# Зеркальная Z: на 20% меньше по габариту и на 20% толще линия,
# чем у базового варианта со стрелками.
def variant_z_mirror():
    return stroke_glyph(Z_MIRROR, fit=0.2479, stroke=0.285)


# --- Вариант «ключ» (для синего фона) ----------------------------------

# Геометрия в единичной сетке (центр 0, ось Y вниз).
# Ключ горизонтальный: кольцо-головка слева, стержень вправо, зубья вниз.
KEY_BOW_C = (-0.30, 0.0)     # центр головки
KEY_BOW_R = 0.19             # внешний радиус головки (компактнее)
KEY_BOW_HOLE = 0.095         # радиус отверстия
KEY_SHAFT_END = 0.24         # правый конец стержня
KEY_SHAFT_W = 0.115          # толщина стержня
KEY_TEETH = [(0.05, 0.15), (0.18, 0.10)]   # (x, длина вниз) зубьев, с зазором от кольца
KEY_TOOTH_W = 0.095          # толщина зуба
KEY_FIT = 0.270              # доля холста под весь габарит


def key_mask(fit=KEY_FIT):
    """Белый силуэт горизонтального ключа, вписанный в безопасную зону."""
    bxc, byc = KEY_BOW_C
    tooth_reach = max(ln for _, ln in KEY_TEETH)

    # Габарит по осям с учётом толщин - чтобы отцентрировать знак на холсте.
    min_x = bxc - KEY_BOW_R
    max_x = KEY_SHAFT_END + KEY_SHAFT_W / 2
    min_y = byc - KEY_BOW_R
    max_y = byc + max(tooth_reach + KEY_TOOTH_W / 2, KEY_SHAFT_W / 2)
    ccx, ccy = (min_x + max_x) / 2, (min_y + max_y) / 2

    pts = [
        (min_x - ccx, byc - ccy),                # левый край головки
        (bxc + KEY_BOW_R - ccx, byc - ccy),
        (bxc - ccx, byc - KEY_BOW_R - ccy),
        (bxc - ccx, byc + KEY_BOW_R - ccy),
        (KEY_SHAFT_END - ccx, byc - ccy),
    ]
    for x, ln in KEY_TEETH:
        pts.append((x - ccx, byc + ln - ccy))
    reach = max(math.hypot(*p) for p in pts) + KEY_SHAFT_W / 2
    scale = (fit * B) / reach

    def m(pt):
        return (CX + pt[0] * scale, CY + pt[1] * scale)

    def box(cx, cy, r):
        x, y, rr = CX + cx * scale, CY + cy * scale, r * scale
        return (x - rr, y - rr, x + rr, y + rr)

    bow = (bxc - ccx, byc - ccy)
    mask = new_mask()
    dr = ImageDraw.Draw(mask)
    # Внешний круг головки, стержень и зубья рисуем одним силуэтом...
    dr.ellipse(box(bow[0], bow[1], KEY_BOW_R), fill=255)
    sw = max(2, int(KEY_SHAFT_W * scale))
    end = (KEY_SHAFT_END - ccx, byc - ccy)
    dr.line([m(bow), m(end)], fill=255, width=sw)
    dr.ellipse(box(end[0], end[1], KEY_SHAFT_W / 2), fill=255)
    tw = max(2, int(KEY_TOOTH_W * scale))
    for x, ln in KEY_TEETH:
        dr.line([m((x - ccx, byc - ccy)),
                 m((x - ccx, byc + ln - ccy))], fill=255, width=tw)
    # ...и только потом вырезаем отверстие головки, чтобы стержень его не затирал.
    dr.ellipse(box(bow[0], bow[1], KEY_BOW_HOLE), fill=0)
    return mask


# --- Вариант «скрытый туннель» -----------------------------------------

# Замкнутая капсула-труба, сквозь которую проходят две горизонтальные
# стрелки: верхняя вправо, нижняя влево (обмен данными в туннеле).
TUNNEL_ACCENT = (61, 220, 132)      # Android green #3DDC84
TUNNEL_BG_TOP = (10, 26, 63)        # глубокий неоново-синий
TUNNEL_BG_BOTTOM = (16, 46, 92)
CYAN_ACCENT = (34, 211, 238)        # бирюзовый #22D3EE
DARK_BG_TOP = (28, 28, 32)          # тёмно-серый
DARK_BG_BOTTOM = (14, 14, 17)

TUNNEL_HW = 0.21                    # половина ширины капсулы
TUNNEL_HH = 0.115                   # половина высоты капсулы
TUNNEL_WALL = 0.035                 # толщина стенки трубы
TUNNEL_ARROW_X = 0.29               # полувылет стрелок (сквозь трубу)
TUNNEL_ARROW_Y = 0.055              # смещение стрелок от оси
TUNNEL_ARROW_W = 0.042              # толщина стрелки
TUNNEL_HEAD_L = 0.10                # длина наконечника
TUNNEL_HEAD_W = 0.062               # половина ширины наконечника
TUNNEL_FIT = 0.300                  # доля холста под весь габарит


def tunnel_mask(fit=TUNNEL_FIT):
    """Силуэт туннеля: контур капсулы + две сквозные стрелки."""
    pts = [
        (TUNNEL_ARROW_X, -TUNNEL_ARROW_Y),
        (-TUNNEL_ARROW_X, -TUNNEL_ARROW_Y),
        (TUNNEL_ARROW_X, TUNNEL_ARROW_Y),
        (-TUNNEL_ARROW_X, TUNNEL_ARROW_Y),
        (TUNNEL_HW, -TUNNEL_HH),
        (-TUNNEL_HW, TUNNEL_HH),
    ]
    reach = max(math.hypot(*p) for p in pts) + TUNNEL_ARROW_W / 2
    scale = (fit * B) / reach

    def m(pt):
        return (CX + pt[0] * scale, CY + pt[1] * scale)

    def capsule(pw, ph, fill):
        x0, y0 = m((-pw, -ph))
        x1, y1 = m((pw, ph))
        dr.rounded_rectangle((x0, y0, x1, y1), radius=ph * scale, fill=fill)

    mask = new_mask()
    dr = ImageDraw.Draw(mask)
    # Труба: внешний контур минус внутренность
    capsule(TUNNEL_HW, TUNNEL_HH, 255)
    capsule(TUNNEL_HW - TUNNEL_WALL, TUNNEL_HH - TUNNEL_WALL, 0)

    aw = max(2, int(TUNNEL_ARROW_W * scale))
    hl, hw = TUNNEL_HEAD_L, TUNNEL_HEAD_W

    def arrow(y, direction):
        """Стрелка вдоль оси y; direction +1 вправо, -1 влево."""
        x_tip = direction * TUNNEL_ARROW_X
        x_base = x_tip - direction * hl
        x_tail = -x_tip
        dr.line([m((x_tail, y)), m((x_base, y))], fill=255, width=aw)
        dr.polygon([m((x_tip, y)), m((x_base, y - hw)), m((x_base, y + hw))],
                   fill=255)

    arrow(-TUNNEL_ARROW_Y, +1)
    arrow(+TUNNEL_ARROW_Y, -1)
    return mask


def diag_gradient(c1, c2):
    w = h = 256
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / (w + h - 2)
            px[x, y] = (
                int(c1[0] + (c2[0] - c1[0]) * t),
                int(c1[1] + (c2[1] - c1[1]) * t),
                int(c1[2] + (c2[2] - c1[2]) * t),
            )
    return img.resize((S, S), Image.BICUBIC)


def mask_to_rgba(mask, color, scale=1.0):
    m = mask
    if scale != 1.0:
        size = int(B * scale)
        m = m.resize((size, size), Image.LANCZOS)
    m = m.resize((S, S), Image.LANCZOS)
    rgba = Image.new("RGBA", (S, S), color + (0,))
    rgba.putalpha(m)
    return rgba


def rounded_mask(radius_ratio, size=B):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(radius_ratio * size), fill=255
    )
    return m


def circle_mask(size=B):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
    return m


def composite(bg, fg, mask):
    layer = bg.copy()
    layer.alpha_composite(fg)
    if mask.size != layer.size:
        mask = mask.resize(layer.size, Image.LANCZOS)
    layer.putalpha(mask)
    return layer


def adaptive_preview(bg, fg, shape):
    """Как иконку отрисует лаунчер: берём центральные 72dp и применяем маску."""
    canvas = bg.copy()
    canvas.alpha_composite(fg)
    half = int(S * 72 / 108 / 2)
    crop = canvas.crop((S // 2 - half, S // 2 - half, S // 2 + half, S // 2 + half))
    crop = crop.resize((S, S), Image.LANCZOS)
    mask = circle_mask(S) if shape == "circle" else rounded_mask(0.28, S)
    crop.putalpha(mask)
    return crop


def save(img, name, size=None):
    if size:
        img = img.resize((size, size), Image.LANCZOS)
    path = os.path.join(OUT, name)
    try:
        img.save(path)
        print("saved", name, img.size)
    except OSError:
        # Файл может держать редактор/превью - пишем рядом.
        alt = os.path.join(OUT, "new_" + name)
        img.save(alt)
        print("saved", "new_" + name, img.size, "(original busy)")


def write_svg():
    """Векторный мастер: щит с вырезом-аркой, холст 108x108dp."""

    def path(pts):
        d = "M" + " L".join(f"{x / B * 108:.3f} {y / B * 108:.3f}" for x, y in pts)
        return d + " Z"

    shield = path(shield_poly())
    arch = path(arch_poly(CX, CY + 0.02 * B, 0.105 * B, CY + 0.19 * B))
    svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" '
        'width="108" height="108">\n'
        f'  <path fill="#FFFFFF" fill-rule="evenodd" d="{shield} {arch}"/>\n'
        "</svg>\n"
    )
    with open(os.path.join(OUT, "foreground.svg"), "w", encoding="utf-8") as f:
        f.write(svg)
    print("saved foreground.svg")


def main():
    bg = diag_gradient(GRAD_TOP, GRAD_BOTTOM).convert("RGBA")
    save(bg, "background-1024.png")

    # Синий фон под ключ
    blue_bg = diag_gradient(BLUE_TOP, BLUE_BOTTOM).convert("RGBA")
    save(blue_bg, "background-blue-1024.png")

    variants = {
        "shield_arch": variant_shield_arch(),
        "tunnel": variant_tunnel(),
        "shield_arrow": variant_shield_arrow(),
        "shield_s": variant_shield_letter_s(),
        "s_arrows": variant_s_arrows(),
        "z_mirror": variant_z_mirror(),
        "key": key_mask(),
    }

    main_mask = variants["shield_arch"]
    fg = mask_to_rgba(main_mask, WHITE)
    save(fg, "foreground-1024.png")
    save(mask_to_rgba(main_mask, MONO_TINT), "monochrome-1024.png")

    # Legacy / Google Play: символ крупнее (full-bleed), маска-скругление 20%
    big = mask_to_rgba(main_mask, WHITE, scale=1.38)
    save(composite(bg, big, rounded_mask(0.20)), "ic_launcher-512.png", 512)

    # Как покажет лаунчер (адаптивная маска)
    save(adaptive_preview(bg, fg, "circle"), "preview-adaptive.png", 512)

    # Legacy full-bleed в разных масках
    save(composite(bg, big, circle_mask()), "preview-circle.png", 512)
    save(composite(bg, big, rounded_mask(0.28)), "preview-squircle.png", 512)
    save(composite(bg, big, rounded_mask(0.10)), "preview-square.png", 512)

    # Themed icon: монохромный слой, перекрашенный системой
    themed = Image.new("RGBA", (S, S), SURFACE + (255,))
    themed.alpha_composite(mask_to_rgba(main_mask, MONO_TINT))
    themed.putalpha(rounded_mask(0.28, S))
    save(themed, "preview-themed.png", 512)

    # Отдельное превью варианта «щит с буквой S»
    s_mask = variants["shield_s"]
    save(mask_to_rgba(s_mask, WHITE), "foreground-s-1024.png")
    save(adaptive_preview(bg, mask_to_rgba(s_mask, WHITE), "circle"),
         "preview-shield-s.png", 512)

    # Отдельное превью варианта «буква S со стрелками» (без щита)
    sa_mask = variants["s_arrows"]
    save(mask_to_rgba(sa_mask, WHITE), "foreground-s-arrows-1024.png")
    save(adaptive_preview(bg, mask_to_rgba(sa_mask, WHITE), "circle"),
         "preview-s-arrows.png", 512)
    save(mask_to_rgba(sa_mask, MONO_TINT), "monochrome-s-arrows-1024.png")

    # Отдельное превью варианта «зеркальная Z со стрелками»
    z_mask = variants["z_mirror"]
    save(mask_to_rgba(z_mask, WHITE), "foreground-z-1024.png")
    save(adaptive_preview(bg, mask_to_rgba(z_mask, WHITE), "circle"),
         "preview-z-mirror.png", 512)

    # Вариант «ключ на синем фоне»
    k_mask = variants["key"]
    k_fg = mask_to_rgba(k_mask, WHITE)
    save(k_fg, "foreground-key-1024.png")
    save(mask_to_rgba(k_mask, MONO_TINT), "monochrome-key-1024.png")
    save(adaptive_preview(blue_bg, k_fg, "circle"), "preview-key.png", 512)
    save(adaptive_preview(blue_bg, k_fg, "squircle"),
         "preview-key-squircle.png", 512)

    # Вариант «скрытый туннель»: капсула-труба со сквозными стрелками
    t_mask = tunnel_mask()
    t_fg = mask_to_rgba(t_mask, TUNNEL_ACCENT)
    save(t_fg, "foreground-tunnel-1024.png")
    save(mask_to_rgba(t_mask, MONO_TINT), "monochrome-tunnel-1024.png")
    tunnel_blue = diag_gradient(TUNNEL_BG_TOP, TUNNEL_BG_BOTTOM).convert("RGBA")
    tunnel_dark = diag_gradient(DARK_BG_TOP, DARK_BG_BOTTOM).convert("RGBA")
    save(tunnel_blue, "background-tunnel-blue-1024.png")
    save(adaptive_preview(tunnel_blue, t_fg, "circle"),
         "preview-tunnel-green.png", 512)
    save(adaptive_preview(tunnel_blue, t_fg, "squircle"),
         "preview-tunnel-green-squircle.png", 512)
    # Тот же знак, бирюзовый на тёмно-сером
    save(adaptive_preview(tunnel_dark, mask_to_rgba(t_mask, CYAN_ACCENT), "circle"),
         "preview-tunnel-cyan.png", 512)

    # Сравнение вариантов символа (у каждого свой фон, адаптивная маска)
    cell = 400
    items = [
        (variants["shield_arch"], bg),
        (variants["tunnel"], bg),
        (variants["shield_arrow"], bg),
        (variants["shield_s"], bg),
        (variants["s_arrows"], bg),
        (variants["z_mirror"], bg),
        (k_mask, blue_bg),
        (t_mask, tunnel_blue),
    ]
    sheet = Image.new("RGBA", (cell * len(items), cell), (245, 245, 247, 255))
    for i, (m, b) in enumerate(items):
        fg_i = mask_to_rgba(m, TUNNEL_ACCENT) if m is t_mask else mask_to_rgba(m, WHITE)
        layer = adaptive_preview(b, fg_i, "circle")
        layer = layer.resize((cell, cell), Image.LANCZOS)
        sheet.alpha_composite(layer, (i * cell, 0))
    save(sheet, "compare.png")
    write_svg()


if __name__ == "__main__":
    main()
