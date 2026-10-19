#!/usr/bin/env python3
"""Prototype .page stroke decoder for format research (Samsung Notes .sdocx).

Decoded so far (from the user's own notes):
  object: u16 len=36, ASCII uuid, i64 timestamp, f64 bbox[4], ..., marker u32 1200 u32 1552,
          u32 size1, u16 1, u32 size2, payload
  payload: 02 65 04 04 8e 24 00 00, u32 n, f64 x0, f64 y0, (n-1) x [i16 dx, i16 dy] sign-magnitude, /32
"""
import re, struct, sys, os, glob

MARK = struct.pack('<II', 1200, 1552)
PAYLOAD_MAGIC = bytes.fromhex('026504048e24')
TYPES = {}
UUID = re.compile(rb'\x24\x00([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})')


def sm(v):
    return -(v & 0x7FFF) if v & 0x8000 else v


def find_payload(b, start, limit=1200, types=None):
    """Find 'u32 size1, u16 type, u32 size2' after the 'u32 1200, u32 h' page marker.
    Type 1 is a stroke (size1 - size2 == 24, payload starts 02 ?? 04 04). Returns payload
    offset for strokes, -1 if none; records other types in `types`."""
    end = min(len(b) - 32, start + limit)
    i = b.find(b'\xb0\x04\x00\x00', start, end)
    if i < 0:
        return -1
    for j in range(i + 8, min(i + 8 + 64, end)):
        size1, typ, size2 = struct.unpack_from('<IHI', b, j)
        if 0 < size2 < size1 < 50_000_000 and size1 - size2 in (24, 28, 39, 40, 25) and typ < 64:
            if typ == 1 and size1 - size2 in (24, 28) and b[j + 10] == 0x02:
                return j + 10
            if types is not None:
                types[typ] = types.get(typ, 0) + 1
            return -1
    return -1


def objects(b):
    ms = list(UUID.finditer(b))
    for k, m in enumerate(ms):
        s = m.end()
        if s + 40 > len(b):
            continue
        bbox = struct.unpack_from('<dddd', b, s + 8)
        nxt = ms[k + 1].start() if k + 1 < len(ms) else len(b)
        p = find_payload(b, s, limit=min(1200, nxt - s), types=TYPES)
        if p < 0:
            yield m.group(1).decode(), bbox, None
            continue
        n = struct.unpack_from('<H', b, p + 8)[0]
        x0, y0 = struct.unpack_from('<dd', b, p + 10)
        xs, ys = [x0], [y0]
        q = p + 26
        for _ in range(n - 1):
            dx, dy = struct.unpack_from('<HH', b, q); q += 4
            xs.append(xs[-1] + sm(dx) / 32.0); ys.append(ys[-1] + sm(dy) / 32.0)
        yield m.group(1).decode(), bbox, (xs, ys, q)


if __name__ == "__main__":
    ok = bad = other = 0
    kinds = {}
    worst = []
    for page in glob.glob(os.path.join(sys.argv[1], '*/*.page')):
        b = open(page, 'rb').read()
        for oid, bbox, res in objects(b):
            if res is None or isinstance(res[0], str):
                other += 1
                if res is not None: kinds[res[1][:12]] = kinds.get(res[1][:12], 0) + 1
                continue
            xs, ys, _ = res
            tol = 1.0
            inside = min(xs) >= bbox[0] - tol and max(xs) <= bbox[2] + tol and min(ys) >= bbox[1] - tol and max(ys) <= bbox[3] + tol
            if inside:
                ok += 1
            else:
                bad += 1
                worst.append((os.path.basename(page), oid, [round(v, 1) for v in bbox], round(min(xs), 1), round(max(xs), 1), round(min(ys), 1), round(max(ys), 1)))
    print("strokes inside their bbox:", ok, "outside:", bad, "objects without stroke payload:", other)
    print("other object types (type: count):", TYPES)
    for w in worst[:5]:
        print("  mismatch", w)
