import os
import shutil
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))
sys.path.insert(0, AQUI)

import formatos as fm  # noqa: E402
import sinteticos as sx  # noqa: E402

HAY_BSDTAR = shutil.which("bsdtar") is not None


class Cabeceras(unittest.TestCase):
    def test_firmas(self):
        self.assertEqual(fm.detectar(b"PK\x03\x04resto"), (fm.ZIP, False))
        self.assertEqual(fm.detectar(b"7z\xbc\xaf\x27\x1c\x00\x04"), (fm.SEVEN_ZIP, False))
        self.assertEqual(fm.detectar(b"%PDF-1.7\n")[0], fm.PDF)
        self.assertEqual(fm.detectar(b"\n\n%PDF-1.4")[0], fm.PDF)
        self.assertEqual(fm.detectar(b"<html>")[0], fm.DESCONOCIDO)
        self.assertEqual(fm.detectar(b"")[0], fm.DESCONOCIDO)

    def test_rar4_solido_por_bytes(self):
        self.assertEqual(fm.detectar(sx.cabecera_rar4(False)), (fm.RAR4, False))
        self.assertEqual(fm.detectar(sx.cabecera_rar4(True)), (fm.RAR4, True))

    def test_rar4_cabecera_real(self):
        # primeros 16 bytes de un RAR4 real (no sólido)
        h = bytes.fromhex("526172211a0700cf907300000d000000")
        self.assertEqual(fm.detectar(h), (fm.RAR4, False))

    def test_rar5(self):
        self.assertEqual(fm.detectar(sx.cabecera_rar5(False)), (fm.RAR5, False))
        self.assertEqual(fm.detectar(sx.cabecera_rar5(True)), (fm.RAR5, True))

    def test_extension_real(self):
        self.assertEqual(fm.EXT_REAL[fm.ZIP], ".cbz")
        self.assertEqual(fm.EXT_REAL[fm.SEVEN_ZIP], ".cb7")
        self.assertEqual(fm.EXT_REAL[fm.RAR5], ".cbr")

    def test_es_imagen(self):
        self.assertTrue(fm.es_imagen("a/001.JPG"))
        self.assertFalse(fm.es_imagen("__MACOSX/._001.jpg"))
        self.assertFalse(fm.es_imagen(".oculto.png"))
        self.assertFalse(fm.es_imagen("ComicInfo.xml"))


@unittest.skipUnless(HAY_BSDTAR, "sin bsdtar")
class Integridad(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mig-test-")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_zip_bueno(self):
        p = os.path.join(self.tmp, "a.cbz")
        sx.cbz(p)
        r = fm.integridad(p, fm.ZIP, False, os.path.getsize(p))
        self.assertEqual(r["estado"], "ok")
        self.assertEqual(r["imagenes"], 3)

    def test_zip_truncado_es_roto(self):
        p = os.path.join(self.tmp, "a.cbz")
        sx.cbz(p)
        datos = sx.leer(p)
        with open(p, "wb") as f:
            f.write(datos[: len(datos) // 2])
        r = fm.integridad(p, fm.ZIP, False, os.path.getsize(p))
        self.assertEqual(r["estado"], "rota")

    def test_7z_disfrazado(self):
        p = os.path.join(self.tmp, "disfrazado.cbr")
        sx.siete_z(p, os.path.join(self.tmp, "x"))
        formato, solido = fm.detectar(fm.leer_cabecera(p))
        self.assertEqual(formato, fm.SEVEN_ZIP)
        self.assertEqual(fm.integridad(p, formato, solido, os.path.getsize(p))["estado"], "ok")

    def test_cero_bytes_y_desconocido(self):
        p = os.path.join(self.tmp, "v.cbr")
        open(p, "wb").close()
        self.assertEqual(fm.integridad(p, fm.DESCONOCIDO, None, 0)["estado"], "rota")
        with open(p, "wb") as f:
            f.write(b"<html>no es un comic</html>")
        self.assertEqual(fm.integridad(p, fm.DESCONOCIDO, None, os.path.getsize(p))["estado"], "rota")

    def test_rar4_solido_falla_tras_la_primera(self):
        # simulación: bsdtar lista una entrada y falla (lo que hace libarchive con RAR4 sólido)
        original = fm.listar
        fm.listar = lambda ruta, timeout=600: (1, [b"001.jpg"], "bsdtar: Invalid solid")
        try:
            r = fm.integridad(__file__, fm.RAR4, True, 10)
            self.assertEqual(r["estado"], "parcial")
            self.assertIn("sólido", r["nota"])
            r = fm.integridad(__file__, fm.RAR4, False, 10)
            self.assertEqual(r["estado"], "rota")
        finally:
            fm.listar = original

    def test_pdf_con_y_sin_eof(self):
        p = os.path.join(self.tmp, "a.pdf")
        sx.pdf(p)
        self.assertEqual(fm.integridad(p, fm.PDF, False, os.path.getsize(p))["estado"], "ok")
        datos = sx.leer(p).replace(b"%%EOF", b"")
        with open(p, "wb") as f:
            f.write(datos)
        self.assertEqual(fm.integridad(p, fm.PDF, False, os.path.getsize(p))["estado"], "dudosa")


if __name__ == "__main__":
    unittest.main()
