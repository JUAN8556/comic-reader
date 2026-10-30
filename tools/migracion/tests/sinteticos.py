"""Ficheros sintéticos para las pruebas: PNG, CBZ, 7z, PDF y cabeceras RAR."""

import os
import struct
import subprocess
import zlib
import zipfile


def leer(ruta: str) -> bytes:
    with open(ruta, "rb") as f:
        return f.read()


def png(ancho: int = 12, alto: int = 18, color=(200, 30, 30)) -> bytes:
    def trozo(tipo: bytes, datos: bytes) -> bytes:
        return struct.pack(">I", len(datos)) + tipo + datos + struct.pack(">I", zlib.crc32(tipo + datos) & 0xFFFFFFFF)
    filas = b"".join(b"\x00" + bytes(color) * ancho for _ in range(alto))
    return (b"\x89PNG\r\n\x1a\n" + trozo(b"IHDR", struct.pack(">IIBBBBB", ancho, alto, 8, 2, 0, 0, 0))
            + trozo(b"IDAT", zlib.compress(filas)) + trozo(b"IEND", b""))


def cbz(ruta: str, paginas=("10.png", "2.png", "1.png"), extra=None):
    os.makedirs(os.path.dirname(ruta), exist_ok=True)
    with zipfile.ZipFile(ruta, "w") as z:
        for i, p in enumerate(paginas):
            z.writestr(p, png(color=(10 * i, 100, 200)))
        for nombre, datos in (extra or {}).items():
            z.writestr(nombre, datos)


def siete_z(ruta: str, tmp: str):
    """7z de verdad escrito por bsdtar (libarchive sabe escribir 7z)."""
    os.makedirs(tmp, exist_ok=True)
    os.makedirs(os.path.dirname(ruta), exist_ok=True)
    for n in ("01.png", "02.png"):
        with open(os.path.join(tmp, n), "wb") as f:
            f.write(png())
    subprocess.run(["bsdtar", "--format", "7zip", "-cf", ruta, "-C", tmp, "01.png", "02.png"], check=True)


def pdf(ruta: str):
    """PDF mínimo válido de una página (con tabla xref correcta)."""
    os.makedirs(os.path.dirname(ruta), exist_ok=True)
    contenido = b"0.8 0.1 0.1 rg 20 20 160 240 re f"
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 280] /Contents 4 0 R >>",
        b"<< /Length " + str(len(contenido)).encode() + b" >>\nstream\n" + contenido + b"\nendstream",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += f"{i} 0 obj\n".encode() + o + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    for o in offs:
        out += f"{o:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    with open(ruta, "wb") as f:
        f.write(out)


def cabecera_rar4(solido: bool) -> bytes:
    flags = 0x0008 if solido else 0x0000
    return b"Rar!\x1a\x07\x00" + b"\xcf\x90" + b"\x73" + struct.pack("<H", flags) + b"\x0d\x00" + b"\x00" * 20


def _vint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def cabecera_rar5(solido: bool) -> bytes:
    cuerpo = _vint(1) + _vint(0) + _vint(0x0004 if solido else 0)   # tipo, flags, archive flags
    return b"Rar!\x1a\x07\x01\x00" + b"\x00\x00\x00\x00" + _vint(len(cuerpo)) + cuerpo + b"\x00" * 8
