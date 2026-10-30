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
from contextlib import redirect_stdout

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))
sys.path.insert(0, AQUI)

import falsos  # noqa: E402
import migrar  # noqa: E402
import sinteticos as sx  # noqa: E402

HERRAMIENTAS = all(shutil.which(x) for x in ("bsdtar", "cwebp", "sips"))


def md5(p):
    return hashlib.md5(sx.leer(p)).hexdigest()


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


if __name__ == "__main__":
    unittest.main()
