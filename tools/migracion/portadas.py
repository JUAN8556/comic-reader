"""Portadas: primera imagen en orden natural -> WebP de 300 px de ancho.

Archivos (ZIP, RAR, 7z): «bsdtar -xOqf» de la primera imagen en orden natural.
En un RAR4 sólido solo se puede sacar la primera entrada física, así que se
prueba esa primero. PDF: «sips» pinta la 1.ª página. Si nada funciona, no hay
portada y la app muestra una tarjeta vacía.

Todo se escribe primero en un temporal y se renombra al final (atómico).
"""

from __future__ import annotations

import os
import subprocess

import formatos
from nombres import clave_natural

CWEBP = ["cwebp", "-quiet", "-resize", "300", "0", "-q", "70"]


def escapar_patron(entrada: bytes) -> bytes:
    """bsdtar trata el nombre como patrón: se escapan \\ [ ] * ?."""
    out = bytearray()
    for c in entrada:
        if c in b"\\[]*?":
            out += b"\\"
        out.append(c)
    return bytes(out)


def candidatas(lista: list[bytes], solido_rar4: bool) -> list[bytes]:
    imagenes = [e for e in lista if formatos.es_imagen(formatos.nombre_entrada(e))]
    orden = sorted(imagenes, key=lambda e: clave_natural(formatos.nombre_entrada(e)))
    if solido_rar4 and imagenes:
        primera_fisica = imagenes[0]
        orden = [primera_fisica] + [e for e in orden if e != primera_fisica]
    return orden[:3]


def extraer(ruta: str, entrada: bytes, destino: str, timeout: int = 300) -> bool:
    try:
        r = subprocess.run(
            ["bsdtar", "-xOqf", ruta, escapar_patron(entrada)],
            capture_output=True, env=formatos.ENV_UTF8, timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return False
    if r.returncode != 0 or not r.stdout:
        return False
    with open(destino, "wb") as f:
        f.write(r.stdout)
    return True


def a_webp(imagen: str, salida: str, tmpdir: str) -> bool:
    tmp_out = salida + ".tmp"
    try:
        r = subprocess.run(CWEBP + [imagen, "-o", tmp_out], capture_output=True, timeout=180)
        if r.returncode != 0 or not os.path.exists(tmp_out) or os.path.getsize(tmp_out) == 0:
            # formatos que cwebp no lee (GIF, BMP, JPEG raros): pasar por sips a PNG
            png = os.path.join(tmpdir, "conv.png")
            r2 = subprocess.run(["sips", "-s", "format", "png", imagen, "--out", png],
                                capture_output=True, timeout=180)
            if r2.returncode != 0 or not os.path.exists(png):
                return False
            r = subprocess.run(CWEBP + [png, "-o", tmp_out], capture_output=True, timeout=180)
            if r.returncode != 0 or not os.path.exists(tmp_out):
                return False
        os.replace(tmp_out, salida)
        return True
    except subprocess.TimeoutExpired:
        return False
    finally:
        if os.path.exists(tmp_out):
            os.unlink(tmp_out)


def portada_pdf(ruta: str, salida: str, tmpdir: str) -> bool:
    png = os.path.join(tmpdir, "pdf.png")
    try:
        r = subprocess.run(["sips", "-s", "format", "png", ruta, "--out", png],
                           capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        return False
    if r.returncode != 0 or not os.path.exists(png) or os.path.getsize(png) == 0:
        return False
    return a_webp(png, salida, tmpdir)


def generar(ruta: str, formato: str, lista: list[bytes], solido: bool | None,
            salida: str, tmpdir: str) -> tuple[bool, str]:
    """Genera la portada en «salida». Devuelve (hecho, nota)."""
    os.makedirs(tmpdir, exist_ok=True)
    try:
        if formato == formatos.PDF:
            if portada_pdf(ruta, salida, tmpdir):
                return True, ""
            return False, "sips no pudo pintar la 1.ª página del PDF"
        if formato not in (formatos.ZIP, formatos.RAR4, formatos.RAR5, formatos.SEVEN_ZIP):
            return False, "formato sin portada"
        cands = candidatas(lista, bool(solido) and formato == formatos.RAR4)
        if not cands:
            return False, "no hay imágenes dentro"
        for entrada in cands:
            nombre = formatos.nombre_entrada(entrada)
            ext = os.path.splitext(nombre)[1].lower() or ".img"
            img = os.path.join(tmpdir, "portada" + ext)
            if extraer(ruta, entrada, img) and a_webp(img, salida, tmpdir):
                return True, ""
        return False, "no se pudo extraer ni convertir la primera imagen"
    finally:
        for n in os.listdir(tmpdir):
            p = os.path.join(tmpdir, n)
            if os.path.isfile(p) and not os.path.islink(p):
                os.unlink(p)
