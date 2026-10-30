"""Formatos: detección por cabecera e integridad con bsdtar.

Cabeceras:
  ZIP  50 4B 03 04 (o 50 4B 05 06 vacío)
  RAR4 52 61 72 21 1A 07 00     sólido si (bytes 10-11, little endian) & 0x0008;
                                cabeceras cifradas si & 0x0080; multivolumen si & 0x0001
  RAR5 52 61 72 21 1A 07 01 00  sólido si el campo «archive flags» & 0x0004; cabeceras
                                cifradas si el primer bloque es de cifrado (tipo 4)
  7z   37 7A BC AF 27 1C
  TAR  «ustar» en el byte 257 (.cbt)
  PDF  %PDF (en los primeros 1024 bytes)

Integridad: «bsdtar -tf» lista las entradas y el tamaño > 0. Se distingue «roto» de
«no pude comprobarlo»; solo lo roto se aparta:
  ok       bsdtar lista entradas sin error
  parcial  RAR sólido (o de solidez desconocida): libarchive falla a partir de la 2.ª
           entrada; si listó al menos una se acepta con «comprobación parcial»
  dudosa   no se pudo comprobar, pero no hay prueba de que esté roto: bsdtar no terminó a
           tiempo, RAR o 7z cifrado, parte de un RAR multivolumen, cabecera desconocida que
           bsdtar sí lee (autoextraíble…), PDF sin %%EOF. Se sube y va a la lista de revisión.
  rota     0 bytes; cabecera desconocida que bsdtar tampoco lee; archivo de formato
           conocido que bsdtar no puede listar (o truncado a media lista). No se sube.
"""

from __future__ import annotations

import os
import re
import subprocess
import unicodedata

ZIP, RAR4, RAR5, SEVEN_ZIP, TAR, PDF, DESCONOCIDO = "ZIP", "RAR4", "RAR5", "7Z", "TAR", "PDF", "DESCONOCIDO"
RAR = (RAR4, RAR5)

EXT_REAL = {ZIP: ".cbz", RAR4: ".cbr", RAR5: ".cbr", SEVEN_ZIP: ".cb7", TAR: ".cbt", PDF: ".pdf"}

# código que devuelve listar() cuando bsdtar no termina a tiempo (bsdtar nunca sale con 124)
RC_TIEMPO = 124

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


def _rar5_principal(h: bytes) -> tuple[int, int | None] | None:
    """(tipo del primer bloque, «archive flags» si es la cabecera principal) o None."""
    try:
        i = len(FIRMA_RAR5) + 4          # CRC32 de la cabecera
        _, i = _vint(h, i)               # tamaño de la cabecera
        tipo, i = _vint(h, i)
        if tipo != 1:                    # 1 = cabecera principal; 4 = cifrado del archivo
            return tipo, None
        flags, i = _vint(h, i)
        if flags & 0x0001:
            _, i = _vint(h, i)           # tamaño del área extra
        if flags & 0x0002:
            _, i = _vint(h, i)           # tamaño de datos
        archivo, i = _vint(h, i)
        return tipo, archivo
    except ValueError:
        return None


def _rar5_solido(h: bytes) -> bool | None:
    p = _rar5_principal(h)
    if not p or p[1] is None:
        return None
    return bool(p[1] & 0x0004)


def info_rar(h: bytes) -> dict:
    """{cifrado, volumen} de la cabecera de un RAR (False en cualquier otro fichero)."""
    out = {"cifrado": False, "volumen": False}
    if h.startswith(FIRMA_RAR5):
        p = _rar5_principal(h)
        if p:
            out["cifrado"] = p[0] == 4
            out["volumen"] = bool(p[1] is not None and p[1] & 0x0001)
    elif h.startswith(FIRMA_RAR4) and len(h) >= 12 and h[9] == 0x73:
        flags = int.from_bytes(h[10:12], "little")
        out["cifrado"] = bool(flags & 0x0080)
        out["volumen"] = bool(flags & 0x0001)
    return out


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
    if len(h) >= 262 and h[257:262] == b"ustar":
        return TAR, False
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
    """bsdtar -tf: (código, entradas en bytes tal como las da bsdtar, error).

    Si no termina a tiempo devuelve RC_TIEMPO con lo que llegó a listar."""
    try:
        r = subprocess.run(["bsdtar", "-tf", ruta], capture_output=True, env=ENV_UTF8, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        parcial = e.stdout or b""
        trozos = parcial.split(b"\n")
        if not parcial.endswith(b"\n"):
            trozos = trozos[:-1]                     # la última línea puede venir a medias
        entradas = [x for x in trozos if x]
        return RC_TIEMPO, entradas, f"bsdtar -tf no terminó en {timeout} s"
    entradas = [e for e in r.stdout.split(b"\n") if e]
    err = r.stderr.decode("utf-8", "replace").strip()
    return r.returncode, entradas, err


def _pdf_eof(ruta: str) -> bool:
    tam = os.path.getsize(ruta)
    with open(ruta, "rb") as f:
        f.seek(max(0, tam - 65536))
        return b"%%EOF" in f.read()


_CIFRADO = ("encrypt", "passphrase", "password")


def integridad(ruta: str, formato: str, solido: bool | None, tamano: int, timeout: int = 600) -> dict:
    """Comprueba el fichero ya materializado.

    Devuelve {estado, entradas, imagenes, nota, error, lista}:
      estado: 'ok' | 'parcial' | 'dudosa' | 'rota' (ver el docstring del módulo)
      lista: entradas de bsdtar en bytes (para elegir la portada)
    """
    out = {"estado": "ok", "entradas": 0, "imagenes": 0, "nota": "", "error": "", "lista": []}
    if tamano <= 0:
        out.update(estado="rota", error="0 bytes")
        return out
    if formato == PDF:
        if not _pdf_eof(ruta):
            out.update(estado="dudosa", nota="PDF sin %%EOF al final (¿truncado?)")
        return out
    rc, lista, err = listar(ruta, timeout)
    out["lista"] = lista
    out["entradas"] = len([e for e in lista if not e.endswith(b"/")])
    out["imagenes"] = len([e for e in lista if es_imagen(nombre_entrada(e))])
    if rc == RC_TIEMPO:
        out.update(estado="dudosa", nota=f"no se pudo comprobar: {err}")
        return out
    if formato == DESCONOCIDO:
        if out["entradas"] > 0:
            out.update(estado="dudosa", nota="cabecera desconocida, pero bsdtar lo lista (¿autoextraíble?)"
                       + ("" if rc == 0 else "; la lista se corta: comprobación parcial"))
        else:
            out.update(estado="rota", error="cabecera desconocida (no es ZIP, RAR, 7z, TAR ni PDF) "
                                            "y bsdtar no lo lee")
        return out
    if rc == 0 and out["entradas"] > 0:
        if out["imagenes"] == 0:
            out["nota"] = "sin imágenes dentro"
        return out
    rar = info_rar(leer_cabecera(ruta)) if formato in RAR else {"cifrado": False, "volumen": False}
    if rar["cifrado"] or any(x in err.lower() for x in _CIFRADO):
        out.update(estado="dudosa", nota="cifrado (con contraseña): no se puede comprobar")
        return out
    if formato in RAR and out["entradas"] >= 1 and solido is not False:
        out.update(estado="parcial", nota="sólido: comprobación parcial" if solido else
                   "RAR de solidez desconocida: comprobación parcial")
        return out
    if rar["volumen"]:
        out.update(estado="dudosa", nota="parte de un RAR multivolumen: no se puede comprobar suelta")
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
