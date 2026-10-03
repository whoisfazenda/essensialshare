"""Generates the launcher vector (Android) and icon.ico / icon.png (Windows) from one design:
a faint dot matrix and a comet of growing white dots flying up-right into a red dot ("send")."""
import struct
import zlib
from pathlib import Path

root = Path(__file__).parent

N = 7            # 7 x 7 matrix
PITCH = 9.0      # in a 108-unit canvas
CX = CY = 54.0


def cell(c, r):
    return CX + (c - 3) * PITCH, CY + (r - 3) * PITCH


# diagonal from bottom-left (0, 6) to top-right (6, 0)
comet = [(0, 6, 2.2), (1, 5, 2.9), (2, 4, 3.6), (3, 3, 4.3), (4, 2, 5.0), (5, 1, 5.8)]
head = (6, 0, 7.0)
diagonal = {(c, r) for c, r, _ in comet} | {(head[0], head[1])}
faint = [(c, r) for r in range(N) for c in range(N) if (c, r) not in diagonal]


def circ(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 -{2 * r},0z"


# ---- Android vector ----
faint_path = "".join(circ(*cell(c, r), 1.9) for c, r in faint)
white_path = "".join(circ(*cell(c, r), rad) for c, r, rad in comet)
red_path = circ(*cell(head[0], head[1]), head[2])
xml = (
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" '
    'android:viewportWidth="108" android:viewportHeight="108">\n'
    f'<path android:fillColor="#FFFFFF" android:fillAlpha="0.3" android:pathData="{faint_path}"/>\n'
    f'<path android:fillColor="#FFFFFF" android:pathData="{white_path}"/>\n'
    f'<path android:fillColor="#D71921" android:pathData="{red_path}"/>\n</vector>\n'
)
(root / "android/src/main/res/drawable").mkdir(parents=True, exist_ok=True)
(root / "android/src/main/res/drawable/ic_launcher_fg.xml").write_text(xml)


# ---- icon.ico / icon.png (supersampled) ----
def render(size):
    ss = 4
    n = size * ss
    px = [[0] * n for _ in range(n)]  # 0 clear, 1 white, 2 red, 3 black tile, 4 faint

    def disc(cx, cy, r, v):
        x0, x1 = int(max(0, cx - r - 1)), int(min(n - 1, cx + r + 1))
        y0, y1 = int(max(0, cy - r - 1)), int(min(n - 1, cy + r + 1))
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if (x + .5 - cx) ** 2 + (y + .5 - cy) ** 2 <= r * r:
                    px[y][x] = v

    s = n / 108
    rad = 22 * s
    for y in range(n):
        for x in range(n):
            dx = max(rad - x, 0, x - (n - rad)); dy = max(rad - y, 0, y - (n - rad))
            if dx * dx + dy * dy <= rad * rad:
                px[y][x] = 3
    grow = 1.18  # the tile shows the artwork a little larger than the adaptive icon does
    def pos(c, r):
        x, y = cell(c, r)
        return (CX + (x - CX) * grow) * s, (CY + (y - CY) * grow) * s
    for c, r in faint:
        x, y = pos(c, r); disc(x, y, 1.9 * grow * s, 4)
    for c, r, radius in comet:
        x, y = pos(c, r); disc(x, y, radius * grow * s, 1)
    x, y = pos(head[0], head[1]); disc(x, y, head[2] * grow * s, 2)
    pal = {0: (0, 0, 0, 0), 1: (255, 255, 255, 255), 2: (215, 25, 33, 255), 3: (0, 0, 0, 255), 4: (80, 80, 80, 255)}
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            acc = [0, 0, 0, 0]
            for j in range(ss):
                for i in range(ss):
                    c = pal[px[y * ss + j][x * ss + i]]
                    a = c[3]
                    acc[0] += c[0] * a; acc[1] += c[1] * a; acc[2] += c[2] * a; acc[3] += a
            a = acc[3]
            row += bytes([acc[0] // a, acc[1] // a, acc[2] // a, a // (ss * ss)]) if a else bytes(4)
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


sizes = [16, 32, 48, 64, 128, 256]
imgs = [render(sz) for sz in sizes]
ico = struct.pack("<HHH", 0, 1, len(imgs))
off = 6 + 16 * len(imgs)
for sz, data in zip(sizes, imgs):
    ico += struct.pack("<BBBBHHII", sz % 256, sz % 256, 0, 0, 1, 32, len(data), off)
    off += len(data)
ico += b"".join(imgs)
(root / "desktop/icon.ico").write_bytes(ico)
(root / "desktop/src/main/resources/icon.png").write_bytes(imgs[-1])
print("icons written")
