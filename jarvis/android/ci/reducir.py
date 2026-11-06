#!/usr/bin/env python3
"""Reduce una captura cruda de Android (adb exec-out screencap, RGBA) a un PNG pequeño, en Python puro (sin PIL)."""
import struct, sys, zlib
raw, out, width = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = open(raw, "rb").read()
w, h = struct.unpack("<II", d[:8])
hdr = len(d) - w * h * 4  # 12 o 16 bytes según la versión de Android
px = d[hdr:]
f = max(1, w // width)
nw, nh = w // f, h // f
rows = []
for y in range(nh):
    row = bytearray([0])
    base = (y * f) * w * 4
    for x in range(nw):
        i = base + x * f * 4
        row += px[i:i + 3]
    rows.append(bytes(row))
def chunk(t, b): return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", nw, nh, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
open(out, "wb").write(png)
