#!/usr/bin/env python3
"""Writes app/assets/setup/hello.txt: the word "hello" in the Hershey Script font as one SVG path (M, C), the
handwriting Set Up writes on its first screen. The letters' strokes are joined where one ends near where the next
begins, so the pen draws the word in as few strokes as possible, and the font's straight segments are smoothed into
curves (Catmull-Rom), keeping the sharp turns. Normalized to a 1000 x 400 box (y down).

The Hershey fonts were originally created by Dr. A. V. Hershey while working at the U. S. National Bureau of
Standards. The format of the font data used here was originally created by James Hurt, Cognition, Inc.
The data file is read into a temporary directory and deleted afterwards; only the generated path is kept.

    python3 tools/make_hello.py            # writes app/assets/setup/hello.txt
"""
import os
import sys
import tempfile
import urllib.request

URL = "https://raw.githubusercontent.com/kamalmostafa/hershey-fonts/master/hershey-fonts/scripts.jhf"
WORD = "hello"
BOX_W, BOX_H, MARGIN = 1000.0, 400.0, 20.0
JOIN = 3.5          # font units: a stroke ending this close to the next one's start continues it
CORNER = 110        # degrees: a sharper turn than this stays a corner (the cusp at the foot of the h)


def glyphs(text):
    """The .jhf records in order (ASCII 32 first): (left, right, strokes), each stroke a list of (x, y)."""
    out = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        i += 1
        if not line.strip():
            continue
        count = int(line[5:8])
        data = line[8:]
        while len(data) < count * 2 and i < len(lines):   # long glyphs wrap onto the next lines
            data += lines[i]
            i += 1
        left, right = ord(data[0]) - ord("R"), ord(data[1]) - ord("R")
        strokes, cur = [], []
        for j in range(2, count * 2, 2):
            pair = data[j:j + 2]
            if pair == " R":
                if cur:
                    strokes.append(cur)
                cur = []
            else:
                cur.append((ord(pair[0]) - ord("R"), ord(pair[1]) - ord("R")))
        if cur:
            strokes.append(cur)
        out.append((left, right, strokes))
    return out


def word_strokes(font, word):
    strokes, x = [], 0
    for ch in word:
        left, right, parts = font[ord(ch) - 32]
        for s in parts:
            strokes.append([(x + px - left, py) for px, py in s])
        x += right - left
    # One continuous line where a letter's exit stroke meets the next letter's entry.
    joined = [strokes[0]]
    for s in strokes[1:]:
        (ax, ay), (bx, by) = joined[-1][-1], s[0]
        if abs(ax - bx) <= JOIN and abs(ay - by) <= JOIN:
            joined[-1] = joined[-1] + (s[1:] if (ax, ay) == (bx, by) else s)   # else a short pen move, then on
        else:
            joined.append(s)
    return joined


def turn(a, b, c):
    """The angle in degrees between the directions a→b and b→c."""
    import math
    u, v = (b[0] - a[0], b[1] - a[1]), (c[0] - b[0], c[1] - b[1])
    lu, lv = math.hypot(*u), math.hypot(*v)
    if lu == 0 or lv == 0:
        return 0
    cos = max(-1.0, min(1.0, (u[0] * v[0] + u[1] * v[1]) / (lu * lv)))
    return math.degrees(math.acos(cos))


def smooth(s):
    """Cubic Béziers through the points: (start, [(c1, c2, end), ...]). Tangents are zeroed at the ends and corners."""
    n = len(s)
    corner = [i == 0 or i == n - 1 or turn(s[i - 1], s[i], s[i + 1]) > CORNER for i in range(n)]

    def tangent(i):
        if corner[i]:
            return (0.0, 0.0)
        return ((s[i + 1][0] - s[i - 1][0]) / 6.0, (s[i + 1][1] - s[i - 1][1]) / 6.0)

    curves = []
    for i in range(n - 1):
        t0, t1 = tangent(i), tangent(i + 1)
        c1 = (s[i][0] + t0[0], s[i][1] + t0[1])
        c2 = (s[i + 1][0] - t1[0], s[i + 1][1] - t1[1])
        curves.append((c1, c2, s[i + 1]))
    return s[0], curves


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "scripts.jhf")
        urllib.request.urlretrieve(URL, path)
        with open(path, encoding="latin-1") as f:
            font = glyphs(f.read())
    strokes = word_strokes(font, WORD)
    xs = [p[0] for s in strokes for p in s]
    ys = [p[1] for s in strokes for p in s]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    scale = min((BOX_W - 2 * MARGIN) / w, (BOX_H - 2 * MARGIN) / h)
    ox = (BOX_W - w * scale) / 2 - min(xs) * scale
    oy = (BOX_H - h * scale) / 2 - min(ys) * scale
    def pt(p):
        return "%.1f %.1f" % (ox + p[0] * scale, oy + p[1] * scale)

    parts = []
    for s in strokes:
        start, curves = smooth(s)
        parts.append("M" + pt(start) + "".join(" C" + pt(a) + " " + pt(b) + " " + pt(c) for a, b, c in curves))
    out = os.path.join(root, "app", "assets", "setup", "hello.txt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write(" ".join(parts) + "\n")
    print("%s: %d strokes, %d curves" % (os.path.relpath(out, root), len(strokes), sum(len(s) - 1 for s in strokes)),
          file=sys.stderr)


if __name__ == "__main__":
    main()
