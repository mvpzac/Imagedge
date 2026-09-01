#!/usr/bin/env python3
"""Render Imagedge launcher icon (Eclipse concept v2) to PNG.

Geometry mirrors res/drawable/ic_launcher_foreground.xml (108dp viewport):
ring r=23 @ (54,54) stroke 4.0; crescent = circle r=23 @ (54,54) minus
circle r=23 @ (64.35,61.48). Colors from ui/theme/Color.kt.
"""
import os
from PIL import Image, ImageDraw

S = 4  # supersample factor
BASE = 1024
C = BASE * S // 2  # canvas center
R = 23 / 108 * BASE * S          # mark radius
STROKE = 4.0 / 108 * BASE * S    # ring stroke width
DX = 10.35 / 108 * BASE * S      # crescent offset x
DY = 7.475 / 108 * BASE * S      # crescent offset y

INK = (26, 27, 30, 255)        # #1A1B1E background
BLOCK = (232, 233, 235, 255)   # #E8E9EB crescent
LINE = (250, 250, 251, 255)    # #FAFAFB ring


def draw_icon(full_bleed: bool) -> Image.Image:
    size = BASE * S
    img = Image.new("RGBA", (size, size), INK)
    # crescent layer: light circle then dark offset circle, masked to mark circle
    layer = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(layer)
    d.ellipse([C - R, C - R, C + R, C + R], fill=255)
    crescent = Image.new("RGBA", (size, size), BLOCK)
    dc = ImageDraw.Draw(crescent)
    dc.ellipse([C + DX - R, C + DY - R, C + DX + R, C + DY + R], fill=(0, 0, 0, 0))
    img.paste(crescent, (0, 0), layer)
    # ring
    dr = ImageDraw.Draw(img)
    w = int(round(STROKE))
    dr.ellipse([C - R, C - R, C + R, C + R], outline=LINE, width=w)
    if not full_bleed:
        # squircle mask (rounded rect, rx = 30% like Android masks)
        mask = Image.new("L", (size, size), 0)
        m = ImageDraw.Draw(mask)
        rx = int(size * 0.30)
        m.rounded_rectangle([0, 0, size, size], radius=rx, fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        img = out
    return img.resize((BASE, BASE), Image.LANCZOS)


if __name__ == "__main__":
    # 输出到脚本所在目录（相对路径，避免硬编码本机绝对路径泄漏用户名/目录结构）
    out_dir = os.path.dirname(os.path.abspath(__file__))
    draw_icon(True).save(os.path.join(out_dir, "icon-512-play.png"))
    draw_icon(False).save(os.path.join(out_dir, "icon-512-squircle.png"))
    print("done")
