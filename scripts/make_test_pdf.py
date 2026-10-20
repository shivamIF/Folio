#!/usr/bin/env python3
"""Writes a small synthetic multi-page PDF for import/export tests (no dependencies).

Each page: US Letter, a thick black bar near the top and the text "Folio test page N".
Usage: scripts/make_test_pdf.py OUT.pdf [PAGES]
"""
import sys

def build(pages: int) -> bytes:
    objs = []  # body strings, object numbers start at 1
    def add(body):
        objs.append(body)
        return len(objs)

    font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    pages_obj = add(None)  # placeholder, filled below
    kids = []
    for n in range(1, pages + 1):
        stream = f"0 0 0 rg 72 650 468 40 re f BT /F1 36 Tf 72 560 Td (Folio test page {n}) Tj ET"
        content = add(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream")
        page = add(f"<< /Type /Page /Parent {pages_obj} 0 R /MediaBox [0 0 612 792] "
                   f"/Resources << /Font << /F1 {font} 0 R >> >> /Contents {content} 0 R >>")
        kids.append(page)
    objs[pages_obj - 1] = f"<< /Type /Pages /Kids [{' '.join(f'{k} 0 R' for k in kids)}] /Count {pages} >>"
    catalog = add(f"<< /Type /Catalog /Pages {pages_obj} 0 R >>")

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{body}\nendobj\n".encode("latin-1")
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root {catalog} 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)

if __name__ == "__main__":
    path = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    with open(path, "wb") as f:
        f.write(build(n))
    print(f"wrote {path} ({n} pages)")
