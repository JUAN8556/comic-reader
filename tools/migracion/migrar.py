#!/usr/bin/env python3
"""Migración de la biblioteca de cómics: iCloud Drive -> Google Drive, por tandas.

Subcomandos:
  plan       solo lectura (lstat): qué haría, cuánto pesa, cuántos a revisar
  humo       prueba de humo completa sobre una carpeta
  tanda      la siguiente tanda (~15 GB) en orden alfabético por ruta
  verificar  compara la base local con lo que hay en Drive (md5)
  indice     regenera y resube Cómics/.indice sin subir cómics
  estado     resumen de la base local

Flujo de una tanda: censo -> plan -> materializar (leer el fichero de iCloud lo
descarga) + md5 + cabecera + integridad + portada -> subir (rclone copy) ->
verificar md5 contra Drive -> desalojar la copia local -> índice.

Lo que NO hace nunca: borrar, mover ni renombrar nada en iCloud; «rclone sync» o
«delete» en Drive; tocar Cómics/_sonda.

Rutas de datos: COMICS_DATA (por defecto ~/comic-reader-data). Origen:
COMICS_ORIGEN (por defecto la carpeta «Cómics » de iCloud Drive, con espacio final).
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import fcntl
import hashlib
import json
import os
import shutil
import signal
import sqlite3
import subprocess
import sys
import threading
import time
import zipfile
from collections import Counter, defaultdict

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)

import formatos  # noqa: E402
import nombres  # noqa: E402
import portadas  # noqa: E402

BIN_EVICT = os.path.join(AQUI, "bin", "evict")
GB = 1000 ** 3
GiB = 1024 ** 3
SF_DATALESS = 0x40000000

CATEGORIAS = ("Americano", "Europeo", "Latinoamericano", "Manga", "Manhwa", "Manhua")
SIN_EDITORIAL = ("Manga", "Manhwa", "Manhua")
ESTADOS = ("pendiente", "apartado_duplicado", "apartado_roto", "subido", "verificado",
           "desalojado", "ignorado")
EN_DRIVE = ("subido", "verificado", "desalojado")

RCLONE_COMUNES = [
    "--retries", "5", "--low-level-retries", "20", "--local-unicode-normalization",
]
RCLONE_SUBIDA = [
    "--drive-chunk-size", "64M", "--transfers", "4", "--checkers", "8",
    "--drive-stop-on-upload-limit", "--stats-one-line", "--stats", "15s",
    "--stats-log-level", "NOTICE", "-v",
]


# ==========================================================================
# Configuración, registro y utilidades
# ==========================================================================

class Config:
    def __init__(self):
        self.origen = os.environ.get("COMICS_ORIGEN") or os.path.expanduser(
            "~/Library/Mobile Documents/com~apple~CloudDocs/Cómics ")
        self.data = os.path.expanduser(os.environ.get("COMICS_DATA") or "~/comic-reader-data")
        self.remoto = os.environ.get("COMICS_REMOTO", "gdrive:Cómics")
        self.cuenta = self.remoto.split(":", 1)[0] + ":"
        self.tanda_gb = float(os.environ.get("TANDA_GB", "15"))
        self.reserva_gib = float(os.environ.get("RESERVA_GIB", "15"))
        self.min_libre_mac_gb = float(os.environ.get("MIN_LIBRE_MAC_GB", "40"))
        self.lote_gb = float(os.environ.get("LOTE_GB", "2"))
        self.hilos = int(os.environ.get("HILOS", "4"))
        d = self.data
        self.dir_estado = os.path.join(d, "estado")
        self.dir_logs = os.path.join(d, "logs")
        self.dir_portadas = os.path.join(d, "portadas")
        self.dir_revisar = os.path.join(d, "revisar")
        self.dir_metadatos = os.path.join(d, "metadatos")
        self.dir_indice = os.path.join(d, "indice")
        self.dir_subida = os.path.join(d, "subida")
        self.dir_tmp = os.path.join(d, "tmp")
        self.db = os.path.join(self.dir_estado, "migracion.sqlite")
        self.taxonomia = os.path.join(self.dir_metadatos, "taxonomia.json")
        self.series_meta = os.path.join(self.dir_metadatos, "series.jsonl")
        self.heuristica_local = os.path.join(self.dir_estado, "heuristica-local.json")

    def crear_dirs(self):
        for x in (self.dir_estado, self.dir_logs, self.dir_portadas, self.dir_revisar,
                  self.dir_metadatos, self.dir_indice, self.dir_subida, self.dir_tmp):
            os.makedirs(x, exist_ok=True)

    def dentro_de_origen(self, ruta: str) -> bool:
        o = os.path.realpath(self.origen)
        r = os.path.realpath(ruta)
        return r == o or r.startswith(o.rstrip("/") + "/")


class Registro:
    """Escribe en pantalla y en logs/migracion-AAAAMMDD.log."""

    def __init__(self, cfg: Config, nombre: str = "migracion"):
        os.makedirs(cfg.dir_logs, exist_ok=True)
        self.ruta = os.path.join(cfg.dir_logs, f"{nombre}-{dt.date.today():%Y%m%d}.log")
        self.lock = threading.Lock()

    def __call__(self, msg: str = "", pantalla: bool = True):
        with self.lock:
            if pantalla:
                print(msg, flush=True)
            with open(self.ruta, "a", encoding="utf-8") as f:
                f.write(f"{dt.datetime.now():%Y-%m-%d %H:%M:%S} {msg}\n")

    def solo_log(self, msg: str):
        self(msg, pantalla=False)


def ahora() -> str:
    return dt.datetime.now().isoformat(timespec="seconds")


def num(n: float, dec: int = 0) -> str:
    """Formato español: 9.399 · 227,5."""
    s = f"{n:,.{dec}f}"
    return s.replace(",", "\x00").replace(".", ",").replace("\x00", ".")


def gb(b: float, dec: int = 1) -> str:
    return f"{num(b / GB, dec)} GB"


def gib(b: float, dec: int = 1) -> str:
    return f"{num(b / GiB, dec)} GiB"


def mb(b: float) -> str:
    return f"{num(b / 1e6, 1)} MB"


def duracion(seg: float) -> str:
    seg = int(round(seg))
    if seg < 60:
        return f"{seg} s"
    m, s = divmod(seg, 60)
    if m < 60:
        return f"{m} min {s:02d} s"
    h, m = divmod(m, 60)
    return f"{h} h {m:02d} min"


def libre_mac(cfg: Config) -> int:
    return shutil.disk_usage(cfg.data).free


def mantener_despierto():
    """caffeinate -dimsu mientras viva este proceso."""
    if os.environ.get("COMICS_SIN_CAFFEINATE"):
        return None
    try:
        return subprocess.Popen(["caffeinate", "-dimsu", "-w", str(os.getpid())],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        return None


class Cerrojo:
    """Impide dos migraciones a la vez (dos rclone copy podrían duplicar en Drive)."""

    def __init__(self, cfg: Config):
        self.ruta = os.path.join(cfg.dir_estado, "migracion.lock")
        self.f = None

    def __enter__(self):
        self.f = open(self.ruta, "a+")
        try:
            fcntl.flock(self.f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.f.seek(0)
            pid = self.f.read().strip()
            raise SystemExit(f"Ya hay una migración en marcha (pid {pid or '?'}). Espera a que termine.")
        self.f.seek(0)
        self.f.truncate()
        self.f.write(str(os.getpid()))
        self.f.flush()
        return self

    def __exit__(self, *a):
        try:
            fcntl.flock(self.f, fcntl.LOCK_UN)
        finally:
            self.f.close()


# ==========================================================================
# Base de datos de estado
# ==========================================================================

ESQUEMA = """
CREATE TABLE IF NOT EXISTS ficheros (
    ruta TEXT PRIMARY KEY,      -- relativa a la raíz de iCloud, tal como está en disco
    ruta_nfc TEXT,              -- NFC y sin espacios al borde de cada componente
    tamano INTEGER,
    mtime REAL,
    clase TEXT,                 -- comic | no_comic | ignorado
    estado TEXT NOT NULL DEFAULT 'pendiente',
    md5 TEXT,
    formato TEXT,
    solido INTEGER,
    integridad TEXT,            -- ok | parcial | dudosa | rota
    entradas INTEGER,
    imagenes INTEGER,
    paginas INTEGER,
    categoria TEXT,
    editorial TEXT,
    serie TEXT,
    serie_origen TEXT,          -- carpeta de origen (normalizada)
    fuente TEXT,                -- taxonomia | heuristica
    numero TEXT,
    anio INTEGER,
    interpretado INTEGER,
    motivo TEXT,                -- por qué no se interpretó el nombre
    destino TEXT,               -- relativa a la raíz de Drive (gdrive:Cómics)
    drive_id TEXT,
    md5_drive TEXT,
    duplicado_de TEXT,
    estaba_local INTEGER,       -- ya estaba materializado antes de empezar
    portada TEXT,
    nota TEXT,
    tanda INTEGER,
    error TEXT,
    actualizado TEXT
);
CREATE INDEX IF NOT EXISTS ix_md5 ON ficheros(md5);
CREATE INDEX IF NOT EXISTS ix_estado ON ficheros(estado);
CREATE INDEX IF NOT EXISTS ix_destino ON ficheros(destino);
CREATE TABLE IF NOT EXISTS tandas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT,                  -- tanda | humo
    alcance TEXT,
    inicio TEXT,
    fin TEXT,
    bytes INTEGER,
    ficheros INTEGER,
    resultado TEXT
);
"""


_CONEXIONES: list[sqlite3.Connection] = []


def abrir_db(cfg: Config) -> sqlite3.Connection:
    os.makedirs(cfg.dir_estado, exist_ok=True)
    conn = sqlite3.connect(cfg.db, timeout=30)
    _CONEXIONES.append(conn)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=FULL")
    conn.executescript(ESQUEMA)
    return conn


def filas_por_rutas(conn: sqlite3.Connection, rutas: list[str], columnas: str = "*") -> list[sqlite3.Row]:
    out = []
    for i in range(0, len(rutas), 500):
        trozo = rutas[i:i + 500]
        out += conn.execute(f"SELECT {columnas} FROM ficheros WHERE ruta IN ({','.join('?' * len(trozo))})",
                            trozo).fetchall()
    return out


def actualizar(conn: sqlite3.Connection, ruta: str, **campos):
    campos["actualizado"] = ahora()
    sets = ", ".join(f"{k}=?" for k in campos)
    conn.execute(f"UPDATE ficheros SET {sets} WHERE ruta=?", (*campos.values(), ruta))
    conn.commit()


# ==========================================================================
# Censo (solo lstat)
# ==========================================================================

def clase_de(nombre: str) -> str:
    n = nombre.lower()
    if n in nombres.IGNORADOS or n.startswith("."):
        return "ignorado"
    ext = os.path.splitext(n)[1]
    if ext in nombres.EXT_COMIC or n.endswith(("cbr", "cbz", "cb7")):
        return "comic"
    return "no_comic"


def censar(cfg: Config) -> list[dict]:
    if not os.path.isdir(cfg.origen):
        raise SystemExit(f"No encuentro la carpeta de origen: {cfg.origen!r}")
    out = []
    for dirpath, dirnames, filenames in os.walk(cfg.origen):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            if not os.path.isfile(p) or os.path.islink(p):
                continue
            rel = os.path.relpath(p, cfg.origen)
            out.append({
                "ruta": rel,
                "ruta_nfc": nombres.ruta_normalizada(rel),
                "tamano": st.st_size,
                "mtime": st.st_mtime,
                "bloques": st.st_blocks,
                "sin_datos": bool(st.st_flags & SF_DATALESS),
                "clase": clase_de(fn),
            })
    out.sort(key=lambda f: nombres.clave_ruta(f["ruta_nfc"]))
    return out


def es_local(st: os.stat_result) -> bool:
    return st.st_blocks > 0 and not (st.st_flags & SF_DATALESS)


# ==========================================================================
# Taxonomía, heurística y nombres de destino
# ==========================================================================

def cargar_json(ruta: str):
    try:
        with open(ruta, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        print(f"Aviso: no se pudo leer {ruta}: {e}")
        return None


def cargar_taxonomia(cfg: Config) -> dict[str, dict]:
    """Acepta {ruta: {...}}, {"series": {...}|[...]} o [{"ruta": ...}, ...]."""
    datos = cargar_json(cfg.taxonomia)
    if datos is None:
        return {}
    if isinstance(datos, dict):
        for k in ("series", "rutas", "taxonomia", "carpetas"):
            if k in datos and isinstance(datos[k], (dict, list)):
                datos = datos[k]
                break
    out = {}
    if isinstance(datos, dict):
        items = datos.items()
    elif isinstance(datos, list):
        items = [(d.get("ruta"), d) for d in datos if isinstance(d, dict)]
    else:
        return {}
    for ruta, d in items:
        if not ruta or not isinstance(d, dict):
            continue
        out[nombres.ruta_normalizada(ruta.rstrip("/"))] = d
    return out


def cargar_heuristica_local(cfg: Config) -> dict:
    datos = cargar_json(cfg.heuristica_local) or {}
    out = {"primer_nivel": {}, "raiz": {}}
    for seccion in out:
        for k, v in (datos.get(seccion) or {}).items():
            if isinstance(v, list) and len(v) >= 1:
                out[seccion][nombres.clave(k)] = (v[0], v[1] if len(v) > 1 else "")
    return out


_MANGA = ("dr stone", "dragon ball", "naruto", "death note", "elfen lied", "hellsing", "parasyte",
          "chobits", "battle royale", "pokemon adventures", "one piece", "bleach", "berserk",
          "akira", "attack on titan", "shingeki no kyojin")
_IMAGE = ("the walking dead", "invincible", "sexcriminals", "sex criminals", "saga", "spawn")
_CONTENEDORES = {"marvel", "marvel comics", "dc", "dc comics", "image", "image comics",
                 "dark horse", "dark horse comics"}


def heuristica(primer_nivel: str, locales: dict) -> tuple[str, str]:
    k = nombres.clave(primer_nivel)
    if k in locales["primer_nivel"]:
        return locales["primer_nivel"][k]
    if k.startswith("marvel"):
        return "Americano", "Marvel"
    if k == "dc" or k.startswith("dc "):
        return "Americano", "DC"
    if k in _IMAGE or k.startswith("image"):
        return "Americano", "Image"
    if k.startswith("dark horse"):
        return "Americano", "Dark Horse"
    if "novaro" in k:
        return "Latinoamericano", "Novaro"
    if k.startswith("memin"):
        return "Latinoamericano", "Memín"
    if k.startswith("condorito"):
        return "Latinoamericano", "Condorito"
    if k.startswith("mafalda"):
        return "Latinoamericano", "Mafalda"
    if k.startswith("los pitufos") or k.startswith("pitufos"):
        return "Europeo", "Franco-belga"
    if any(k == m or k.startswith(m + " ") for m in _MANGA):
        return "Manga", ""
    return "Americano", "Otros"


def _valida_tax(d: dict) -> tuple[str, str, str] | None:
    cat = (d.get("categoria") or "").strip()
    edit = (d.get("editorial_carpeta") or d.get("editorial") or "").strip()
    serie = (d.get("serie_canonica") or d.get("serie") or "").strip()
    if cat not in CATEGORIAS or not serie:
        return None
    for c in (cat, edit, serie):
        if c.startswith(".") or c.startswith("_") or "/" in c:
            return None
    if cat in SIN_EDITORIAL and edit.casefold() in ("", "manga", "manhwa", "manhua"):
        edit = ""
    return cat, edit, nombres.nfc(serie)


def resolver_series(censo: list[dict], taxonomia: dict, locales: dict) -> dict[str, dict]:
    """Para cada carpeta con cómics (y cada fichero suelto en la raíz): categoría,
    editorial, serie canónica y de dónde salió (taxonomía o heurística)."""
    carpetas: dict[str, list[dict]] = defaultdict(list)
    for f in censo:
        if f["clase"] == "comic":
            carpetas[os.path.dirname(f["ruta_nfc"]) or "."].append(f)
    info: dict[str, dict] = {}
    for carpeta, fs in carpetas.items():
        comps = [] if carpeta == "." else carpeta.split("/")
        suelta = carpeta == "." or (len(comps) == 1 and nombres.clave(comps[0]) in _CONTENEDORES)
        if suelta:
            # ficheros sueltos (raíz o carpeta-editorial): cada uno es su propia «serie»
            for f in fs:
                stem = _stem(os.path.basename(f["ruta_nfc"]))
                tax = taxonomia.get(f["ruta_nfc"]) or taxonomia.get(os.path.splitext(f["ruta_nfc"])[0])
                v = _valida_tax(tax) if tax else None
                if v:
                    cat, edit, serie = v
                    fuente = "taxonomia"
                else:
                    k = nombres.clave(stem)
                    if comps:
                        cat, edit = heuristica(comps[0], locales)
                    else:
                        cat, edit = locales["raiz"].get(k) or ("Americano", "Otros")
                    serie = nombres.serie_de_carpeta(stem)
                    fuente = "heuristica"
                info["file:" + f["ruta_nfc"]] = {
                    "categoria": cat, "editorial": edit, "serie": serie, "fuente": fuente,
                    "carpeta": carpeta, "suelto": True, "n": 1,
                }
            continue
        tax = taxonomia.get(carpeta)
        v = _valida_tax(tax) if tax else None
        if v:
            cat, edit, serie = v
            fuente = "taxonomia"
        else:
            cat, edit = heuristica(comps[0], locales)
            hoja = comps[-1]
            serie = nombres.serie_de_carpeta(hoja)
            if len(comps) >= 2 and nombres.es_carpeta_generica(hoja) and \
                    nombres.clave(comps[-2]) not in _CONTENEDORES:
                serie = f"{nombres.serie_de_carpeta(comps[-2])} - {serie}"
            fuente = "heuristica"
        info[carpeta] = {"categoria": cat, "editorial": edit, "serie": serie, "fuente": fuente,
                         "carpeta": carpeta, "suelto": False, "n": len(fs)}

    # dos carpetas heurísticas distintas con la misma serie en el mismo sitio: desambiguar
    grupos = defaultdict(list)
    for k, v in info.items():
        if not v["suelto"]:
            grupos[(v["categoria"], v["editorial"], nombres.clave(v["serie"]))].append(k)
    for (_c, _e, _s), ks in grupos.items():
        heur = [k for k in ks if info[k]["fuente"] == "heuristica"]
        if len(ks) < 2 or not heur:
            continue
        usados = {nombres.clave(info[k]["serie"]) for k in ks if info[k]["fuente"] == "taxonomia"}
        for k in sorted(heur, key=nombres.clave_ruta)[(0 if usados else 1):]:
            comps = k.split("/")
            if len(comps) >= 2 and nombres.clave(comps[-2]) not in _CONTENEDORES:
                info[k]["serie"] = f"{nombres.serie_de_carpeta(comps[-2])} - {nombres.serie_de_carpeta(comps[-1])}"
                info[k]["nota"] = "serie repetida: se antepone la carpeta madre"
    # si aún chocan, numerar
    vistos = Counter()
    for k in sorted(info, key=lambda x: nombres.clave_ruta(x.replace("file:", ""))):
        v = info[k]
        if v["suelto"]:
            continue
        clave_s = (v["categoria"], v["editorial"], nombres.clave(v["serie"]))
        vistos[clave_s] += 1
        if vistos[clave_s] > 1 and v["fuente"] == "heuristica":
            v["serie"] = f"{v['serie']} ({vistos[clave_s]})"
            v["nota"] = "serie repetida: numerada"
    return info


def _stem(nombre: str) -> str:
    raiz, ext = os.path.splitext(nombre)
    return raiz if ext.lower() in nombres.EXT_COMIC else nombre


def info_serie(info: dict, f: dict) -> dict:
    return info.get("file:" + f["ruta_nfc"]) or info[os.path.dirname(f["ruta_nfc"]) or "."]


def ext_declarada(nombre: str) -> str:
    ext = os.path.splitext(nombre)[1].lower()
    if ext in (".rar",):
        return ".cbr"
    if ext in (".zip",):
        return ".cbz"
    if ext in (".7z",):
        return ".cb7"
    if ext in nombres.EXT_COMIC:
        return ext
    for e in ("cbr", "cbz", "cb7", "pdf"):
        if nombre.lower().endswith(e):
            return "." + e
    return ext


def destino_de(si: dict, base: str, ext: str) -> str:
    serie = nombres.componente_seguro(si["serie"])
    nombre = nombres.recortar_nombre(nombres.componente_seguro(base), ext)
    partes = [si["categoria"]] + ([si["editorial"]] if si["editorial"] else []) + [serie, nombre]
    return "/".join(partes)


def nombrar(f: dict, si: dict, ext: str) -> dict:
    stem = _stem(os.path.basename(f["ruta_nfc"]))
    r = nombres.interpretar(stem, si["serie"], si["n"], raiz=si["suelto"])
    r["destino"] = destino_de(si, r["base"], ext)
    return r


# ==========================================================================
# Plan (comparte lógica con tanda y humo)
# ==========================================================================

class Plan:
    def __init__(self, cfg: Config, censo: list[dict], conn: sqlite3.Connection | None):
        self.cfg = cfg
        self.censo = censo
        self.taxonomia = cargar_taxonomia(cfg)
        self.locales = cargar_heuristica_local(cfg)
        self.series = resolver_series(censo, self.taxonomia, self.locales)
        self.db = {}
        if conn is not None:
            for r in conn.execute("SELECT * FROM ficheros"):
                self.db[r["ruta"]] = dict(r)
        self.nombres: dict[str, dict] = {}
        ocupados: dict[str, str] = {}
        # los destinos ya fijados en la base (subidos o analizados) mandan
        for r in self.db.values():
            if r.get("destino") and r["estado"] not in ("apartado_duplicado", "apartado_roto", "ignorado"):
                ocupados[r["destino"].casefold()] = r["ruta"]
        for f in censo:
            if f["clase"] != "comic":
                continue
            fila = self.db.get(f["ruta"])
            si = info_serie(self.series, f)
            if fila and fila.get("destino") and fila["estado"] != "pendiente":
                self.nombres[f["ruta"]] = {"destino": fila["destino"], "interpretado": fila["interpretado"],
                                           "motivo": fila["motivo"], "numero": fila["numero"],
                                           "anio": fila["anio"], "base": None}
                continue
            ext = EXT_FORMATO_DB(fila) or ext_declarada(os.path.basename(f["ruta_nfc"]))
            r = nombrar(f, si, ext)
            r = resolver_choque(r, f, si, ext, ocupados)
            ocupados[r["destino"].casefold()] = f["ruta"]
            self.nombres[f["ruta"]] = r

    def pendientes(self) -> list[dict]:
        out = []
        for f in self.censo:
            if f["clase"] != "comic":
                continue
            fila = self.db.get(f["ruta"])
            if fila and fila["estado"] != "pendiente":
                continue
            if f["tamano"] == 0:
                continue
            out.append(f)
        return out


def EXT_FORMATO_DB(fila: dict | None) -> str | None:  # noqa: N802
    if fila and fila.get("formato") in formatos.EXT_REAL:
        return formatos.EXT_REAL[fila["formato"]]
    return None


def resolver_choque(r: dict, f: dict, si: dict, ext: str, ocupados: dict[str, str]) -> dict:
    if ocupados.get(r["destino"].casefold(), f["ruta"]) == f["ruta"]:
        return r
    limpio = nombres.limpiar(_stem(os.path.basename(f["ruta_nfc"])))
    alt = destino_de(si, limpio, ext)
    n = 2
    while ocupados.get(alt.casefold(), f["ruta"]) != f["ruta"]:
        alt = destino_de(si, f"{limpio} [{n}]", ext)
        n += 1
    r = dict(r, destino=alt, interpretado=False, motivo="choque de nombres: se conserva el nombre limpio")
    return r


def elegir_tanda(pendientes: list[dict], limite: int) -> list[dict]:
    """Ficheros en orden alfabético por ruta hasta ~limite bytes. Prefiere cortar
    entre series: si la siguiente serie entera no cabe y ya llevamos la mitad, para."""
    por_serie: list[list[dict]] = []
    actual, carpeta = [], None
    for f in pendientes:
        c = os.path.dirname(f["ruta_nfc"])
        if c != carpeta and actual:
            por_serie.append(actual)
            actual = []
        carpeta = c
        actual.append(f)
    if actual:
        por_serie.append(actual)
    tanda, total = [], 0
    for grupo in por_serie:
        tam = sum(f["tamano"] for f in grupo)
        if total + tam <= limite:
            tanda += grupo
            total += tam
            continue
        if total >= limite / 2:
            break
        for f in grupo:
            if total + f["tamano"] > limite and tanda:
                return tanda
            tanda.append(f)
            total += f["tamano"]
        break
    return tanda


# ==========================================================================
# Cuota de Drive y rclone
# ==========================================================================

def rclone(args: list[str], log: Registro | None = None, capturar: bool = True,
           mostrar: bool = False, timeout: int | None = None) -> tuple[int, str, str]:
    """Ejecuta rclone. Con mostrar=True enseña las líneas de progreso y errores."""
    cmd = ["rclone"] + args + RCLONE_COMUNES
    if log:
        log.solo_log("$ " + " ".join(_cita(a) for a in cmd))
    if not mostrar:
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return 124, "", f"rclone no terminó en {timeout} s"
        if log and r.stderr.strip():
            for linea in r.stderr.strip().splitlines()[-30:]:
                log.solo_log("  rclone: " + linea)
        return r.returncode, r.stdout, r.stderr
    ultimas = []
    with subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1) as p:
        for linea in p.stdout:
            linea = linea.rstrip()
            if not linea:
                continue
            ultimas = (ultimas + [linea])[-40:]
            if log:
                log.solo_log("  rclone: " + linea)
            if any(x in linea for x in ("ETA", "ERROR", "Failed", "NOTICE", "Fatal", "error", "Copied")):
                print("    " + (linea.split(" : ", 1)[-1] if " : " in linea else linea), flush=True)
        rc = p.wait()
    return rc, "", "\n".join(ultimas)


def _cita(a: str) -> str:
    return a if a and all(c.isalnum() or c in "-_./:=@%+," for c in a) else repr(a)


def cuota(cfg: Config, log: Registro) -> dict:
    rc, out, err = rclone(["about", cfg.cuenta, "--json"], log, timeout=120)
    if rc != 0:
        raise SystemExit(f"No pude leer la cuota de Drive (rclone about): {err.strip()[-300:]}")
    return json.loads(out)


def comprobar_cuota(cfg: Config, log: Registro, bytes_tanda: int) -> dict:
    c = cuota(cfg, log)
    libre = int(c.get("free", 0))
    reserva = int(cfg.reserva_gib * GiB)
    log(f"Drive: {gib(libre)} libres de {gib(c.get('total', 0))} (reserva mínima {num(cfg.reserva_gib)} GiB).")
    if libre < reserva:
        raise SystemExit(
            f"PARADA: a Drive le quedan {gib(libre)} libres, menos de la reserva de {num(cfg.reserva_gib)} GiB.\n"
            "No se sube nada más. Para seguir hay que ampliar Google One (ver docs/MIGRACION.md).")
    if libre - bytes_tanda < reserva:
        cabe = max(0, libre - reserva)
        raise SystemExit(
            f"PARADA: esta tanda ({gb(bytes_tanda)}) dejaría a Drive con {gib(libre - bytes_tanda)} libres, "
            f"por debajo de la reserva de {num(cfg.reserva_gib)} GiB.\n"
            f"Caben {gb(cabe)} sin tocar la reserva: lanza una tanda más pequeña con "
            f"«make migrar TANDA_GB={max(1, int(cabe / GB))}» o amplía Google One.")
    return c


# ==========================================================================
# Materializar y analizar (un hilo por fichero)
# ==========================================================================

_local = threading.local()


def _tmpdir(cfg: Config) -> str:
    if not hasattr(_local, "dir"):
        _local.dir = os.path.join(cfg.dir_tmp, f"h{threading.get_ident()}")
        os.makedirs(_local.dir, exist_ok=True)
    assert not cfg.dentro_de_origen(_local.dir)
    return _local.dir


def leer_md5(ruta: str) -> tuple[str, bytes, int]:
    """Lee el fichero entero (si está desalojado, iCloud lo descarga): md5, cabecera, bytes."""
    h = hashlib.md5()
    total = 0
    with open(ruta, "rb") as fh:
        cab = fh.read(formatos.BYTES_CABECERA)
        h.update(cab)
        total += len(cab)
        while True:
            b = fh.read(4 << 20)
            if not b:
                break
            h.update(b)
            total += len(b)
    return h.hexdigest(), cab, total


def paginas_pdf(ruta: str) -> int | None:
    try:
        r = subprocess.run(["exiftool", "-s3", "-PageCount", ruta], capture_output=True, text=True, timeout=60)
        return int(r.stdout.strip()) if r.stdout.strip().isdigit() else None
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None


def analizar(cfg: Config, fila: dict) -> dict:
    """Trabajo de un hilo: materializa, md5, cabecera, integridad, portada."""
    ruta = os.path.join(cfg.origen, fila["ruta"])
    t0 = time.time()
    out = {"ruta": fila["ruta"], "error": None}
    try:
        st = os.lstat(ruta)
    except OSError as e:
        out["error"] = f"no se puede leer en iCloud: {e}"
        return out
    out["estaba_local"] = 1 if es_local(st) else 0
    reusar = (fila.get("md5") and fila.get("formato") and fila.get("tamano") == st.st_size
              and fila.get("mtime") == st.st_mtime and fila.get("integridad"))
    try:
        if reusar and es_local(st):
            out.update(md5=fila["md5"], formato=fila["formato"], solido=fila["solido"],
                       integridad=fila["integridad"], entradas=fila["entradas"], imagenes=fila["imagenes"],
                       paginas=fila["paginas"], nota=fila["nota"], portada=fila["portada"], reusado=True,
                       estaba_local=fila.get("estaba_local", out["estaba_local"]))
            if out["portada"] and os.path.exists(os.path.join(cfg.dir_portadas, out["portada"])):
                out["segundos"] = time.time() - t0
                return out
        md5, cab, leidos = leer_md5(ruta)
        if leidos != st.st_size:
            out["error"] = f"leídos {leidos} bytes de {st.st_size}"
            return out
        formato, solido = formatos.detectar(cab)
        integ = formatos.integridad(ruta, formato, solido, st.st_size)
        out.update(md5=md5, formato=formato, solido=None if solido is None else int(solido),
                   integridad=integ["estado"], entradas=integ["entradas"], imagenes=integ["imagenes"],
                   nota=integ["nota"] or None, error_integridad=integ["error"] or None)
        out["paginas"] = integ["imagenes"] or None
        if formato == formatos.PDF:
            out["paginas"] = paginas_pdf(ruta)
        out["portada"] = None
        if integ["estado"] != "rota":
            webp = os.path.join(cfg.dir_portadas, f"{md5}.webp")
            if os.path.exists(webp):
                out["portada"] = f"{md5}.webp"
            else:
                hecho, nota = portadas.generar(ruta, formato, integ["lista"], solido, webp, _tmpdir(cfg))
                if hecho:
                    out["portada"] = f"{md5}.webp"
                else:
                    out["nota"] = "; ".join(x for x in (out.get("nota"), f"sin portada: {nota}") if x)
    except OSError as e:
        out["error"] = f"error de lectura: {e}"
    out["segundos"] = time.time() - t0
    return out


# ==========================================================================
# Censo -> base de datos
# ==========================================================================

def sincronizar_censo(conn: sqlite3.Connection, plan: Plan, log: Registro):
    """Mete en la base los ficheros nuevos y refresca los datos de plan de los pendientes."""
    vistos = set()
    nuevos = 0
    for f in plan.censo:
        vistos.add(f["ruta"])
        fila = plan.db.get(f["ruta"])
        if f["clase"] == "comic":
            si = info_serie(plan.series, f)
            nm = plan.nombres[f["ruta"]]
            plan_campos = dict(categoria=si["categoria"], editorial=si["editorial"], serie=si["serie"],
                               serie_origen=si["carpeta"], fuente=si["fuente"], numero=nm["numero"],
                               anio=nm["anio"], interpretado=int(bool(nm["interpretado"])),
                               motivo=nm["motivo"] or None, destino=nm["destino"])
        else:
            plan_campos = {}
        if fila is None:
            estado = "pendiente" if f["clase"] == "comic" else "ignorado"
            error = None
            if f["clase"] == "comic" and f["tamano"] == 0:
                estado, error = "apartado_roto", "0 bytes"
            nota = "no es un cómic" if f["clase"] == "no_comic" else None
            campos = dict(ruta=f["ruta"], ruta_nfc=f["ruta_nfc"], tamano=f["tamano"], mtime=f["mtime"],
                          clase=f["clase"], estado=estado, error=error, nota=nota,
                          estaba_local=1 if (f["bloques"] > 0 and not f["sin_datos"]) else 0,
                          actualizado=ahora(), **plan_campos)
            conn.execute(f"INSERT INTO ficheros ({', '.join(campos)}) VALUES ({', '.join('?' * len(campos))})",
                         tuple(campos.values()))
            nuevos += 1
        elif fila["estado"] == "pendiente":
            cambios = dict(plan_campos)
            if fila["tamano"] != f["tamano"] or fila["mtime"] != f["mtime"]:
                cambios.update(tamano=f["tamano"], mtime=f["mtime"], md5=None, formato=None, integridad=None,
                               portada=None)
            if fila.get("error", "") and fila["error"].startswith("ya no está"):
                cambios["error"] = None
            sets = ", ".join(f"{k}=?" for k in cambios)
            if sets:
                conn.execute(f"UPDATE ficheros SET {sets} WHERE ruta=?", (*cambios.values(), f["ruta"]))
    for ruta, fila in plan.db.items():
        if ruta not in vistos and fila["estado"] == "pendiente":
            conn.execute("UPDATE ficheros SET error=? WHERE ruta=?", ("ya no está en iCloud", ruta))
    conn.commit()
    if nuevos:
        log.solo_log(f"censo: {nuevos} ficheros nuevos en la base")


# ==========================================================================
# Procesar un conjunto de ficheros (tanda o humo)
# ==========================================================================

def limpiar_subida(cfg: Config):
    """Vacía la carpeta de enlaces de subida: SOLO borra enlaces simbólicos y carpetas vacías."""
    base = cfg.dir_subida
    assert not cfg.dentro_de_origen(base)
    for dirpath, dirnames, filenames in os.walk(base, topdown=False):
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            if os.path.islink(p):
                os.unlink(p)
        for d in dirnames:
            p = os.path.join(dirpath, d)
            if os.path.islink(p):
                os.unlink(p)
            else:
                try:
                    os.rmdir(p)
                except OSError:
                    pass


def desalojar(cfg: Config, rutas_abs: list[str]) -> dict[str, str]:
    """bin/evict por grupos; devuelve {ruta: 'ok' | mensaje de error}."""
    res = {}
    for i in range(0, len(rutas_abs), 100):
        grupo = rutas_abs[i:i + 100]
        r = subprocess.run([BIN_EVICT] + grupo, capture_output=True, text=True, timeout=600)
        for linea in r.stdout.splitlines():
            partes = linea.split("\t")
            if len(partes) >= 2:
                res[partes[1]] = "ok" if partes[0] == "ok" else (partes[2] if len(partes) > 2 else "error")
    for p in rutas_abs:
        res.setdefault(p, "sin respuesta de evict")
    return res


def listar_drive(cfg: Config, log: Registro, carpetas: set[str]) -> dict[str, dict]:
    """lsjson --hash de cada carpeta de destino: {destino: {md5, tam, id}}."""
    out = {}
    for c in sorted(carpetas):
        rc, salida, err = rclone(["lsjson", f"{cfg.remoto}/{c}", "--hash", "--hash-type", "md5",
                                  "--files-only"], log, timeout=600)
        if rc != 0:
            log(f"  aviso: no pude listar {c!r} en Drive: {err.strip()[-200:]}")
            continue
        for e in json.loads(salida or "[]"):
            hashes = {k.lower(): v for k, v in (e.get("Hashes") or {}).items()}
            out[f"{c}/{e['Path']}"] = {"md5": hashes.get("md5"), "tam": e.get("Size"), "id": e.get("ID")}
    return out


def procesar(cfg: Config, conn: sqlite3.Connection, log: Registro, tanda_id: int,
             rutas: list[str], tiempos: dict) -> dict:
    """Materializa, analiza, sube, verifica y desaloja los ficheros dados, por lotes."""
    if not os.access(BIN_EVICT, os.X_OK):
        raise SystemExit(f"Falta {BIN_EVICT}: compílalo con «make mig-bin».")
    resumen = Counter()
    lote_max = int(cfg.lote_gb * GB)
    filas = {r["ruta"]: dict(r) for r in filas_por_rutas(conn, rutas)}
    orden = [r for r in rutas if r in filas]
    lotes, actual, peso = [], [], 0
    for r in orden:
        t = filas[r]["tamano"] or 0
        if actual and peso + t > lote_max:
            lotes.append(actual)
            actual, peso = [], 0
        actual.append(r)
        peso += t
    if actual:
        lotes.append(actual)
    total = len(orden)
    hecho = 0
    for n_lote, lote in enumerate(lotes, 1):
        peso = sum(filas[r]["tamano"] or 0 for r in lote)
        log(f"\n— Lote {n_lote}/{len(lotes)}: {len(lote)} ficheros, {gb(peso, 2)}")
        libre = libre_mac(cfg)
        if libre - peso < cfg.min_libre_mac_gb * GB:
            raise SystemExit(f"PARADA: en la Mac quedan {gb(libre)} libres; materializar este lote bajaría "
                             f"de {num(cfg.min_libre_mac_gb)} GB. Libera espacio y relanza (se reanuda).")
        # 1. materializar + analizar (en paralelo)
        t0 = time.time()
        por_analizar = [r for r in lote if filas[r]["estado"] == "pendiente"]
        resultados = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=cfg.hilos) as ex:
            futuros = {ex.submit(analizar, cfg, filas[r]): r for r in por_analizar}
            for fut in concurrent.futures.as_completed(futuros):
                r = futuros[fut]
                try:
                    resultados[r] = fut.result()
                except Exception as e:  # un fichero raro no tumba la tanda
                    resultados[r] = {"ruta": r, "error": f"fallo inesperado: {type(e).__name__}: {e}"}
                hecho += 1
                res = resultados[r]
                estado = "error" if res.get("error") else (res.get("integridad") or "?")
                log(f"  [{hecho:>4}/{total}] {mb(filas[r]['tamano']):>9}  {res.get('formato') or '':<4} "
                    f"{estado:<7} {num(res.get('segundos', 0), 1):>5} s  {filas[r]['ruta_nfc']}")
                if res.get("error"):
                    log(f"         {res['error']}")
        tiempos["materializar_analizar"] = tiempos.get("materializar_analizar", 0) + time.time() - t0
        # 2. decidir en orden alfabético: rotos, duplicados, nombre final
        for r in por_analizar:
            res = resultados[r]
            fila = filas[r]
            if res.get("error"):
                actualizar(conn, r, error=res["error"])
                resumen["error_lectura"] += 1
                continue
            comunes = dict(md5=res["md5"], formato=res["formato"], solido=res.get("solido"),
                           integridad=res["integridad"], entradas=res.get("entradas"),
                           imagenes=res.get("imagenes"), paginas=res.get("paginas"), nota=res.get("nota"),
                           portada=res.get("portada"), estaba_local=res.get("estaba_local", 0),
                           tanda=tanda_id, error=None)
            if res["integridad"] == "rota":
                actualizar(conn, r, estado="apartado_roto", error=res.get("error_integridad"),
                           **{k: v for k, v in comunes.items() if k != "error"})
                resumen["apartado_roto"] += 1
                log(f"    apartado (roto): {fila['ruta_nfc']} — {res.get('error_integridad')}")
                continue
            dup = conn.execute(
                "SELECT ruta FROM ficheros WHERE md5=? AND ruta<>? AND estado IN "
                "('pendiente','subido','verificado','desalojado') AND (estado<>'pendiente' OR tanda IS NOT NULL) "
                "AND integridad IS NOT NULL ORDER BY ruta LIMIT 1", (res["md5"], r)).fetchone()
            if dup:
                actualizar(conn, r, estado="apartado_duplicado", duplicado_de=dup["ruta"], **comunes)
                resumen["apartado_duplicado"] += 1
                log(f"    apartado (duplicado de {nombres.ruta_normalizada(dup['ruta'])}): {fila['ruta_nfc']}")
                continue
            ext = formatos.EXT_REAL[res["formato"]]
            destino = fila["destino"]
            if destino and not destino.endswith(ext):
                viejo = os.path.splitext(destino)[1]
                destino = (destino[: -len(viejo)] if viejo else destino) + ext
            otro = conn.execute("SELECT ruta FROM ficheros WHERE destino=? COLLATE NOCASE AND ruta<>? AND estado "
                                "NOT IN ('apartado_duplicado','apartado_roto','ignorado')", (destino, r)).fetchone()
            extra = {}
            if otro:
                limpio = nombres.limpiar(_stem(os.path.basename(fila["ruta_nfc"])))
                base_dir = os.path.dirname(destino)
                n = 1
                while True:
                    base = limpio + (f" [{n}]" if n > 1 else "")
                    cand = f"{base_dir}/{nombres.recortar_nombre(nombres.componente_seguro(base), ext)}"
                    if not conn.execute("SELECT 1 FROM ficheros WHERE destino=? COLLATE NOCASE AND ruta<>?",
                                        (cand, r)).fetchone():
                        break
                    n += 1
                destino = cand
                extra = dict(interpretado=0, motivo="choque de nombres: se conserva el nombre limpio")
            if ext != ext_declarada(os.path.basename(fila["ruta_nfc"])):
                comunes["nota"] = "; ".join(x for x in (comunes.get("nota"),
                                            f"extensión corregida a {ext} (era {res['formato']} disfrazado)") if x)
            actualizar(conn, r, destino=destino, **comunes, **extra)
            filas[r].update(destino=destino, **comunes)
        # 3. subir
        subir, destinos = [], {}
        for r in lote:
            row = conn.execute("SELECT estado, md5, destino FROM ficheros WHERE ruta=?", (r,)).fetchone()
            if row["estado"] in ("pendiente", "subido") and row["md5"] and row["destino"]:
                subir.append(r)
                destinos[r] = row["destino"]
        if subir:
            t0 = time.time()
            limpiar_subida(cfg)
            lista = os.path.join(cfg.dir_estado, "lote-subida.txt")
            with open(lista, "w", encoding="utf-8") as fl:
                for r in subir:
                    d = destinos[r]
                    enlace = os.path.join(cfg.dir_subida, d)
                    os.makedirs(os.path.dirname(enlace), exist_ok=True)
                    os.symlink(os.path.join(cfg.origen, r), enlace)
                    fl.write(d + "\n")
            peso_sub = sum(filas[r]["tamano"] for r in subir)
            log(f"  Subiendo {len(subir)} ficheros ({gb(peso_sub, 2)}) a {cfg.remoto} …")
            # --files-from-raw: cada línea es una ruta tal cual (sin comentarios «#» ni recortes)
            rc, _, err = rclone(["copy", cfg.dir_subida, cfg.remoto, "--files-from-raw", lista, "--copy-links"]
                                + RCLONE_SUBIDA, log, mostrar=True)
            tiempos["subir"] = tiempos.get("subir", 0) + time.time() - t0
            tiempos["bytes_subidos"] = tiempos.get("bytes_subidos", 0) + peso_sub
            if rc != 0:
                log(f"  rclone copy terminó con código {rc}; se verifica lo que llegó.")
                if "upload limit" in err.lower() or "uploadlimit" in err.lower():
                    log("  Google cortó por el límite diario de subida (750 GB/día). Relanza mañana.")
            limpiar_subida(cfg)
            # 4. verificar contra Drive
            t0 = time.time()
            en_drive = listar_drive(cfg, log, {os.path.dirname(destinos[r]) for r in subir})
            reintentar = []
            for r in subir:
                fila = conn.execute("SELECT * FROM ficheros WHERE ruta=?", (r,)).fetchone()
                d = en_drive.get(fila["destino"])
                if not d:
                    actualizar(conn, r, error="no aparece en Drive tras la subida")
                    resumen["no_subido"] += 1
                    continue
                if d["tam"] != fila["tamano"]:
                    actualizar(conn, r, estado="subido", error=f"tamaño distinto en Drive ({d['tam']})",
                               drive_id=d["id"])
                    reintentar.append(r)
                    continue
                if d["md5"] != fila["md5"]:
                    actualizar(conn, r, estado="subido", md5_drive=d["md5"], drive_id=d["id"],
                               error=f"md5 distinto en Drive ({d['md5']})")
                    reintentar.append(r)
                    continue
                actualizar(conn, r, estado="verificado", md5_drive=d["md5"], drive_id=d["id"], error=None)
                resumen["verificado"] += 1
            for r in reintentar:
                fila = conn.execute("SELECT * FROM ficheros WHERE ruta=?", (r,)).fetchone()
                log(f"  Reintento (md5 o tamaño no cuadra): {fila['destino']}")
                rc, _, _ = rclone(["copyto", os.path.join(cfg.origen, r), f"{cfg.remoto}/{fila['destino']}",
                                   "--ignore-times"] + RCLONE_SUBIDA, log, mostrar=True)
                d = listar_drive(cfg, log, {os.path.dirname(fila["destino"])}).get(fila["destino"])
                if d and d["md5"] == fila["md5"] and d["tam"] == fila["tamano"]:
                    actualizar(conn, r, estado="verificado", md5_drive=d["md5"], drive_id=d["id"], error=None)
                    resumen["verificado"] += 1
                else:
                    resumen["md5_distinto"] += 1
            tiempos["verificar"] = tiempos.get("verificar", 0) + time.time() - t0
        # 5. desalojar: verificados + apartados que materializamos nosotros
        t0 = time.time()
        a_desalojar = []
        for r in lote:
            fila = conn.execute("SELECT estado, estaba_local, tanda FROM ficheros WHERE ruta=?", (r,)).fetchone()
            if fila["estado"] == "verificado":
                a_desalojar.append(r)
            elif fila["estado"] in ("apartado_duplicado", "apartado_roto") and not fila["estaba_local"] \
                    and fila["tanda"] == tanda_id:
                a_desalojar.append(r)
        if a_desalojar:
            res = desalojar(cfg, [os.path.join(cfg.origen, r) for r in a_desalojar])
            for r in a_desalojar:
                p = os.path.join(cfg.origen, r)
                st = os.lstat(p)
                estado = conn.execute("SELECT estado FROM ficheros WHERE ruta=?", (r,)).fetchone()["estado"]
                if res.get(p) == "ok":
                    if estado == "verificado":
                        actualizar(conn, r, estado="desalojado",
                                   nota=None if st.st_blocks == 0 else f"evict ok, {st.st_blocks} bloques aún")
                        resumen["desalojado"] += 1
                else:
                    actualizar(conn, r, error=f"no se pudo desalojar: {res.get(p)}")
                    resumen["desalojo_fallido"] += 1
            log(f"  Desalojados {sum(1 for r in a_desalojar if res.get(os.path.join(cfg.origen, r)) == 'ok')} "
                f"de {len(a_desalojar)} (la copia sigue en iCloud).")
        tiempos["desalojar"] = tiempos.get("desalojar", 0) + time.time() - t0
    return resumen


# ==========================================================================
# Índice y lista de revisión
# ==========================================================================

def cargar_meta_series(cfg: Config) -> tuple[dict, dict]:
    por_ruta, por_serie = {}, {}
    try:
        with open(cfg.series_meta, encoding="utf-8") as f:
            for linea in f:
                linea = linea.strip()
                if not linea:
                    continue
                try:
                    d = json.loads(linea)
                except ValueError:
                    continue
                if d.get("ruta"):
                    por_ruta[nombres.ruta_normalizada(d["ruta"])] = d
                s = d.get("serie_canonica") or d.get("serie")
                if s:
                    por_serie[nombres.clave(s)] = d
    except FileNotFoundError:
        pass
    return por_ruta, por_serie


def generar_indice(cfg: Config, conn: sqlite3.Connection, log: Registro) -> dict:
    os.makedirs(cfg.dir_indice, exist_ok=True)
    por_ruta, por_serie = cargar_meta_series(cfg)
    filas = conn.execute("SELECT * FROM ficheros WHERE estado IN ('verificado','desalojado') "
                         "ORDER BY destino").fetchall()
    tmp = os.path.join(cfg.dir_indice, "indice.jsonl.tmp")
    series = set()
    with open(tmp, "w", encoding="utf-8") as f:
        for r in filas:
            linea = {
                "md5": r["md5"], "ruta": r["destino"], "nombre": os.path.basename(r["destino"]),
                "drive_id": r["drive_id"], "categoria": r["categoria"], "editorial": r["editorial"] or None,
                "serie": r["serie"], "numero": r["numero"], "anio": r["anio"], "formato": r["formato"],
                "tamano": r["tamano"], "paginas": r["paginas"], "solido": bool(r["solido"]),
                "portada": r["portada"], "original": r["ruta_nfc"],
            }
            meta = por_ruta.get(r["serie_origen"] or "") or por_serie.get(nombres.clave(r["serie"] or ""))
            if meta:
                linea["meta_serie"] = {k: v for k, v in meta.items() if k != "ruta"}
            series.add((r["categoria"], r["editorial"], r["serie"]))
            f.write(json.dumps(linea, ensure_ascii=False) + "\n")
    os.replace(tmp, os.path.join(cfg.dir_indice, "indice.jsonl"))
    # portadas.zip (sin comprimir: WebP ya va comprimido)
    tmpz = os.path.join(cfg.dir_indice, "portadas.zip.tmp")
    n_port = 0
    with zipfile.ZipFile(tmpz, "w", zipfile.ZIP_STORED) as z:
        for r in filas:
            if r["portada"]:
                p = os.path.join(cfg.dir_portadas, r["portada"])
                if os.path.exists(p):
                    z.write(p, r["portada"])
                    n_port += 1
    os.replace(tmpz, os.path.join(cfg.dir_indice, "portadas.zip"))
    copiados = []
    for origen, nombre in ((cfg.taxonomia, "taxonomia.json"), (cfg.series_meta, "series.jsonl")):
        if os.path.exists(origen):
            shutil.copy2(origen, os.path.join(cfg.dir_indice, nombre))
            copiados.append(nombre)
        elif os.path.exists(os.path.join(cfg.dir_indice, nombre)):
            os.unlink(os.path.join(cfg.dir_indice, nombre))
    resumen = {"generado": ahora(), "version": 1, "ficheros": len(filas),
               "bytes": sum(r["tamano"] for r in filas), "series": len(series), "portadas": n_port,
               "con_taxonomia": "taxonomia.json" in copiados, "con_metadatos": "series.jsonl" in copiados}
    with open(os.path.join(cfg.dir_indice, "resumen.json"), "w", encoding="utf-8") as f:
        json.dump(resumen, f, ensure_ascii=False, indent=1)
    log(f"Índice: {num(len(filas))} cómics, {num(len(series))} series, {num(n_port)} portadas"
        + (f"; con {', '.join(copiados)}" if copiados else "") + ".")
    return resumen


def subir_indice(cfg: Config, log: Registro) -> int:
    t0 = time.time()
    rc, _, err = rclone(["copy", cfg.dir_indice, f"{cfg.remoto}/.indice", "--exclude", "*.tmp"]
                        + RCLONE_SUBIDA, log, mostrar=True)
    if rc == 0:
        log(f"Índice subido a {cfg.remoto}/.indice en {duracion(time.time() - t0)}.")
    else:
        log(f"AVISO: no se pudo subir el índice (código {rc}). Repite con «make mig-indice».")
    return rc


def escribir_revision(cfg: Config, conn: sqlite3.Connection, log: Registro | None = None) -> str:
    os.makedirs(cfg.dir_revisar, exist_ok=True)
    filas = [dict(r) for r in conn.execute("SELECT * FROM ficheros ORDER BY ruta_nfc")]
    secciones = []

    def seccion(titulo, explicacion, cabecera, items):
        secciones.append((titulo, explicacion, cabecera, items))

    seccion("Apartados por duplicado (mismo md5)", "No se suben; el original sí.",
            ("Fichero", "Es copia de"),
            [(r["ruta_nfc"], nombres.ruta_normalizada(r["duplicado_de"] or "")) for r in filas
             if r["estado"] == "apartado_duplicado"])
    seccion("Apartados por rotos o vacíos", "No se suben. Siguen intactos en iCloud.",
            ("Fichero", "Motivo"),
            [(r["ruta_nfc"], r["error"] or "") for r in filas if r["estado"] == "apartado_roto"])
    seccion("No son cómics", "Imágenes sueltas y otros ficheros: no se suben.", ("Fichero", "Nota"),
            [(r["ruta_nfc"], r["nota"] or "") for r in filas if r["clase"] == "no_comic"])
    por_serie = defaultdict(list)
    for r in filas:
        if r["clase"] == "comic" and r["fuente"] == "heuristica":
            por_serie[(r["serie_origen"], r["categoria"], r["editorial"], r["serie"])].append(r)
    seccion("Series sin entrada en taxonomia.json (clasificadas por heurística)",
            "Comprueba categoría, editorial y nombre; al llegar la taxonomía se usa la suya.",
            ("Carpeta de origen", "Destino", "Ficheros"),
            [(k[0], "/".join(x for x in (k[1], k[2], k[3]) if x), str(len(v)))
             for k, v in sorted(por_serie.items(), key=lambda kv: nombres.clave_ruta(kv[0][0] or ""))])
    seccion("Nombres que no se interpretan con seguridad", "Se conserva el nombre limpio (NFC, sin leet).",
            ("Fichero", "Nombre en Drive", "Motivo"),
            [(r["ruta_nfc"], os.path.basename(r["destino"] or ""), r["motivo"] or "") for r in filas
             if r["clase"] == "comic" and not r["interpretado"] and r["estado"] not in
             ("apartado_duplicado", "apartado_roto")])
    seccion("Comprobación parcial o dudosa", "Se suben igual; conviene abrirlos en la tablet.",
            ("Fichero", "Integridad", "Nota"),
            [(r["ruta_nfc"], r["integridad"], r["nota"] or "") for r in filas
             if r["integridad"] in ("parcial", "dudosa") or (r["nota"] or "").startswith("sin imágenes")])
    seccion("Sin portada", "La app mostrará una tarjeta vacía.", ("Fichero", "Nota"),
            [(r["ruta_nfc"], r["nota"] or "") for r in filas if r["estado"] in EN_DRIVE and not r["portada"]])
    seccion("Errores pendientes", "Se reintentan en la siguiente tanda.", ("Fichero", "Estado", "Error"),
            [(r["ruta_nfc"], r["estado"], r["error"]) for r in filas if r["error"]
             and r["estado"] not in ("apartado_roto",)])

    ruta_md = os.path.join(cfg.dir_revisar, "REVISION.md")
    with open(ruta_md + ".tmp", "w", encoding="utf-8") as f:
        f.write(f"# Lista de revisión de la migración\n\nGenerada {ahora()} desde la base local "
                f"({cfg.db}). Se rehace en cada tanda; no la edites a mano.\n\n")
        f.write("| Sección | Ficheros o series |\n|---|---|\n")
        for t, _, _, items in secciones:
            f.write(f"| {t} | {num(len(items))} |\n")
        for t, expl, cab, items in secciones:
            f.write(f"\n## {t} ({num(len(items))})\n\n{expl}\n\n")
            if not items:
                f.write("Ninguno.\n")
                continue
            f.write("| " + " | ".join(cab) + " |\n|" + "---|" * len(cab) + "\n")
            for it in items:
                f.write("| " + " | ".join(str(x).replace("|", "\\|") for x in it) + " |\n")
    os.replace(ruta_md + ".tmp", ruta_md)
    with open(os.path.join(cfg.dir_revisar, "revision.tsv"), "w", encoding="utf-8") as f:
        f.write("seccion\tcampo1\tcampo2\tcampo3\n")
        for t, _, _, items in secciones:
            for it in items:
                f.write(t + "\t" + "\t".join(str(x) for x in it) + "\n")
    if log:
        log.solo_log(f"lista de revisión: {ruta_md}")
    return ruta_md


# ==========================================================================
# Subcomandos
# ==========================================================================

def cmd_plan(cfg: Config, args) -> int:
    log = Registro(cfg, "plan")
    t0 = time.time()
    conn = abrir_db(cfg) if os.path.exists(cfg.db) else None
    censo = censar(cfg)
    plan = Plan(cfg, censo, conn)
    comics = [f for f in censo if f["clase"] == "comic"]
    ign = [f for f in censo if f["clase"] == "ignorado"]
    nocom = [f for f in censo if f["clase"] == "no_comic"]
    total_b = sum(f["tamano"] for f in censo)
    comics_b = sum(f["tamano"] for f in comics)
    locales = [f for f in censo if f["bloques"] > 0 and not f["sin_datos"]]
    no_nfc = sum(1 for f in censo if nombres.nfc(f["ruta"]) != f["ruta"])
    bordes = sum(1 for f in censo if any(c != c.strip() for c in f["ruta"].split("/")))
    dobles = sum(1 for f in censo if "  " in f["ruta"])
    cero = [f for f in comics if f["tamano"] == 0]
    ext = Counter(ext_declarada(os.path.basename(f["ruta_nfc"])) for f in comics)
    # cabeceras solo de los ya materializados (leer uno desalojado lo descargaría)
    cab = Counter()
    disfrazados = []
    for f in comics:
        if f["bloques"] > 0 and not f["sin_datos"]:
            try:
                fmt, sol = formatos.detectar(formatos.leer_cabecera(os.path.join(cfg.origen, f["ruta"])))
            except OSError:
                continue
            cab[fmt + (" sólido" if sol else "")] += 1
            if fmt in formatos.EXT_REAL and formatos.EXT_REAL[fmt] != ext_declarada(os.path.basename(f["ruta_nfc"])):
                disfrazados.append((f["ruta_nfc"], fmt))
    por_tam = defaultdict(list)
    for f in comics:
        if f["tamano"] > 0:
            por_tam[f["tamano"]].append(f)
    dup_grupos = [g for g in por_tam.values() if len(g) > 1]
    dup_b = sum(sum(f["tamano"] for f in g[1:]) for g in dup_grupos)
    # series
    carpetas = {k: v for k, v in plan.series.items()}
    n_series = sum(1 for v in carpetas.values() if not v["suelto"])
    n_sueltos = sum(1 for v in carpetas.values() if v["suelto"])
    heur = [v for v in carpetas.values() if v["fuente"] == "heuristica"]
    no_interp = [f for f in comics if not plan.nombres[f["ruta"]]["interpretado"]]

    def clave_info(f):
        return "file:" + f["ruta_nfc"] if info_serie(plan.series, f)["suelto"] else info_serie(plan.series, f)["carpeta"]

    series_no_interp = {clave_info(f) for f in no_interp}
    series_revision = {k for k, v in carpetas.items() if v["fuente"] == "heuristica"} | series_no_interp
    motivos = Counter(plan.nombres[f["ruta"]]["motivo"] for f in no_interp)
    destinos = defaultdict(lambda: [0, 0])
    for f in comics:
        si = info_serie(plan.series, f)
        k = si["categoria"] + (f"/{si['editorial']}" if si["editorial"] else "")
        destinos[k][0] += 1
        destinos[k][1] += f["tamano"]
    estados_db = Counter()
    if conn is not None:
        for r in conn.execute("SELECT estado, COUNT(*) n FROM ficheros GROUP BY estado"):
            estados_db[r["estado"]] = r["n"]
    pend = plan.pendientes()
    pend_b = sum(f["tamano"] for f in pend)
    limite = int(cfg.tanda_gb * GB)
    tanda1 = elegir_tanda(pend, limite)
    tanda1_b = sum(f["tamano"] for f in tanda1)
    n_tandas = 0
    resto = list(pend)
    while resto:
        t = elegir_tanda(resto, limite)
        if not t:
            break
        hechos = {f["ruta"] for f in t}
        resto = [f for f in resto if f["ruta"] not in hechos]
        n_tandas += 1
    drive = None
    if not args.sin_drive:
        try:
            drive = cuota(cfg, log)
        except SystemExit as e:
            log(str(e))

    L = []
    p = L.append
    p(f"# Plan de migración — {dt.datetime.now():%d-%m-%Y %H:%M}")
    p("")
    p("Solo lectura: lstat y, de los ficheros que ya estaban en la Mac, sus primeros bytes. "
      "No se ha materializado ni subido nada.")
    p("")
    p(f"- Origen: `{cfg.origen}` (la raíz tiene espacio final; en Drive será «Cómics»)")
    p(f"- Ficheros: **{num(len(censo))}** ({gb(total_b)}) — cómics {num(len(comics))} ({gb(comics_b)}), "
      f"ignorados {num(len(ign))} (.DS_Store y ocultos), no cómics {num(len(nocom))} (imágenes sueltas)")
    p(f"- En la Mac ahora: {num(len(locales))} materializados ({gb(sum(f['tamano'] for f in locales))}); "
      f"el resto solo en iCloud")
    p(f"- Por extensión: " + " · ".join(f"{e or '(sin)'} {num(n)}" for e, n in ext.most_common()))
    if cab:
        p(f"- Cabecera real (solo de los {num(sum(cab.values()))} cómics ya locales): "
          + " · ".join(f"{k} {num(n)}" for k, n in cab.most_common())
          + f"; disfrazados {num(len(disfrazados))} (se corrige la extensión al subir)")
    p(f"- Normalización: {num(no_nfc)} rutas no están en NFC; {num(bordes)} con espacios al borde de algún "
      f"componente; {num(dobles)} con espacios dobles")
    p(f"- 0 bytes: {num(len(cero))} (se apartan como rotos)")
    p(f"- Posibles duplicados por tamaño: {num(len(dup_grupos))} grupos, "
      f"{num(sum(len(g) - 1 for g in dup_grupos))} copias ({gb(dup_b)}); se confirman por md5 al materializar")
    if estados_db:
        p(f"- Ya en la base local: " + " · ".join(f"{k} {num(v)}" for k, v in estados_db.most_common()))
    p("")
    p("## Series y nombres")
    p("")
    p(f"- Carpetas-serie: {num(n_series)}; ficheros sueltos (raíz o carpeta de editorial): {num(n_sueltos)}")
    p(f"- taxonomia.json: {'sí, ' + num(len(plan.taxonomia)) + ' entradas' if plan.taxonomia else 'todavía no existe'}"
      f" → por heurística (a revisión): {num(len(heur))}")
    p(f"- Nombres interpretados: {num(len(comics) - len(no_interp))} de {num(len(comics))} "
      f"({num(100 * (len(comics) - len(no_interp)) / max(1, len(comics)), 1)} %); "
      f"se conserva el nombre limpio en {num(len(no_interp))}")
    p("  - motivos: " + " · ".join(f"{m} {num(n)}" for m, n in motivos.most_common()))
    p(f"- Series que irían a la lista de revisión: **{num(len(series_revision))}** "
      f"(sin taxonomía {num(len(heur))}; con algún nombre sin interpretar {num(len(series_no_interp))})")
    p("")
    p("## Destino en Drive")
    p("")
    p("| Carpeta | Ficheros | Tamaño |\n|---|---|---|")
    for k, (n, b) in sorted(destinos.items(), key=lambda kv: -kv[1][1]):
        p(f"| {k} | {num(n)} | {gb(b)} |")
    p("")
    p("## Tandas")
    p("")
    p(f"- Pendientes: {num(len(pend))} ficheros, {gb(pend_b)}; tanda de ~{num(cfg.tanda_gb)} GB → "
      f"{num(n_tandas)} tandas en orden alfabético por ruta")
    if tanda1:
        p(f"- Primera tanda: **{num(len(tanda1))} ficheros, {gb(tanda1_b)}**, de «{tanda1[0]['ruta_nfc']}» "
          f"a «{tanda1[-1]['ruta_nfc']}»")
    if drive:
        libre = int(drive.get("free", 0))
        reserva = int(cfg.reserva_gib * GiB)
        cabe = max(0, libre - reserva)
        p(f"- Drive: {gib(libre)} libres de {gib(drive.get('total', 0))}; con la reserva de "
          f"{num(cfg.reserva_gib)} GiB caben {gb(cabe)} → ~{num(min(n_tandas, int(cabe // limite)))} tandas")
        falta = pend_b - cabe
        if falta > 0:
            necesita = int(drive.get("used", 0)) + pend_b + reserva
            p(f"- Para la biblioteca entera faltan {gb(falta)}: hacen falta {gib(necesita)} en total "
              f"(uso actual + pendiente + reserva) → el plan de 200 GB no basta; el siguiente es 2 TB "
              f"(o menos si los duplicados reales son muchos)")
    p("")
    p("## Nombres sin interpretar (muestra)")
    p("")
    for f in no_interp[:60]:
        nm = plan.nombres[f["ruta"]]
        p(f"- `{f['ruta_nfc']}` → `{os.path.basename(nm['destino'])}` ({nm['motivo']})")
    p("")
    p("## Ejemplos de nombres interpretados")
    p("")
    vistos = set()
    for f in comics:
        nm = plan.nombres[f["ruta"]]
        c = os.path.dirname(f["ruta_nfc"])
        if nm["interpretado"] and c not in vistos:
            vistos.add(c)
            p(f"- `{f['ruta_nfc']}` → `{nm['destino']}`")
        if len(vistos) >= 40:
            break
    p("")
    p("## Series a revisión")
    p("")
    for k in sorted(series_revision, key=lambda x: nombres.clave_ruta(x.replace("file:", ""))):
        v = plan.series.get(k)
        destino = "/".join(x for x in (v["categoria"], v["editorial"], v["serie"]) if x) if v else "?"
        p(f"- `{k.replace('file:', '')}` → {destino}")
    texto = "\n".join(L) + "\n"
    ruta = os.path.join(cfg.dir_logs, f"plan-{dt.datetime.now():%Y%m%d-%H%M}.md")
    with open(ruta, "w", encoding="utf-8") as f:
        f.write(texto)
    # en pantalla, el resumen (hasta la muestra)
    print(texto.split("## Nombres sin interpretar")[0].rstrip())
    print(f"\nPlan completo (con las series a revisión): {ruta}  ({duracion(time.time() - t0)})")
    return 0


def preparar(cfg: Config, log: Registro):
    conn = abrir_db(cfg)
    log("Censo de la biblioteca (lstat, sin descargar nada)…")
    censo = censar(cfg)
    plan = Plan(cfg, censo, conn)
    sincronizar_censo(conn, plan, log)
    log(f"  {num(len(censo))} ficheros; {num(sum(1 for f in censo if f['clase'] == 'comic'))} cómics.")
    if plan.taxonomia:
        log(f"  taxonomia.json: {num(len(plan.taxonomia))} entradas.")
    else:
        log("  taxonomia.json todavía no existe: se clasifica por heurística (va a revisión).")
    return conn, plan


def nueva_tanda(conn, tipo: str, alcance: str, rutas: list[str], bytes_: int) -> int:
    cur = conn.execute("INSERT INTO tandas (tipo, alcance, inicio, bytes, ficheros) VALUES (?,?,?,?,?)",
                       (tipo, alcance, ahora(), bytes_, len(rutas)))
    tid = cur.lastrowid
    conn.executemany("UPDATE ficheros SET tanda=? WHERE ruta=?", [(tid, r) for r in rutas])
    conn.commit()
    return tid


def cerrar_tanda(conn, tid: int, resumen: Counter):
    conn.execute("UPDATE tandas SET fin=?, resultado=? WHERE id=?",
                 (ahora(), json.dumps(dict(resumen), ensure_ascii=False), tid))
    conn.commit()


def cmd_tanda(cfg: Config, args) -> int:
    cfg.crear_dirs()
    log = Registro(cfg)
    with Cerrojo(cfg):
        mantener_despierto()
        t_ini = time.time()
        conn, plan = preparar(cfg, log)
        abierta = conn.execute("SELECT * FROM tandas WHERE fin IS NULL AND tipo='tanda' ORDER BY id LIMIT 1").fetchone()
        if abierta:
            tid = abierta["id"]
            rutas = [r["ruta"] for r in conn.execute(
                "SELECT ruta FROM ficheros WHERE tanda=? AND estado IN ('pendiente','subido','verificado')", (tid,))]
            rutas.sort(key=lambda r: nombres.clave_ruta(nombres.ruta_normalizada(r)))
            log(f"\nReanudo la tanda {tid} (empezada {abierta['inicio']}): quedan {num(len(rutas))} ficheros.")
        else:
            pend = plan.pendientes()
            elegidos = elegir_tanda(pend, int(cfg.tanda_gb * GB))
            if not elegidos:
                log("No queda nada pendiente: la biblioteca entera está migrada (o apartada).")
                return 0
            rutas = [f["ruta"] for f in elegidos]
            tid = None
        bytes_tanda = sum(r["tamano"] for r in filas_por_rutas(conn, rutas, "tamano, estado")
                          if r["estado"] == "pendiente")
        comprobar_cuota(cfg, log, bytes_tanda)
        if tid is None:
            tid = nueva_tanda(conn, "tanda", "alfabético", rutas, bytes_tanda)
            primero = nombres.ruta_normalizada(rutas[0])
            ultimo = nombres.ruta_normalizada(rutas[-1])
            log(f"\nTanda {tid}: {num(len(rutas))} ficheros, {gb(bytes_tanda)}, de «{primero}» a «{ultimo}».")
        tiempos = {}
        resumen = procesar(cfg, conn, log, tid, rutas, tiempos)
        quedan = conn.execute("SELECT COUNT(*) FROM ficheros WHERE tanda=? AND estado IN ('pendiente','subido','verificado')",
                              (tid,)).fetchone()[0]
        # procesar() llegó al final: la tanda se cierra aunque queden errores (se reintentan en la
        # siguiente). Solo una interrupción (Ctrl-C, apagón, parada por cuota o disco) la deja abierta.
        cerrar_tanda(conn, tid, resumen)
        generar_indice(cfg, conn, log)
        escribir_revision(cfg, conn, log)
        subir_indice(cfg, log)
        log("\n" + resumen_texto(resumen, tiempos, time.time() - t_ini))
        if quedan:
            log(f"Quedan {num(quedan)} ficheros de esta tanda con errores (ver la lista de revisión); "
                "la siguiente «make migrar» los reintenta.")
        log(f"Lista de revisión: {os.path.join(cfg.dir_revisar, 'REVISION.md')}")
        return 0 if quedan == 0 else 1


def resumen_texto(resumen: Counter, tiempos: dict, total: float) -> str:
    partes = [f"{k.replace('_', ' ')} {num(v)}" for k, v in resumen.most_common()]
    t = [f"{k.replace('_', ' ')} {duracion(v)}" for k, v in tiempos.items() if k != "bytes_subidos"]
    s = "Resultado: " + (" · ".join(partes) or "nada que hacer") + f"\nTiempos: {' · '.join(t)} · total {duracion(total)}"
    if tiempos.get("bytes_subidos") and tiempos.get("subir"):
        s += f"\nVelocidad de subida: {mb(tiempos['bytes_subidos'] / tiempos['subir'])}/s"
    return s


def cmd_humo(cfg: Config, args) -> int:
    cfg.crear_dirs()
    log = Registro(cfg, "humo")
    carpeta = args.carpeta
    if not carpeta:
        try:
            with open(os.path.join(cfg.dir_estado, "humo.txt"), encoding="utf-8") as f:
                carpeta = f.read().strip()
        except FileNotFoundError:
            raise SystemExit("Indica la carpeta: make mig-humo HUMO='<carpeta relativa a Cómics>'")
    carpeta = carpeta.strip("/")
    with Cerrojo(cfg):
        mantener_despierto()
        t_ini = time.time()
        tiempos = {}
        conn, plan = preparar(cfg, log)
        objetivo = nombres.ruta_normalizada(carpeta)
        filas = [r for r in conn.execute("SELECT * FROM ficheros WHERE clase='comic'")
                 if r["ruta_nfc"].startswith(objetivo + "/")]
        filas.sort(key=lambda r: nombres.clave_ruta(r["ruta_nfc"]))
        if not filas:
            raise SystemExit(f"No hay cómics en {carpeta!r}.")
        rutas = [r["ruta"] for r in filas]
        antes = {}
        for r in rutas:
            st = os.lstat(os.path.join(cfg.origen, r))
            antes[r] = {"bloques": st.st_blocks, "local": es_local(st)}
        log(f"\nPRUEBA DE HUMO sobre «{objetivo}»: {len(filas)} ficheros, "
            f"{mb(sum(r['tamano'] for r in filas))}; materializados ahora: "
            f"{sum(1 for v in antes.values() if v['local'])}.")
        pendientes = [r["ruta"] for r in filas if r["estado"] in ("pendiente", "subido", "verificado")]
        bytes_ = sum(r["tamano"] for r in filas if r["estado"] == "pendiente")
        c = comprobar_cuota(cfg, log, bytes_)
        tid = nueva_tanda(conn, "humo", objetivo, pendientes, bytes_)
        resumen = Counter()
        if pendientes:
            resumen = procesar(cfg, conn, log, tid, pendientes, tiempos)
        else:
            log("Todos sus ficheros ya estaban migrados: solo se comprueba.")
        t0 = time.time()
        generar_indice(cfg, conn, log)
        escribir_revision(cfg, conn, log)
        rc_ind = subir_indice(cfg, log)
        tiempos["indice"] = time.time() - t0
        cerrar_tanda(conn, tid, resumen)
        # comprobaciones independientes
        log("\nComprobaciones:")
        t0 = time.time()
        filas = [dict(conn.execute("SELECT * FROM ficheros WHERE ruta=?", (r,)).fetchone()) for r in rutas]
        rc1, lsl, _ = rclone(["lsl", cfg.remoto], log, timeout=600)
        dirs = sorted({os.path.dirname(r["destino"]) for r in filas if r["destino"]})
        md5_drive = {}
        md5_salida = []
        for d in dirs:
            rc2, salida, _ = rclone(["md5sum", f"{cfg.remoto}/{d}"], log, timeout=600)
            md5_salida.append(f"$ rclone md5sum {cfg.remoto}/{d}\n{salida.rstrip()}")
            for linea in salida.splitlines():
                if "  " in linea:
                    h, nombre = linea.split("  ", 1)
                    md5_drive[f"{d}/{nombre}"] = h
        rc3, lsl_ind, _ = rclone(["lsl", f"{cfg.remoto}/.indice"], log, timeout=300)
        ev = subprocess.run([BIN_EVICT, "--estado"] + [os.path.join(cfg.origen, r) for r in rutas],
                            capture_output=True, text=True)
        estado_icloud = {}
        for linea in ev.stdout.splitlines():
            p = linea.split("\t")
            if p[0] == "estado":
                estado_icloud[os.path.relpath(p[1], cfg.origen)] = (p[3], p[4])
        tiempos["comprobar"] = time.time() - t0
        ok_md5 = sum(1 for r in filas if r["md5"] and md5_drive.get(r["destino"]) == r["md5"])
        desalojados = sum(1 for r in rutas if estado_icloud.get(r, ("", "?"))[1] == "0")
        en_indice = 0
        try:
            with open(os.path.join(cfg.dir_indice, "indice.jsonl"), encoding="utf-8") as f:
                destinos = {r["destino"] for r in filas}
                en_indice = sum(1 for linea in f if json.loads(linea)["ruta"] in destinos)
        except FileNotFoundError:
            pass
        n_port = sum(1 for r in filas if r["portada"] and os.path.exists(os.path.join(cfg.dir_portadas, r["portada"])))
        total = time.time() - t_ini
        exito = ok_md5 == len(filas) and desalojados == len(filas) and en_indice == len(filas) and rc_ind == 0
        log(f"  md5 local = md5 de Drive: {ok_md5}/{len(filas)}")
        log(f"  desalojados en la Mac (0 bloques): {desalojados}/{len(filas)}")
        log(f"  en el índice: {en_indice}/{len(filas)} · portadas: {n_port}/{len(filas)}")
        log(f"  .indice subido: {'sí' if rc_ind == 0 else 'NO'}")
        log("\n" + resumen_texto(resumen, tiempos, total))
        log("PRUEBA DE HUMO: " + ("SUPERADA" if exito else "CON FALLOS (ver informe)"))
        informe = informe_humo(cfg, objetivo, filas, antes, estado_icloud, md5_drive, lsl, lsl_ind,
                               md5_salida, resumen, tiempos, total, exito, c, n_port, en_indice, rc_ind)
        log(f"Informe: {informe}")
        return 0 if exito else 1


def informe_humo(cfg, objetivo, filas, antes, estado_icloud, md5_drive, lsl, lsl_ind, md5_salida,
                 resumen, tiempos, total, exito, cuota_ini, n_port, en_indice, rc_ind) -> str:
    ruta = os.path.join(cfg.dir_logs, f"humo-{dt.date.today():%Y-%m-%d}.md")
    L = [f"# Prueba de humo — {dt.datetime.now():%d-%m-%Y %H:%M}", "",
         f"Resultado: **{'SUPERADA' if exito else 'CON FALLOS'}**", "",
         f"- Carpeta: `{objetivo}` ({len(filas)} ficheros, {mb(sum(r['tamano'] for r in filas))})",
         f"- Destino: `{cfg.remoto}`",
         f"- Drive antes: {gib(int(cuota_ini.get('free', 0)))} libres",
         f"- md5 local = md5 de Drive: {sum(1 for r in filas if md5_drive.get(r['destino']) == r['md5'])}/{len(filas)}",
         f"- Desalojados (0 bloques en la Mac, iCloud intacto): "
         f"{sum(1 for r in filas if estado_icloud.get(r['ruta'], ('', '?'))[1] == '0')}/{len(filas)}",
         f"- Portadas: {n_port}/{len(filas)} · en el índice: {en_indice}/{len(filas)} · "
         f".indice subido: {'sí' if rc_ind == 0 else 'no'}",
         f"- Tiempos: " + " · ".join(f"{k.replace('_', ' ')} {duracion(v)}" for k, v in tiempos.items()
                                     if k != "bytes_subidos") + f" · total {duracion(total)}",
         f"- Resultado por estados: " + (" · ".join(f"{k} {v}" for k, v in resumen.items()) or "—"),
         "", "## Ficheros", "",
         "| Original | Destino | Formato | MB | md5 | Drive | Antes | Después | Portada |",
         "|---|---|---|---|---|---|---|---|---|"]
    for r in filas:
        a = antes.get(r["ruta"], {})
        d = estado_icloud.get(r["ruta"], ("?", "?"))
        L.append(f"| {os.path.basename(r['ruta_nfc'])} | {r['destino']} | {r['formato']} | "
                 f"{num(r['tamano'] / 1e6, 1)} | `{r['md5']}` | "
                 f"{'=' if md5_drive.get(r['destino']) == r['md5'] else 'DISTINTO'} | "
                 f"{'local' if a.get('local') else 'desalojado'} ({a.get('bloques')} bl.) | "
                 f"{d[0]} ({d[1]} bl.) | {r['portada'] or '—'} |")
    L += ["", "## rclone md5sum (destino)", "", "```", *md5_salida, "```",
          "", f"## rclone lsl {cfg.remoto}", "", "```", lsl.rstrip(), "```",
          "", f"## rclone lsl {cfg.remoto}/.indice", "", "```", lsl_ind.rstrip(), "```", ""]
    with open(ruta, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    return ruta


def cmd_verificar(cfg: Config, args) -> int:
    log = Registro(cfg, "verificar")
    conn = abrir_db(cfg)
    log(f"Listando {cfg.remoto} con md5 (sin descargar nada)…")
    rc, salida, err = rclone(["lsjson", cfg.remoto, "-R", "--files-only", "--hash", "--hash-type", "md5",
                              "--exclude", "/_sonda/**", "--exclude", "/.indice/**"], log, timeout=3600)
    if rc != 0:
        raise SystemExit(f"No pude listar Drive: {err.strip()[-300:]}")
    en_drive = {}
    for e in json.loads(salida or "[]"):
        hashes = {k.lower(): v for k, v in (e.get("Hashes") or {}).items()}
        en_drive[e["Path"]] = {"md5": hashes.get("md5"), "tam": e.get("Size"), "id": e.get("ID")}
    filas = [dict(r) for r in conn.execute("SELECT * FROM ficheros WHERE estado IN ('subido','verificado','desalojado')")]
    ok = faltan = distintos = promovidos = 0
    for r in filas:
        d = en_drive.get(r["destino"])
        if not d:
            faltan += 1
            actualizar(conn, r["ruta"], error="no está en Drive (verificar)")
            log(f"  FALTA en Drive: {r['destino']}")
            continue
        if d["md5"] != r["md5"] or d["tam"] != r["tamano"]:
            distintos += 1
            actualizar(conn, r["ruta"], error=f"md5/tamaño distinto en Drive ({d['md5']})", md5_drive=d["md5"])
            log(f"  DISTINTO: {r['destino']}")
            continue
        ok += 1
        if r["estado"] == "subido":
            actualizar(conn, r["ruta"], estado="verificado", md5_drive=d["md5"], drive_id=d["id"], error=None)
            promovidos += 1
        elif not r["drive_id"] or (r["error"] or "").startswith(("no está en Drive", "md5/tamaño")):
            actualizar(conn, r["ruta"], drive_id=d["id"], error=None)
    conocidos = {r["destino"] for r in filas}
    extra = sorted(p for p in en_drive if p not in conocidos)
    log(f"\nVerificados: {num(ok)} bien · {num(faltan)} faltan · {num(distintos)} distintos · "
        f"{num(promovidos)} pasan de subido a verificado.")
    if extra:
        log(f"En Drive hay {num(len(extra))} ficheros que la base no conoce (no se tocan):")
        for p in extra[:30]:
            log(f"  {p}")
    escribir_revision(cfg, conn, log)
    return 0 if faltan == distintos == 0 else 1


def cmd_indice(cfg: Config, args) -> int:
    cfg.crear_dirs()
    log = Registro(cfg)
    with Cerrojo(cfg):
        conn = abrir_db(cfg)
        generar_indice(cfg, conn, log)
        escribir_revision(cfg, conn, log)
        return subir_indice(cfg, log)


def cmd_estado(cfg: Config, args) -> int:
    if not os.path.exists(cfg.db):
        print("Aún no hay base de estado: no se ha lanzado ninguna tanda ni la prueba de humo.")
        print("Empieza por «make mig-plan».")
        return 0
    conn = abrir_db(cfg)
    print(f"Base: {cfg.db}")
    print("\nPor estado:")
    for r in conn.execute("SELECT estado, COUNT(*) n, COALESCE(SUM(tamano),0) b FROM ficheros "
                          "GROUP BY estado ORDER BY n DESC"):
        print(f"  {r['estado']:<20} {num(r['n']):>7}  {gb(r['b']):>10}")
    pend = conn.execute("SELECT COUNT(*) n, COALESCE(SUM(tamano),0) b FROM ficheros WHERE estado='pendiente' "
                        "AND clase='comic'").fetchone()
    print(f"\nPendiente: {num(pend['n'])} cómics, {gb(pend['b'])} → ~{num(-(-pend['b'] // int(cfg.tanda_gb * GB)))} "
          f"tandas de {num(cfg.tanda_gb)} GB")
    print("\nTandas:")
    for t in conn.execute("SELECT * FROM tandas ORDER BY id"):
        print(f"  {t['id']:>3} {t['tipo']:<5} {t['inicio']} → {t['fin'] or 'SIN TERMINAR'}  "
              f"{num(t['ficheros'])} ficheros, {gb(t['bytes'] or 0)}  {t['resultado'] or ''}")
    errores = conn.execute("SELECT COUNT(*) FROM ficheros WHERE error IS NOT NULL AND estado<>'apartado_roto'").fetchone()[0]
    print(f"\nCon error pendiente: {num(errores)}")
    rev = os.path.join(cfg.dir_revisar, "REVISION.md")
    if os.path.exists(rev):
        print(f"Lista de revisión: {rev}")
    if not args.sin_drive:
        try:
            c = cuota(cfg, Registro(cfg, "estado"))
            print(f"Drive: {gib(int(c.get('free', 0)))} libres de {gib(int(c.get('total', 0)))} "
                  f"(reserva {num(cfg.reserva_gib)} GiB)")
        except SystemExit as e:
            print(e)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="migrar.py", description="Migración de cómics iCloud → Google Drive")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan", help="solo lectura: qué haría y cuánto pesa")
    p.add_argument("--sin-drive", action="store_true", help="no consultar la cuota de Drive")
    p = sub.add_parser("humo", help="prueba de humo completa sobre una carpeta")
    p.add_argument("carpeta", nargs="?", help="ruta relativa a la raíz de iCloud")
    sub.add_parser("tanda", help="la siguiente tanda")
    sub.add_parser("verificar", help="md5 de la base contra Drive")
    sub.add_parser("indice", help="regenera y resube Cómics/.indice")
    p = sub.add_parser("estado", help="resumen de la base local")
    p.add_argument("--sin-drive", action="store_true")
    args = ap.parse_args(argv)
    cfg = Config()

    def _term(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, _term)
    try:
        try:
            return {"plan": cmd_plan, "humo": cmd_humo, "tanda": cmd_tanda, "verificar": cmd_verificar,
                    "indice": cmd_indice, "estado": cmd_estado}[args.cmd](cfg, args)
        finally:
            while _CONEXIONES:
                c = _CONEXIONES.pop()
                try:
                    c.commit()
                    c.close()
                except sqlite3.Error:
                    pass
    except KeyboardInterrupt:
        for c in _CONEXIONES:
            try:
                c.commit()
            except sqlite3.Error:
                pass
        print("\nInterrumpido. El estado está guardado fichero a fichero: vuelve a lanzar la misma orden "
              "y sigue donde lo dejó.", flush=True)
        os._exit(130)


if __name__ == "__main__":
    sys.exit(main())
