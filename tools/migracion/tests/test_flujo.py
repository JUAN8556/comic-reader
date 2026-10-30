"""Flujo entero (plan -> tanda -> verificar -> índice -> humo) con rclone y evict falsos."""

import hashlib
import io
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
import zipfile
from contextlib import contextmanager, redirect_stdout

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))
sys.path.insert(0, AQUI)

import falsos  # noqa: E402
import migrar  # noqa: E402
import sinteticos as sx  # noqa: E402

HERRAMIENTAS = all(shutil.which(x) for x in ("bsdtar", "cwebp", "sips"))


def md5(p):
    return hashlib.md5(sx.leer(p)).hexdigest()


@contextmanager
def entorno(**variables):
    previo = {k: os.environ.get(k) for k in variables}
    os.environ.update(variables)
    try:
        yield
    finally:
        for k, v in previo.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


LOTE_DE_UNO = "0.000000001"      # LOTE_GB tan pequeño que cada fichero es su propio lote


@unittest.skipUnless(HERRAMIENTAS, "faltan bsdtar, cwebp o sips")
class Flujo(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="mig-flujo-")
        t = cls.tmp
        cls.origen = os.path.join(t, "iCloud", "Cómics ")          # con espacio final, como el de verdad
        cls.data = os.path.join(t, "data")
        cls.drive = os.path.join(t, "drive")
        bin_dir = os.path.join(t, "bin")
        rclone, evict = falsos.instalar(bin_dir)
        cls.env_previo = dict(os.environ)
        os.environ.update({
            "COMICS_ORIGEN": cls.origen, "COMICS_DATA": cls.data, "COMICS_REMOTO": "gdrive:Cómics",
            "FAKE_DRIVE": cls.drive, "FAKE_EVICT": os.path.join(t, "evict.log"),
            "PATH": bin_dir + os.pathsep + os.environ["PATH"], "COMICS_SIN_CAFFEINATE": "1",
            "MIN_LIBRE_MAC_GB": "1", "TANDA_GB": "15",
        })
        cls.bin_previo = migrar.BIN_EVICT
        migrar.BIN_EVICT = evict
        o = cls.origen
        titan = os.path.join(o, "Marvel", "C0M1CS D3L PL4N3T4", "Titán - La Caída del Olimpo (Forum)")
        sx.cbz(os.path.join(titan, "Titan.La Caida Del Olimpo 01.cbr"))           # ZIP disfrazado de .cbr
        sx.siete_z(os.path.join(titan, "Titan.La Caida Del Olimpo 02.cbr"), os.path.join(t, "x7"))  # 7z disfrazado
        sx.cbz(os.path.join(titan, "Titan.La Caida Del Olimpo 03.cbz"), paginas=("a1.png", "a2.png"))
        sx.cbz(os.path.join(o, "Serie  Uno ", "Serie Uno 01.cbz"), paginas=("1.png",))   # espacios raros
        sx.cbz(os.path.join(o, "Serie  Uno ", "Serie Uno 02.cbz"), paginas=("1.png", "2.png", "3.png"))
        os.makedirs(os.path.join(o, "Zeta"), exist_ok=True)
        shutil.copyfile(os.path.join(o, "Serie  Uno ", "Serie Uno 01.cbz"), os.path.join(o, "Zeta", "copia.cbz"))
        sx.cbz(os.path.join(o, "Zeta", "roto.cbz"))
        datos = sx.leer(os.path.join(o, "Zeta", "roto.cbz"))
        with open(os.path.join(o, "Zeta", "roto.cbz"), "wb") as f:
            f.write(datos[:60])
        open(os.path.join(o, "Zeta", "vacio.cbr"), "wb").close()
        with open(os.path.join(o, "Zeta", "logo.png"), "wb") as f:
            f.write(sx.png())
        with open(os.path.join(o, ".DS_Store"), "wb") as f:
            f.write(b"\x00\x00")
        sx.pdf(os.path.join(o, "Akira", "Akira Tomo 01.pdf"))
        sx.cbz(os.path.join(o, "Suelto - Autor.cbz"))
        # Drive ya tiene una carpeta de prueba que no se puede tocar
        sonda = os.path.join(cls.drive, "Cómics", "_sonda")
        os.makedirs(sonda)
        with open(os.path.join(sonda, "no-tocar.cbr"), "wb") as f:
            f.write(b"sonda")
        cls.md5_origen = {}
        for dp, dn, fn in os.walk(o):
            for n in fn:
                p = os.path.join(dp, n)
                cls.md5_origen[p] = md5(p)

    @classmethod
    def tearDownClass(cls):
        migrar.BIN_EVICT = cls.bin_previo
        os.environ.clear()
        os.environ.update(cls.env_previo)
        shutil.rmtree(cls.tmp)

    def correr(self, *args):
        buf = io.StringIO()
        with redirect_stdout(buf):
            try:
                rc = migrar.main(list(args))
            except SystemExit as e:
                rc = e.code if isinstance(e.code, int) else 2
                print(e)
        return rc, buf.getvalue()

    def db(self):
        conn = sqlite3.connect(os.path.join(self.data, "estado", "migracion.sqlite"))
        conn.row_factory = sqlite3.Row
        try:
            return {r["ruta_nfc"]: dict(r) for r in conn.execute("SELECT * FROM ficheros")}
        finally:
            conn.close()

    def texto(self, p):
        with open(p, encoding="utf-8") as f:
            return f.read()

    def tandas_abiertas(self):
        conn = sqlite3.connect(os.path.join(self.data, "estado", "migracion.sqlite"))
        try:
            return conn.execute("SELECT COUNT(*) FROM tandas WHERE fin IS NULL AND tipo='tanda'").fetchone()[0]
        finally:
            conn.close()

    def serie(self, nombre, n):
        rutas = []
        for i in range(1, n + 1):
            p = os.path.join(self.origen, nombre, f"{nombre} 0{i}.cbz")
            sx.cbz(p, paginas=(f"{nombre}-{i}-a.png", f"{nombre}-{i}-b.png"))   # contenido único: sin duplicados
            rutas.append(f"{nombre}/{nombre} 0{i}.cbz")
        return rutas

    def test_1_plan_no_toca_nada(self):
        rc, out = self.correr("plan", "--sin-drive")
        self.assertEqual(rc, 0, out)
        self.assertIn("Ficheros: **12**", out)
        self.assertIn("Primera tanda", out)
        self.assertFalse(os.path.exists(os.path.join(self.data, "estado", "migracion.sqlite")))
        self.assertFalse(os.path.exists(os.path.join(self.drive, "llamadas.log")))

    def test_2_tanda(self):
        rc, out = self.correr("tanda")
        if os.environ.get("VER_SALIDA"):
            print(out)
        self.assertEqual(rc, 0, out)
        filas = self.db()
        base = "Americano/Marvel/Titán - La Caída del Olimpo/Titán - La Caída del Olimpo"
        r1 = filas["Marvel/C0M1CS D3L PL4N3T4/Titán - La Caída del Olimpo (Forum)/Titan.La Caida Del Olimpo 01.cbr"]
        self.assertEqual(r1["destino"], base + " 001.cbz")           # ZIP disfrazado -> .cbz
        self.assertEqual(r1["estado"], "desalojado")
        r2 = filas["Marvel/C0M1CS D3L PL4N3T4/Titán - La Caída del Olimpo (Forum)/Titan.La Caida Del Olimpo 02.cbr"]
        self.assertEqual(r2["destino"], base + " 002.cb7")           # 7z disfrazado -> .cb7
        self.assertEqual(filas["Serie Uno/Serie Uno 01.cbz"]["destino"], "Americano/Otros/Serie Uno/Serie Uno 001.cbz")
        self.assertEqual(filas["Zeta/copia.cbz"]["estado"], "apartado_duplicado")
        self.assertEqual(filas["Zeta/roto.cbz"]["estado"], "apartado_roto")
        self.assertEqual(filas["Zeta/vacio.cbr"]["estado"], "apartado_roto")
        self.assertEqual(filas["Zeta/logo.png"]["estado"], "ignorado")
        self.assertEqual(filas["Akira/Akira Tomo 01.pdf"]["destino"], "Manga/Akira/Akira 001.pdf")
        self.assertEqual(filas["Suelto - Autor.cbz"]["destino"],
                         "Americano/Otros/Suelto - Autor/Suelto - Autor.cbz")
        # en «Drive» con el mismo md5
        for r in filas.values():
            if r["estado"] == "desalojado":
                p = os.path.join(self.drive, "Cómics", r["destino"])
                self.assertTrue(os.path.exists(p), r["destino"])
                self.assertEqual(md5(p), r["md5"])
                self.assertTrue(r["portada"], r["ruta_nfc"])
                self.assertTrue(os.path.exists(os.path.join(self.data, "portadas", r["portada"])))
        # índice subido
        ind = os.path.join(self.drive, "Cómics", ".indice")
        lineas = [json.loads(x) for x in self.texto(os.path.join(ind, "indice.jsonl")).splitlines()]
        self.assertEqual(len(lineas), sum(1 for r in filas.values() if r["estado"] == "desalojado"))
        self.assertTrue(all(x["drive_id"] for x in lineas))
        with zipfile.ZipFile(os.path.join(ind, "portadas.zip")) as z:
            self.assertEqual(len(z.namelist()), len(lineas))
        # lista de revisión
        rev = self.texto(os.path.join(self.data, "revisar", "REVISION.md"))
        self.assertIn("Zeta/copia.cbz", rev)
        self.assertIn("Zeta/roto.cbz", rev)

    def test_3_icloud_y_sonda_intactos(self):
        for p, h in self.md5_origen.items():
            self.assertTrue(os.path.exists(p), p)
            self.assertEqual(md5(p), h, p)
        self.assertEqual(sx.leer(os.path.join(self.drive, "Cómics", "_sonda", "no-tocar.cbr")), b"sonda")
        llamadas = self.texto(os.path.join(self.drive, "llamadas.log"))
        self.assertNotIn("sync", llamadas.split())
        self.assertNotIn("_sonda", llamadas)

    def test_4_repetir_no_hace_nada(self):
        rc, out = self.correr("tanda")
        self.assertEqual(rc, 0, out)
        self.assertIn("No queda nada pendiente", out)

    def test_5_verificar_e_indice(self):
        rc, out = self.correr("verificar")
        self.assertEqual(rc, 0, out)
        self.assertIn("0 faltan", out)
        rc, out = self.correr("indice")
        self.assertEqual(rc, 0, out)

    def test_6_humo_sobre_carpeta_ya_migrada(self):
        rc, out = self.correr("humo", "Marvel/C0M1CS D3L PL4N3T4/Titán - La Caída del Olimpo (Forum)")
        self.assertEqual(rc, 0, out)
        self.assertIn("SUPERADA", out)

    def test_7_estado(self):
        rc, out = self.correr("estado", "--sin-drive")
        self.assertEqual(rc, 0, out)
        self.assertIn("desalojado", out)

    def test_8_cuota_corta_para(self):
        os.environ["FAKE_FREE"] = str(10 * 1024 ** 3)
        try:
            sx.cbz(os.path.join(self.origen, "Zzz Nueva", "Zzz Nueva 01.cbz"))
            rc, out = self.correr("tanda")
            self.assertNotEqual(rc, 0)
            self.assertIn("PARADA", out)
        finally:
            del os.environ["FAKE_FREE"]

    def test_9a_rezagados_de_una_tanda_cerrada(self):
        # F1: un «subido» (md5 distinto incluso tras el reintento) y un «verificado» (falló el desalojo)
        # al cerrar la tanda; la siguiente «make migrar» tiene que recogerlos sin que nada más los nombre.
        r1, r2 = self.serie("Serie F1", 2)
        with entorno(FAKE_CORROMPER="Serie F1 001", FAKE_EVICT_FALLA="Serie F1 02.cbz"):
            rc, out = self.correr("tanda")
        self.assertEqual(rc, 1, out)
        self.assertEqual(self.tandas_abiertas(), 0, "la tanda se cierra aunque queden errores")
        filas = self.db()
        self.assertEqual(filas[r1]["estado"], "subido", out)
        self.assertIn("md5 distinto", filas[r1]["error"])
        self.assertEqual(filas[r2]["estado"], "verificado")
        self.assertIn("no se pudo desalojar", filas[r2]["error"])
        rc, out = self.correr("tanda")
        self.assertEqual(rc, 0, out)
        self.assertIn("Rezagados de tandas anteriores: 2", out)
        filas = self.db()
        for r in (r1, r2):
            self.assertEqual(filas[r]["estado"], "desalojado", out)
            self.assertIsNone(filas[r]["error"])
            self.assertEqual(md5(os.path.join(self.drive, "Cómics", filas[r]["destino"])), filas[r]["md5"])
        rc, out = self.correr("tanda")
        self.assertIn("No queda nada pendiente", out)

    def test_9b_subida_caida_no_materializa_el_resto(self):
        # F2: rclone copy falla del todo (Drive responde a «about»): tras dos lotes en blanco, PARADA con la
        # tanda abierta; el tercer fichero no se llega a materializar. Al volver la red, se reanuda.
        rutas = self.serie("Serie F2", 3)
        with entorno(FAKE_FALLA="copy", LOTE_GB=LOTE_DE_UNO):
            rc, out = self.correr("tanda")
        self.assertNotEqual(rc, 0, out)
        self.assertIn("PARADA: dos lotes seguidos", out)
        filas = self.db()
        self.assertTrue(filas[rutas[0]]["md5"] and filas[rutas[1]]["md5"])
        self.assertIsNone(filas[rutas[2]]["md5"], "el tercer lote no debía materializarse")
        self.assertTrue(all(filas[r]["estado"] == "pendiente" for r in rutas))
        self.assertEqual(self.tandas_abiertas(), 1)
        with entorno(LOTE_GB=LOTE_DE_UNO):
            rc, out = self.correr("tanda")
        self.assertEqual(rc, 0, out)
        self.assertIn("Reanudo la tanda", out)
        filas = self.db()
        self.assertTrue(all(filas[r]["estado"] == "desalojado" for r in rutas), out)
        self.assertEqual(self.tandas_abiertas(), 0)

    def test_9c_red_caida_a_media_tanda(self):
        # F2: la red se cae durante la subida: ni copy ni lsjson ni about. Para tras el primer lote.
        rutas = self.serie("Serie F2b", 2)
        caido = os.path.join(self.drive, "caido")
        try:
            with entorno(FAKE_FALLA="copy,caer", LOTE_GB=LOTE_DE_UNO):
                rc, out = self.correr("tanda")
            self.assertNotEqual(rc, 0, out)
            self.assertIn("PARADA", out)
            filas = self.db()
            self.assertTrue(filas[rutas[0]]["md5"])
            self.assertIsNone(filas[rutas[1]]["md5"], "con Drive caído no se materializa el lote siguiente")
            self.assertIn("no se pudo comprobar en Drive", filas[rutas[0]]["error"])
            # sin red ni siquiera arranca: la tanda sigue abierta y nada nuevo se materializa
            with entorno(LOTE_GB=LOTE_DE_UNO):
                rc, out = self.correr("tanda")
            self.assertNotEqual(rc, 0, out)
            self.assertIsNone(self.db()[rutas[1]]["md5"])
        finally:
            if os.path.exists(caido):
                os.remove(caido)
        with entorno(LOTE_GB=LOTE_DE_UNO):
            rc, out = self.correr("tanda")
        self.assertEqual(rc, 0, out)
        filas = self.db()
        self.assertTrue(all(filas[r]["estado"] == "desalojado" and filas[r]["error"] is None for r in rutas), out)

    def test_9d_lo_que_verificar_encuentra_mal_se_resube(self):
        # F1: un desalojado cuyo fichero en Drive cambió (mismo tamaño y fecha): «mig-verificar» lo señala y la
        # siguiente tanda lo resube y lo verifica antes de volver a desalojarlo.
        ruta = "Serie Uno/Serie Uno 02.cbz"
        fila = self.db()[ruta]
        self.assertEqual(fila["estado"], "desalojado")
        p = os.path.join(self.drive, "Cómics", fila["destino"])
        st = os.stat(p)
        with open(p, "r+b") as f:
            f.seek(st.st_size // 2)
            b = f.read(1)
            f.seek(-1, 1)
            f.write(bytes([b[0] ^ 0xFF]))
        os.utime(p, ns=(st.st_atime_ns, st.st_mtime_ns))
        rc, out = self.correr("verificar")
        self.assertEqual(rc, 1, out)
        self.assertIn("1 distintos", out)
        rc, out = self.correr("tanda")
        self.assertEqual(rc, 0, out)
        self.assertIn("vuelven a «subido»", out)
        self.assertEqual(md5(p), fila["md5"])
        fila = self.db()[ruta]
        self.assertEqual(fila["estado"], "desalojado")
        self.assertIsNone(fila["error"])
        rc, out = self.correr("verificar")
        self.assertEqual(rc, 0, out)
        self.assertIn("0 distintos", out)


if __name__ == "__main__":
    unittest.main()
