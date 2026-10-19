"""Minimal reader for 'tpl' images (troydhanson/tpl serialisation), for format research."""
import struct

SIZES = {"v": ("<H", 2), "u": ("<I", 4), "i": ("<i", 4), "j": ("<h", 2), "c": ("<B", 1), "f": ("<d", 8), "U": ("<Q", 8), "I": ("<q", 8)}


def split(fmt):
    """Top-level tokens: single chars, S(...) and A(...) groups."""
    out, i = [], 0
    while i < len(fmt):
        c = fmt[i]
        if c in "SA" and i + 1 < len(fmt) and fmt[i + 1] == "(":
            depth, j = 0, i + 1
            while True:
                if fmt[j] == "(":
                    depth += 1
                elif fmt[j] == ")":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            out.append(fmt[i:j + 1]); i = j + 1
        else:
            out.append(c); i += 1
    return out


def _read(tok, b, i):
    if tok.startswith("S("):
        vals = []
        for t in split(tok[2:-1]):
            v, i = _read(t, b, i); vals.append(v)
        return tuple(vals), i
    fmt, n = SIZES[tok]
    return struct.unpack_from(fmt, b, i)[0], i + n


def read(b: bytes, scalars_first=True):
    assert b[:3] == b"tpl", "not a tpl image"
    total = struct.unpack_from("<I", b, 4)[0]
    end = b.index(b"\0", 8)
    fmt = b[8:end].decode()
    i = end + 1
    toks = split(fmt)
    order = ([t for t in toks if not t.startswith("A(")] + [t for t in toks if t.startswith("A(")]) if scalars_first else toks
    out = []
    for t in order:
        if t.startswith("A("):
            n = struct.unpack_from("<I", b, i)[0]; i += 4
            inner = t[2:-1]
            single = len(split(inner)) == 1
            items = []
            for _ in range(n):
                v, i = _read(inner if single else "S(" + inner + ")", b, i)
                items.append(v)
            out.append((t, items))
        else:
            v, i = _read(t, b, i)
            out.append((t, v))
    return {"format": fmt, "declared_len": total, "consumed": i, "fields": out}
