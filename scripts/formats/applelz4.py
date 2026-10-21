"""Apple Compression framework LZ4 container ("bv41"/"bv4-"/"bv4$" blocks) decoder, for format research."""
import struct


def lz4_block(src: bytes, out_len: int) -> bytes:
    out = bytearray()
    i = 0
    while i < len(src):
        token = src[i]; i += 1
        lit = token >> 4
        if lit == 15:
            while True:
                b = src[i]; i += 1; lit += b
                if b != 255: break
        out += src[i:i + lit]; i += lit
        if i >= len(src) or len(out) >= out_len:
            break
        off = src[i] | (src[i + 1] << 8); i += 2
        ml = token & 15
        if ml == 15:
            while True:
                b = src[i]; i += 1; ml += b
                if b != 255: break
        ml += 4
        start = len(out) - off
        for k in range(ml):
            out.append(out[start + k])
    return bytes(out)


def decode(buf: bytes) -> bytes:
    out = bytearray(); i = 0
    while i + 4 <= len(buf):
        magic = buf[i:i + 4]
        if magic == b"bv4$":
            break
        if magic == b"bv41":
            dlen, clen = struct.unpack_from("<II", buf, i + 4)
            out += lz4_block(buf[i + 12:i + 12 + clen], dlen); i += 12 + clen
        elif magic == b"bv4-":
            (n,) = struct.unpack_from("<I", buf, i + 4)
            out += buf[i + 8:i + 8 + n]; i += 8 + n
        else:
            raise ValueError(f"unknown block {magic!r} at {i}")
    return bytes(out)
