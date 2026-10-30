"""rclone y evict falsos para probar el flujo entero sin red ni iCloud.

«Drive» es una carpeta local (FAKE_DRIVE); el remoto «gdrive:Cómics/x» es FAKE_DRIVE/Cómics/x.
El evict falso apunta en FAKE_EVICT qué rutas «desalojó» y, con --estado, dice 0 bloques para ellas.

Averías a demanda (variables de entorno):
  FAKE_FALLA=copy[,about…][,caer]  esas órdenes fallan sin hacer nada; con «caer», tras fallar
                                   «se cae la red»: existe FAKE_DRIVE/caido y fallan todas
  FAKE_CORROMPER=<texto>           copy/copyto escriben con un byte cambiado (mismo tamaño y
                                   fecha) los destinos que contienen <texto>
  FAKE_EVICT_FALLA=<texto>         evict falla en las rutas que contienen <texto>
Como el rclone de verdad, «copy» se salta un destino con el mismo tamaño y fecha (salvo con
--ignore-times), y conserva la fecha del origen.
"""

import os
import stat
import sys

RCLONE = r'''#!/usr/bin/env python3
import hashlib, json, os, shutil, sys
DRIVE = os.environ["FAKE_DRIVE"]
CON_VALOR = {"--files-from", "--files-from-raw", "--hash-type", "--stats", "--stats-log-level", "--drive-chunk-size",
             "--transfers", "--checkers", "--retries", "--low-level-retries", "--exclude"}
args = sys.argv[1:]
cmd, pos, opt, excl = args[0], [], {}, []
i = 1
while i < len(args):
    a = args[i]
    if a in CON_VALOR:
        if a == "--exclude":
            excl.append(args[i + 1])
        opt[a] = args[i + 1]
        i += 2
    elif a.startswith("-"):
        opt[a] = True
        i += 1
    else:
        pos.append(a)
        i += 1
with open(os.path.join(DRIVE, "llamadas.log"), "a") as f:
    f.write(" ".join(args) + "\n")
FALLA = {x for x in os.environ.get("FAKE_FALLA", "").split(",") if x}
CAIDO = os.path.join(DRIVE, "caido")
RED = ("about", "copy", "copyto", "lsjson", "lsl", "md5sum")
if cmd in RED and os.path.exists(CAIDO):
    print("Failed to create file system: dial tcp: lookup www.googleapis.com: no such host", file=sys.stderr)
    sys.exit(1)
if cmd in FALLA:
    print(f"2026/10/30 02:00:00 ERROR : {cmd}: googleapi: Error 503: Service Unavailable", file=sys.stderr)
    if "caer" in FALLA:
        open(CAIDO, "w").close()
    sys.exit(1)
def escribir(origen, destino, forzar):
    if not forzar and os.path.exists(destino) and os.path.getsize(destino) == os.path.getsize(origen) \
            and abs(os.path.getmtime(destino) - os.path.getmtime(origen)) < 0.001:
        return False
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    shutil.copy2(origen, destino)
    marca = os.environ.get("FAKE_CORROMPER")
    if marca and marca in destino:
        with open(destino, "r+b") as f:
            f.seek(os.path.getsize(destino) // 2)
            b = f.read(1)
            f.seek(-1, 1)
            f.write(bytes([b[0] ^ 0xFF]))
        shutil.copystat(origen, destino)
    return True
def local(remoto):
    if ":" in remoto and not remoto.startswith("/"):
        return os.path.join(DRIVE, remoto.split(":", 1)[1])
    return remoto
def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        h.update(f.read())
    return h.hexdigest()
def excluido(rel):
    for e in excl:
        pre = e.strip("/").replace("/**", "")
        if rel == pre or rel.startswith(pre + "/"):
            return True
    return False
def ficheros(base, recursivo=True):
    out = []
    if not os.path.isdir(base):
        return out
    for dp, dn, fn in os.walk(base):
        for n in fn:
            rel = os.path.relpath(os.path.join(dp, n), base)
            if not excluido(rel):
                out.append(rel)
        if not recursivo:
            break
    return sorted(out)
if cmd in ("sync", "delete", "deletefile", "purge", "rmdir", "rmdirs", "move", "moveto"):
    print("PROHIBIDO: " + cmd, file=sys.stderr)
    sys.exit(99)
if cmd == "about":
    print(json.dumps({"total": 107374182400, "used": 1000, "free": int(os.environ.get("FAKE_FREE", "80000000000"))}))
elif cmd == "copy":
    src, dst = pos[0], local(pos[1])
    if "--files-from-raw" in opt or "--files-from" in opt:
        with open(opt.get("--files-from-raw") or opt["--files-from"], encoding="utf-8") as fl:
            lista = [l.rstrip("\n") for l in fl if l.strip()]
    else:
        lista = [r for r in ficheros(src) if not r.endswith(".tmp")]
    for rel in lista:
        if escribir(os.path.join(src, rel), os.path.join(dst, rel), "--ignore-times" in opt):
            print(f"2026/10/30 02:00:00 INFO  : {rel}: Copied (new)", file=sys.stderr)
    print("2026/10/30 02:00:01 NOTICE: 1 MiB / 1 MiB, 100%, 1 MiB/s, ETA 0s", file=sys.stderr)
elif cmd == "copyto":
    escribir(pos[0], local(pos[1]), "--ignore-times" in opt)
elif cmd == "lsjson":
    base = local(pos[0])
    out = []
    for rel in ficheros(base, recursivo="-R" in opt):
        p = os.path.join(base, rel)
        out.append({"Path": rel, "Name": os.path.basename(rel), "Size": os.path.getsize(p),
                    "Hashes": {"md5": md5(p)}, "ID": "id-" + md5(p)[:10], "IsDir": False})
    print(json.dumps(out))
elif cmd == "lsl":
    base = local(pos[0])
    for rel in ficheros(base):
        print(f"{os.path.getsize(os.path.join(base, rel)):>9} 2026-10-30 02:00:00.000000000 {rel}")
elif cmd == "md5sum":
    base = local(pos[0])
    for rel in ficheros(base):
        print(f"{md5(os.path.join(base, rel))}  {rel}")
else:
    print("orden no simulada: " + cmd, file=sys.stderr)
    sys.exit(1)
'''

