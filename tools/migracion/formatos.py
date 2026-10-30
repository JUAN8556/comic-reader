"""Formatos: detección por cabecera e integridad con bsdtar.

Cabeceras:
  ZIP  50 4B 03 04 (o 50 4B 05 06 vacío)
  RAR4 52 61 72 21 1A 07 00     sólido si (bytes 10-11, little endian) & 0x0008
  RAR5 52 61 72 21 1A 07 01 00  sólido si el campo «archive flags» & 0x0004
  7z   37 7A BC AF 27 1C
  PDF  %PDF (en los primeros 1024 bytes)

Integridad: «bsdtar -tf» lista las entradas (fallo = roto) y el tamaño > 0.
Para un RAR4 SÓLIDO, libarchive falla a partir de la 2.ª entrada: si llegó a listar
al menos una, se acepta con «sólido: comprobación parcial» y no se aparta.
"""

from __future__ import annotations

import os
import re
import subprocess
import unicodedata

ZIP, RAR4, RAR5, SEVEN_ZIP, PDF, DESCONOCIDO = "ZIP", "RAR4", "RAR5", "7Z", "PDF", "DESCONOCIDO"

EXT_REAL = {ZIP: ".cbz", RAR4: ".cbr", RAR5: ".cbr", SEVEN_ZIP: ".cb7", PDF: ".pdf"}

FIRMA_RAR4 = b"Rar!\x1a\x07\x00"
FIRMA_RAR5 = b"Rar!\x1a\x07\x01\x00"
FIRMA_7Z = b"7z\xbc\xaf\x27\x1c"

BYTES_CABECERA = 1024

IMAGENES = (".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tif", ".tiff", ".jxl", ".avif")

ENV_UTF8 = dict(os.environ, LC_ALL="en_US.UTF-8", LANG="en_US.UTF-8")


def _vint(b: bytes, i: int) -> tuple[int, int]:
    """Entero de longitud variable de RAR5: (valor, siguiente índice)."""
    valor, desplaz = 0, 0
    while i < len(b):
        x = b[i]
        valor |= (x & 0x7F) << desplaz
        i += 1
        if not x & 0x80:
            return valor, i
        desplaz += 7
        if desplaz > 63:
            break
    raise ValueError("vint truncado")


def _rar5_solido(h: bytes) -> bool | None:
    try:
        i = len(FIRMA_RAR5) + 4          # CRC32 de la cabecera
        _, i = _vint(h, i)               # tamaño de la cabecera
        tipo, i = _vint(h, i)
        if tipo != 1:                    # 1 = cabecera principal
            return None
        flags, i = _vint(h, i)
        if flags & 0x0001:
            _, i = _vint(h, i)           # tamaño del área extra
        if flags & 0x0002:
            _, i = _vint(h, i)           # tamaño de datos
        archivo, i = _vint(h, i)
        return bool(archivo & 0x0004)
    except ValueError:
        return None


def detectar(cabecera: bytes) -> tuple[str, bool | None]:
    """(formato, sólido) a partir de los primeros bytes del fichero."""
    h = cabecera
    if h.startswith(b"PK\x03\x04") or h.startswith(b"PK\x05\x06") or h.startswith(b"PK\x07\x08"):
        return ZIP, False
    if h.startswith(FIRMA_RAR5):
        return RAR5, _rar5_solido(h)
    if h.startswith(FIRMA_RAR4):
        if len(h) >= 12 and h[9] == 0x73:   # cabecera MAIN_HEAD
            flags = int.from_bytes(h[10:12], "little")
            return RAR4, bool(flags & 0x0008)
        return RAR4, None
    if h.startswith(FIRMA_7Z):
        return SEVEN_ZIP, False
    if h[:BYTES_CABECERA].find(b"%PDF") >= 0:
        return PDF, False
    return DESCONOCIDO, None


def leer_cabecera(ruta: str) -> bytes:
    with open(ruta, "rb") as f:
        return f.read(BYTES_CABECERA)


def es_imagen(nombre: str) -> bool:
    n = nombre.lower()
    if n.endswith("/"):
        return False
    partes = n.split("/")
    if any(p.startswith(".") or p == "__macosx" for p in partes):
        return False
    return n.endswith(IMAGENES)


def nombre_entrada(b: bytes) -> str:
    return unicodedata.normalize("NFC", b.decode("utf-8", "surrogateescape"))


def listar(ruta: str, timeout: int = 600) -> tuple[int, list[bytes], str]:
    """bsdtar -tf: (código, entradas en bytes tal como las da bsdtar, error)."""
    try:
        r = subprocess.run(["bsdtar", "-tf", ruta], capture_output=True, env=ENV_UTF8, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, [], f"bsdtar -tf no terminó en {timeout} s"
    entradas = [e for e in r.stdout.split(b"\n") if e]
    err = r.stderr.decode("utf-8", "replace").strip()
    return r.returncode, entradas, err


def _pdf_eof(ruta: str) -> bool:
    tam = os.path.getsize(ruta)
    with open(ruta, "rb") as f:
        f.seek(max(0, tam - 65536))
        return b"%%EOF" in f.read()


def integridad(ruta: str, formato: str, solido: bool | None, tamano: int) -> dict:
    """Comprueba el fichero ya materializado.

    Devuelve {estado, entradas, imagenes, nota, error, lista}:
      estado: 'ok' | 'parcial' (RAR4 sólido) | 'dudosa' (PDF sin %%EOF) | 'rota'
      lista: entradas de bsdtar en bytes (para elegir la portada)
    """
    out = {"estado": "ok", "entradas": 0, "imagenes": 0, "nota": "", "error": "", "lista": []}
    if tamano <= 0:
        out.update(estado="rota", error="0 bytes")
        return out
    if formato == DESCONOCIDO:
        out.update(estado="rota", error="cabecera desconocida (no es ZIP, RAR, 7z ni PDF)")
        return out
    if formato == PDF:
        if not _pdf_eof(ruta):
            out.update(estado="dudosa", nota="PDF sin %%EOF al final (¿truncado?)")
        return out
    rc, lista, err = listar(ruta)
    out["lista"] = lista
    out["entradas"] = len([e for e in lista if not e.endswith(b"/")])
    out["imagenes"] = len([e for e in lista if es_imagen(nombre_entrada(e))])
    if rc == 0 and out["entradas"] > 0:
        if out["imagenes"] == 0:
            out["nota"] = "sin imágenes dentro"
        return out
    if formato == RAR4 and solido and out["entradas"] >= 1:
        out.update(estado="parcial", nota="sólido: comprobación parcial")
        return out
    if rc == 0:
        out.update(estado="rota", error="archivo sin entradas")
    else:
        out.update(estado="rota", error=_resumir(err) or f"bsdtar salió con {rc}")
    return out


def _resumir(err: str) -> str:
    lineas = [l for l in err.splitlines() if l.strip()]
    lineas = [re.sub(r"^bsdtar: ", "", l) for l in lineas if "Error exit delayed" not in l]
    return "; ".join(lineas[:2])[:300]
