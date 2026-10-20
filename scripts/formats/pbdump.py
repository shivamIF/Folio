#!/usr/bin/env python3
"""Schema-less protobuf inspector (like `protoc --decode_raw`) for format research.

pbdump.py FILE [--stream] [--max N] [--depth D]
  --stream  file is a sequence of varint-length-prefixed messages
"""
import struct, sys


def varint(b, i):
    shift = v = 0
    while True:
        c = b[i]; i += 1
        v |= (c & 0x7F) << shift
        if c < 0x80:
            return v, i
        shift += 7


def parse(b):
    """Returns list of (field, wiretype, value) or raises on malformed input."""
    out, i = [], 0
    while i < len(b):
        key, i = varint(b, i)
        f, wt = key >> 3, key & 7
        if f == 0:
            raise ValueError("field 0")
        if wt == 0:
            v, i = varint(b, i)
        elif wt == 1:
            v = b[i:i + 8]; i += 8
        elif wt == 2:
            n, i = varint(b, i); v = b[i:i + n]; i += n
            if i > len(b):
                raise ValueError("overrun")
        elif wt == 5:
            v = b[i:i + 4]; i += 4
        else:
            raise ValueError(f"wiretype {wt}")
        out.append((f, wt, v))
    return out


def show(b, indent=0, depth=8, maxlen=80):
    pad = "  " * indent
    for f, wt, v in parse(b):
        if wt == 0:
            print(f"{pad}{f}: {v}")
        elif wt == 5:
            print(f"{pad}{f}: f32 {struct.unpack('<f', v)[0]:.4g} (i {struct.unpack('<i', v)[0]})")
        elif wt == 1:
            print(f"{pad}{f}: f64 {struct.unpack('<d', v)[0]:.6g}")
        else:
            nested = None
            if depth > 0 and v:
                try:
                    nested = parse(v)
                except Exception:
                    nested = None
            text = None
            try:
                t = v.decode("utf-8")
                if t.isprintable():
                    text = t
            except Exception:
                pass
            if text is not None and (nested is None or len(t) < 64):
                print(f"{pad}{f}: \"{text[:maxlen]}\"")
            elif nested is not None:
                print(f"{pad}{f} {{  # {len(v)} bytes")
                show(v, indent + 1, depth - 1, maxlen)
                print(f"{pad}}}")
            else:
                print(f"{pad}{f}: bytes[{len(v)}] {v[:24].hex()}{'…' if len(v) > 24 else ''}")


def stream(b):
    i = 0
    while i < len(b):
        n, i = varint(b, i)
        yield b[i:i + n]
        i += n


if __name__ == "__main__":
    data = open(sys.argv[1], "rb").read()
    mx = int(sys.argv[sys.argv.index("--max") + 1]) if "--max" in sys.argv else 10**9
    depth = int(sys.argv[sys.argv.index("--depth") + 1]) if "--depth" in sys.argv else 8
    if "--stream" in sys.argv:
        for k, msg in enumerate(stream(data)):
            if k >= mx:
                break
            print(f"=== record {k} ({len(msg)} bytes)")
            show(msg, 1, depth)
    else:
        show(data, 0, depth)