EVICT = r'''#!/usr/bin/env python3
import os, sys
reg = os.environ["FAKE_EVICT"]
args = sys.argv[1:]
hechos = set(open(reg, encoding="utf-8").read().splitlines()) if os.path.exists(reg) else set()
if args and args[0] == "--estado":
    for p in args[1:]:
        print(f"estado\t{p}\tsi\t{'no_descargado' if p in hechos else 'actual'}\t{0 if p in hechos else 8}")
    sys.exit(0)
falla = os.environ.get("FAKE_EVICT_FALLA")
with open(reg, "a", encoding="utf-8") as f:
    for p in args:
        if falla and falla in p:
            print(f"error\t{p}\tThe operation couldn't be completed. (NSCocoaErrorDomain error 256.)")
        elif os.path.exists(p):
            f.write(p + "\n")
            print(f"ok\t{p}")
        else:
            print(f"error\t{p}\tno existe")
'''


def instalar(bin_dir: str) -> tuple[str, str]:
    os.makedirs(bin_dir, exist_ok=True)
    rutas = []
    for nombre, codigo in (("rclone", RCLONE), ("evict", EVICT)):
        p = os.path.join(bin_dir, nombre)
        with open(p, "w", encoding="utf-8") as f:
            f.write(codigo.replace("#!/usr/bin/env python3", "#!" + sys.executable, 1))
        os.chmod(p, os.stat(p).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        rutas.append(p)
    return rutas[0], rutas[1]
