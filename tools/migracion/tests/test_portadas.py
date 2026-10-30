import os
import shutil
import sys
import tempfile
import unittest
import zipfile

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))
sys.path.insert(0, AQUI)

import formatos as fm  # noqa: E402
import portadas as po  # noqa: E402
import sinteticos as sx  # noqa: E402

HERRAMIENTAS = all(shutil.which(x) for x in ("bsdtar", "cwebp", "sips"))


class Eleccion(unittest.TestCase):
    def test_orden_natural(self):
        lista = [b"10.jpg", b"2.jpg", b"1.jpg", b"__MACOSX/._1.jpg", b"info.txt"]
        self.assertEqual(po.candidatas(lista, False)[0], b"1.jpg")

    def test_rar4_solido_primera_fisica(self):
        lista = [b"10.jpg", b"2.jpg", b"1.jpg"]
        self.assertEqual(po.candidatas(lista, True)[0], b"10.jpg")

    def test_escapar(self):
        self.assertEqual(po.escapar_patron(b"[MQ] a*b?.jpg"), b"\\[MQ\\] a\\*b\\?.jpg")


@unittest.skipUnless(HERRAMIENTAS, "faltan bsdtar, cwebp o sips")
class Generar(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mig-test-")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def _webp_ok(self, p):
        self.assertTrue(os.path.exists(p))
        self.assertEqual(sx.leer(p)[:4], b"RIFF")

    def test_cbz_con_corchetes_y_acentos(self):
        p = os.path.join(self.tmp, "a.cbz")
        sx.cbz(p, paginas=("[MQ] página 10.png", "[MQ] página 2.png"))
        rc, lista, _ = fm.listar(p)
        salida = os.path.join(self.tmp, "p.webp")
        hecho, nota = po.generar(p, fm.ZIP, lista, False, salida, os.path.join(self.tmp, "w"))
        self.assertTrue(hecho, nota)
        self._webp_ok(salida)

    def test_7z(self):
        p = os.path.join(self.tmp, "a.cb7")
        sx.siete_z(p, os.path.join(self.tmp, "x"))
        rc, lista, _ = fm.listar(p)
        salida = os.path.join(self.tmp, "p.webp")
        self.assertTrue(po.generar(p, fm.SEVEN_ZIP, lista, False, salida, os.path.join(self.tmp, "w"))[0])
        self._webp_ok(salida)

    def test_cbt(self):
        p = os.path.join(self.tmp, "a.cbt")
        sx.tar(p)
        rc, lista, _ = fm.listar(p)
        salida = os.path.join(self.tmp, "p.webp")
        hecho, nota = po.generar(p, fm.TAR, lista, False, salida, os.path.join(self.tmp, "w"))
        self.assertTrue(hecho, nota)
        self._webp_ok(salida)

    def test_desconocido_que_bsdtar_lista(self):
        p = os.path.join(self.tmp, "a.cbt")
        sx.tar(p, comprimido=True)
        rc, lista, _ = fm.listar(p)
        salida = os.path.join(self.tmp, "p.webp")
        hecho, nota = po.generar(p, fm.DESCONOCIDO, lista, None, salida, os.path.join(self.tmp, "w"))
        self.assertTrue(hecho, nota)
        self._webp_ok(salida)
        self.assertFalse(po.generar(p, fm.DESCONOCIDO, [], None, salida, os.path.join(self.tmp, "w"))[0])

    def test_pdf(self):
        p = os.path.join(self.tmp, "a.pdf")
        sx.pdf(p)
        salida = os.path.join(self.tmp, "p.webp")
        hecho, nota = po.generar(p, fm.PDF, [], False, salida, os.path.join(self.tmp, "w"))
        self.assertTrue(hecho, nota)
        self._webp_ok(salida)

    def test_sin_imagenes(self):
        p = os.path.join(self.tmp, "a.cbz")
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("leeme.txt", "nada")
        rc, lista, _ = fm.listar(p)
        hecho, nota = po.generar(p, fm.ZIP, lista, False, os.path.join(self.tmp, "p.webp"),
                                 os.path.join(self.tmp, "w"))
        self.assertFalse(hecho)


if __name__ == "__main__":
    unittest.main()
