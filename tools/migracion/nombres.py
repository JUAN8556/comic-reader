"""Nombres: NFC, «deleet», limpieza, serie canónica y patrón de nombre.

Todo es puro (sin E/S) para poder probarlo con unittest.

Reglas (conservadoras: si no hay seguridad, se conserva el nombre limpio):
- NFC, sin espacios al principio ni al final de cada componente, sin espacios dobles.
- «Deleet» de palabras disfrazadas con dígitos (C0M1CS -> COMICS, R0b0t -> Robot,
  4zul -> Azul, Noch3 -> Noche), sin tocar números de verdad (Vol1, nº06,
  1de4, 2099, 17mo...).
- Patrón: «<Serie> NNN (Año).ext»; tomos «<Serie> 012.ext»; números únicos
  «<Título> (Año).ext». El número sale de «#12», «nº12», «Tomo 12», «Capítulo 12»,
  «1 de 4», «1de4» o de un número final; el año de «(1999)» o de «012 1999».
  Solo se aplica si lo que queda del nombre coincide con la serie (o no queda nada).
"""

from __future__ import annotations

import re
import unicodedata

# --------------------------------------------------------------------------
# Unicode y limpieza básica
# --------------------------------------------------------------------------

EXT_COMIC = {".cbr", ".cbz", ".cb7", ".cbt", ".pdf", ".rar", ".zip", ".7z"}
EXT_IMAGEN = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tif", ".tiff"}
IGNORADOS = {".ds_store", "thumbs.db", "desktop.ini", ".localized", "icon\r"}


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def sin_acentos(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


_ESPACIOS = re.compile(r"\s+")


def espacios(s: str) -> str:
    """Colapsa cualquier blanco (incluido NBSP) a un espacio y recorta."""
    return _ESPACIOS.sub(" ", s.replace(" ", " ")).strip()


def componente_limpio(s: str) -> str:
    """Componente de ruta: NFC, sin espacios al borde ni dobles (sin deleet)."""
    return espacios(nfc(s))


def ruta_normalizada(rel: str) -> str:
    """Ruta relativa con cada componente en NFC y sin espacios al borde."""
    if rel in ("", "."):
        return "."
    return "/".join(componente_limpio(c) for c in rel.split("/"))


# --------------------------------------------------------------------------
# Deleet
# --------------------------------------------------------------------------

_LEET = {"0": "o", "1": "i", "3": "e", "4": "a"}
_TOKEN = re.compile(r"[^\W_]+")
# tokens que llevan números de verdad: no se tocan
_PROTEGIDOS = [
    re.compile(r"^\d+de\d+$", re.I),            # 1de4, 01de05
    re.compile(r"^n[ºo°]\d", re.I),               # nº06, Nº1de3
    re.compile(r"^\d+(st|nd|rd|th|er|ra|da|do|mo|vo|to|no|ª|º|[a-z])$", re.I),  # 2da, 17mo, 600a
]
# palabras que introducen un número: «Vol3», «estado4», «Tomo3» no son leet
_INTRODUCE_NUMERO = re.compile(
    r"(vol|volumen|tomo|cap|capitulo|capítulo|parte|part|ep|libro|estado|arco|fase|"
    r"impacto|mundo|book|issue|saga|serie|season|nº|n°)",
    re.I,
)


def _es_letra(c: str) -> bool:
    return c.isalpha()


def _deleet_token(tok: str) -> str:
    if not any(c.isdigit() for c in tok) or not any(c.isalpha() for c in tok):
        return tok
    for p in _PROTEGIDOS:
        if p.search(tok):
            return tok
    chars = list(tok)
    n = len(chars)
    # interior: dígito leet entre dos letras (C0M1CS, R0b0t, PL4N3T4)
    for i in range(1, n - 1):
        c = tok[i]
        if c in _LEET and _es_letra(tok[i - 1]) and _es_letra(tok[i + 1]):
            mayus = tok[i - 1].isupper() and tok[i + 1].isupper()
            chars[i] = _LEET[c].upper() if mayus else _LEET[c]
    # inicio: un solo «4» seguido de letras (4zul, 4ntártica)
    if n >= 4 and tok[0] == "4" and all(_es_letra(c) for c in chars[1:]):
        chars[0] = "A"
    # final: un «3» o «4» tras 3 letras o más que no introducen un número (Noch3, Esperanz4)
    if n >= 4 and tok[-1] in "34" and _es_letra(tok[-2]):
        previo = "".join(chars[:-1])
        m = re.search(r"([^\W\d_]+)$", previo)
        letras = m.group(1) if m else ""
        if len(letras) >= 3 and letras == previo and not _INTRODUCE_NUMERO.fullmatch(letras):
            chars[-1] = _LEET[tok[-1]].upper() if previo.isupper() else _LEET[tok[-1]]
    return "".join(chars)


def deleet(s: str) -> str:
    return _TOKEN.sub(lambda m: _deleet_token(m.group(0)), s)


def limpiar(s: str) -> str:
    """Nombre limpio: NFC, guiones bajos como espacios, sin leet, sin espacios dobles."""
    s = nfc(s)
    s = s.replace(" _ ", " - ")
    s = s.replace("_", " ")
    s = deleet(s)
    return espacios(s)


# --------------------------------------------------------------------------
# Claves de comparación
# --------------------------------------------------------------------------

_PARENTESIS = re.compile(r"\([^()]*\)|\[[^\[\]]*\]")
_VOL = re.compile(r"\b(?:vol(?:umen|ume)?|v)\s*\.?\s*(\d+)\b", re.I)


def clave(s: str) -> str:
    """Clave insensible a mayúsculas, acentos, leet y puntuación ('&', 'and' = 'y')."""
    s = sin_acentos(deleet(nfc(s))).casefold()
    s = s.replace("&", " y ")
    s = re.sub(r"[^0-9a-z]+", " ", s)
    s = re.sub(r"\b(and|e)\b", "y", s)
    return " ".join(s.split())


def _sin_parentesis(s: str) -> str:
    prev = None
    while prev != s:
        prev, s = s, _PARENTESIS.sub(" ", s)
    return espacios(s)


_ARTICULO = re.compile(r"^(el|la|los|las|the|le|les|lo)\s+", re.I)
_ANIO_FINAL = re.compile(r"\s*\b(19[3-9]\d|20[0-3]\d)$")


def variantes(s: str) -> set[str]:
    """Claves equivalentes de un título: con y sin volumen, año final o artículo."""
    out = set()
    base = _sin_parentesis(s)
    for v in (s, base):
        k = clave(v)
        sin_vol = clave(_VOL.sub(" ", v))
        for x in (k, sin_vol):
            out.add(x)
            out.add(_ANIO_FINAL.sub("", x).strip())
    for x in list(out):
        out.add(_ARTICULO.sub("", x))
    out.discard("")
    return out


def claves_serie(serie: str) -> set[str]:
    """Formas aceptables de la serie para casar con el título de un fichero."""
    out = variantes(serie)
    base = _sin_parentesis(serie)
    trozos = [t for t in re.split(r"\s+[-–—]\s+", base) if t.strip()]
    if len(trozos) == 2:
        for k in variantes(trozos[-1]):
            if len(k) >= 3:
                out.add(k)
    return out


def casa_con_serie(titulo: str, serie: str) -> bool:
    if not clave(titulo):
        return True
    return bool(variantes(titulo) & claves_serie(serie))


# --------------------------------------------------------------------------
# Serie canónica a partir de una carpeta (heurística, sin taxonomía)
# --------------------------------------------------------------------------

# etiquetas de editorial o de escaneo que no son parte del nombre
_RUIDO = re.compile(
    r"\s*[\(\[]\s*(forum|panini|planeta|norma|ivrea|infinity|usa|esp|español|espanol|"
    r"colecci[oó]n|c[oó]mic|comic|comics|mq|hq|lq|crg|c2c|digital|\d+\s+libros?|completo|"
    r"completa|scan|scans|cbr|cbz|pdf)\s*[\)\]]",
    re.I,
)
# rangos al final: «1-4», «#1-5», «1--7», «#01-05», «1 -4»
_RANGO_FINAL = re.compile(r"\s*#?\s*\d{1,4}\s*-{1,2}\s*\d{1,4}\s*$")
_GENERICA = re.compile(
    r"^(\d+[\s.\-–]|parte\b|part\b|arco\b|saga\b|tomo\b|tomos\b|vol\b|vol\.|volumen\b|libro\b|"
    r"temporada\b|season\b|cap[ií]tulos?\b|crossovers?\b|extras?\b|especiales\b|anuales\b|"
    r"faltantes\b|one[- ]shots?\b|miniseries?\b|otros\b|varios\b)",
    re.I,
)


def normalizar_volumen(s: str) -> str:
    """«Vol.1», «Vol 1», «vol1», «Volumen 1» -> «Vol. 1»."""
    return _VOL.sub(lambda m: f"Vol. {int(m.group(1))}", s)


_CREDITOS = re.compile(r"\s*\((?:de|por|by|ed\.|editorial)\b[^()]*\)|\s*\([^()]*\d[^()]*\)", re.I)


def serie_de_carpeta(nombre: str) -> str:
    """Nombre canónico heurístico de una carpeta-serie."""
    s = limpiar(nombre)
    m = re.fullmatch(r"[\(\[]([^()\[\]]+)[\)\]]", s)
    if m:  # carpeta entera entre paréntesis: «(SERIE EN SEPIA)»
        s = espacios(m.group(1))
    prev = None
    while prev != s:
        prev = s
        s = _RUIDO.sub("", s)
        s = _CREDITOS.sub("", s)
        s = _RANGO_FINAL.sub("", s)
        s = espacios(s)
    s = normalizar_volumen(s)
    s = s.strip(" -–—.,")
    return s or limpiar(nombre)


def es_carpeta_generica(nombre: str) -> bool:
    return bool(_GENERICA.match(limpiar(nombre)))


# --------------------------------------------------------------------------
# Interpretación de un nombre de fichero
# --------------------------------------------------------------------------

_ANIO_PAR = re.compile(r"\((19[3-9]\d|20[0-3]\d)\)")
_RANGO = re.compile(r"(?<![\w.])\d{1,4}\s*(?:-{1,2}|–|\bal\b|\ba\b)\s*\d{1,4}(?![\d.])")
_NUM = r"(\d{1,4}(?:\.\d{1,2})?[a-z]?)"
_P_ALMOHADILLA = re.compile(r"#\s*" + _NUM + r"(?![\w])", re.I)
_P_NUMERO_ORD = re.compile(r"\bn\s*[º°]\s*\.?\s*" + _NUM + r"(?![\w])", re.I)
_P_DE = re.compile(r"(?<![\w.])(\d{1,4})\s*de\s*\d{1,4}(?![\w])", re.I)
_P_CAPITULO = re.compile(r"\b(?:cap[ií]tulo|cap\.?|chapter|ch\.?|episodio|ep\.?)\s*" + _NUM + r"(?![\w])", re.I)
_P_TOMO = re.compile(r"\b(?:tomo|t\.?|libro|book|parte|part)\s*\.?\s*" + _NUM + r"(?![\w])", re.I)
_P_VOL = re.compile(r"\b(?:vol(?:umen|ume)?|v)\s*\.?\s*" + _NUM + r"(?![\w])", re.I)
_P_VOL_FINAL = re.compile(r"\b(?:vol(?:umen|ume)?|v)\s*\.?\s*" + _NUM + r"$", re.I)
_P_FINAL = re.compile(r"(?:^|(?<=[\s.\-–—,]))" + _NUM + r"$", re.I)
_P_FINAL_ANIO = re.compile(r"(?:^|(?<=[\s.\-–—,]))(\d{1,4})\s+(19[3-9]\d|20[0-3]\d)$")
_ES_ANIO = re.compile(r"19[3-9]\d|20[0-3]\d")
_P_INICIAL = re.compile(r"^(\d{1,4}[a-z]?)(?:\s*\.-\s*|\s+-\s+|\.\s+|\s+)(?=\D)", re.I)


def formatear_numero(n: str) -> str:
    """'1' -> '001', '0.1' -> '000.1', '5b' -> '005b', '1000' -> '1000'."""
    m = re.match(r"^(\d+)((?:\.\d+)?)([a-z]?)$", n, re.I)
    if not m:
        return n
    entero = int(m.group(1))
    return f"{entero:03d}{m.group(2)}{m.group(3).lower()}"


def _quitar(s: str, m: re.Match) -> str:
    return espacios(s[: m.start()] + " " + s[m.end():])


def _titulo(s: str) -> str:
    s = espacios(s)
    return s.strip(" -–—.,:;#_")


def interpretar(stem: str, serie: str, ficheros_en_serie: int, raiz: bool = False) -> dict:
    """Interpreta el nombre (sin extensión) de un fichero dentro de su serie.

    Devuelve {base, numero, anio, interpretado, motivo}: «base» es el nombre final
    sin extensión. Si no se interpreta con seguridad, base = nombre limpio y
    motivo dice por qué.
    """
    limpio = limpiar(stem)
    # «Serie #12cbr» (sin punto antes de la extensión)
    limpio = re.sub(r"(?i)(?<=[\w)\]])\.?(cbr|cbz|cb7|pdf)$", "", limpio).strip() or limpio
    s = limpio
    res = {"base": limpio, "numero": None, "anio": None, "interpretado": False, "motivo": ""}

    # año entre paréntesis (uno solo)
    anios = list(_ANIO_PAR.finditer(s))
    anio = None
    if len(anios) == 1:
        anio = int(anios[0].group(1))
        s = _quitar(s, anios[0])
    elif len(anios) > 1:
        res["motivo"] = "varios años"
        return res

    # etiquetas entre paréntesis o corchetes: fuera; si llevan cifras, hay ambigüedad
    grupos = _PARENTESIS.findall(s)
    if any(re.search(r"\d", g) for g in grupos):
        res["motivo"] = "cifras entre paréntesis"
        return res
    s = _sin_parentesis(s)

    if raiz:
        # fichero suelto en la raíz: es un número único, su título es la serie
        if _RANGO.search(s):
            res["motivo"] = "rango de números"
            return res
        base = serie + (f" ({anio})" if anio else "")
        res.update(base=base, anio=anio, interpretado=True)
        return res

    m = _RANGO.search(s)
    if m and clave(m.group(0)) not in clave(serie):
        res["motivo"] = "rango de números"
        return res

    numero = None
    m = _P_FINAL_ANIO.search(s)
    if m and anio is None:
        # «Serie 012 1999»: número y año
        candidatos = [(m.group(1), _quitar(s, m), int(m.group(2)))]
    else:
        fuertes = []
        for patron in (_P_ALMOHADILLA, _P_NUMERO_ORD, _P_DE, _P_CAPITULO, _P_TOMO):
            fuertes += list(patron.finditer(s))
        if len(fuertes) > 1:
            res["motivo"] = "varios números"
            return res
        if fuertes:
            candidatos = [(fuertes[0].group(1), _quitar(s, fuertes[0]), anio)]
        else:
            # débiles: se prueban en orden y vale el primero cuyo título casa con la serie
            candidatos = []
            for patron in (_P_VOL_FINAL, _P_FINAL, _P_INICIAL):
                m = patron.search(s)
                # un número suelto de 4 cifras que parece un año («1984») no es un número de serie
                if m and not (patron is not _P_VOL_FINAL and _ES_ANIO.fullmatch(m.group(1))):
                    candidatos.append((m.group(1), _quitar(s, m), anio))
            vols = list(_P_VOL.finditer(s))
            if len(vols) == 1:
                candidatos.append((vols[0].group(1), _quitar(s, vols[0]), anio))
            candidatos.append((None, s, anio))

    titulo = None
    for num_c, resto, anio_c in candidatos:
        t = _titulo(resto)
        if casa_con_serie(t, serie):
            numero, titulo, anio = num_c, t, anio_c
            break
    if titulo is None:
        t = _titulo(candidatos[0][1])
        res["motivo"] = "cifras sueltas" if re.search(r"\d", t) else "el título no coincide con la serie"
        return res

    if numero is None:
        if ficheros_en_serie == 1:
            base = serie + (f" ({anio})" if anio else "")
            res.update(base=base, anio=anio, interpretado=True)
            return res
        res["motivo"] = "sin número"
        return res

    num = formatear_numero(numero)
    base = f"{serie} {num}" + (f" ({anio})" if anio else "")
    res.update(base=base, numero=num, anio=anio, interpretado=True)
    return res


def recortar_nombre(base: str, ext: str, max_bytes: int = 200) -> str:
    """Nombre final con extensión, recortado a max_bytes UTF-8 si hiciera falta."""
    nombre = base + ext
    if len(nombre.encode("utf-8")) <= max_bytes:
        return nombre
    b = base
    while len((b + "…" + ext).encode("utf-8")) > max_bytes and b:
        b = b[:-1]
    return b.rstrip() + "…" + ext


def componente_seguro(s: str) -> str:
    """Componente válido para Drive y Android: sin '/', sin NUL, sin '.' o '..'."""
    s = espacios(nfc(s)).replace("/", "∕").replace("\x00", "")
    s = re.sub(r"[\x00-\x1f]", "", s)
    if s in ("", ".", ".."):
        s = "_"
    return s


# --------------------------------------------------------------------------
# Orden natural
# --------------------------------------------------------------------------

_TROZOS = re.compile(r"(\d+)")


def clave_natural(s: str):
    s = sin_acentos(nfc(s)).casefold()
    return [(0, int(t), "") if t.isdigit() else (1, 0, t) for t in _TROZOS.split(s) if t != ""]


def clave_ruta(rel: str):
    return [clave_natural(c) for c in rel.split("/")]
