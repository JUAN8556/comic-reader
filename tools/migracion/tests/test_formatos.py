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

    def test_tar_ustar(self):
        h = b"001.png".ljust(257, b"\x00") + b"ustar\x0000" + b"\x00" * 300
        self.assertEqual(fm.detectar(h), (fm.TAR, False))
        self.assertEqual(fm.EXT_REAL[fm.TAR], ".cbt")

    def test_info_rar(self):
        self.assertEqual(fm.info_rar(sx.cabecera_rar4(False)), {"cifrado": False, "volumen": False})
        self.assertTrue(fm.info_rar(sx.cabecera_rar4(False, extra=0x0080))["cifrado"])
        self.assertTrue(fm.info_rar(sx.cabecera_rar4(True, extra=0x0001))["volumen"])
        self.assertTrue(fm.info_rar(sx.cabecera_rar5_cifrada())["cifrado"])
        self.assertFalse(fm.info_rar(sx.cabecera_rar5(True))["cifrado"])
        self.assertEqual(fm.detectar(sx.cabecera_rar5_cifrada()), (fm.RAR5, None))
        self.assertEqual(fm.info_rar(b"PK\x03\x04"), {"cifrado": False, "volumen": False})

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

    def _con_listar(self, resultado, ruta, formato, solido):
        original = fm.listar
        fm.listar = lambda ruta, timeout=600: resultado
        try:
            return fm.integridad(ruta, formato, solido, 10)
        finally:
            fm.listar = original

    def _fichero(self, nombre, datos):
        p = os.path.join(self.tmp, nombre)
        with open(p, "wb") as f:
            f.write(datos)
        return p

    def test_tiempo_agotado_es_dudosa_no_rota(self):
        # F4 (a): bsdtar -tf que no termina (Mac cargada, RAR enorme) no es un archivo roto
        r = self._con_listar((fm.RC_TIEMPO, [b"001.jpg"], "bsdtar -tf no terminó en 600 s"), __file__, fm.RAR4, False)
        self.assertEqual(r["estado"], "dudosa")
        self.assertIn("no se pudo comprobar", r["nota"])
        self.assertEqual(r["lista"], [b"001.jpg"])
        r = self._con_listar((fm.RC_TIEMPO, [], "bsdtar -tf no terminó en 600 s"), __file__, fm.ZIP, False)
        self.assertEqual(r["estado"], "dudosa")

    def test_tiempo_agotado_de_verdad(self):
        p = os.path.join(self.tmp, "a.cbz")
        sx.cbz(p)
        rc, lista, err = fm.listar(p, timeout=0)
        if rc == 0:      # bsdtar fue más rápido que el temporizador: nada que probar
            self.skipTest("bsdtar terminó antes del plazo")
        self.assertEqual(rc, fm.RC_TIEMPO)
        self.assertIn("no terminó", err)

    def test_cbt_tar(self):
        p = os.path.join(self.tmp, "a.cbt")
        sx.tar(p)
        formato, solido = fm.detectar(fm.leer_cabecera(p))
        self.assertEqual(formato, fm.TAR)
        r = fm.integridad(p, formato, solido, os.path.getsize(p))
        self.assertEqual(r["estado"], "ok")
        self.assertEqual(r["imagenes"], 3)

    def test_cabecera_desconocida_que_bsdtar_lee_es_dudosa(self):
        # F4 (b): p. ej. un autoextraíble o un tar.gz: no se aparta, se sube y va a revisión
        p = os.path.join(self.tmp, "a.cbt")
        sx.tar(p, comprimido=True)
        formato, solido = fm.detectar(fm.leer_cabecera(p))
        self.assertEqual(formato, fm.DESCONOCIDO)
        r = fm.integridad(p, formato, solido, os.path.getsize(p))
        self.assertEqual(r["estado"], "dudosa")
        self.assertIn("cabecera desconocida", r["nota"])
        self.assertEqual(r["imagenes"], 3)

    def test_rar_cifrado_es_dudosa(self):
        # F4 (c): cabeceras cifradas -> bsdtar no lista nada, pero no está roto
        p = self._fichero("c4.cbr", sx.cabecera_rar4(False, extra=0x0080))
        r = self._con_listar((1, [], "bsdtar: Invalid header"), p, fm.RAR4, False)
        self.assertEqual(r["estado"], "dudosa")
        self.assertIn("cifrado", r["nota"])
        p = self._fichero("c5.cbr", sx.cabecera_rar5_cifrada())
        r = self._con_listar((1, [], "bsdtar: Truncated RAR5 header"), p, fm.RAR5, None)
        self.assertEqual(r["estado"], "dudosa")
        # 7z (o cualquiera) cuyo error de libarchive habla de cifrado
        r = self._con_listar((1, [], "bsdtar: The archive header is encrypted, but currently not supported"),
                             __file__, fm.SEVEN_ZIP, False)
        self.assertEqual(r["estado"], "dudosa")

    def test_rar5_solido_parcial(self):
        # F4 (d): la excepción de «sólido» ya no es solo para RAR4
        r = self._con_listar((1, [b"001.jpg"], "bsdtar: Invalid solid"), __file__, fm.RAR5, True)
        self.assertEqual(r["estado"], "parcial")
        r = self._con_listar((1, [b"001.jpg"], "bsdtar: Invalid solid"), __file__, fm.RAR5, None)
        self.assertEqual(r["estado"], "parcial")
        r = self._con_listar((1, [b"001.jpg"], "bsdtar: Truncated"), __file__, fm.RAR5, False)
        self.assertEqual(r["estado"], "rota")

    def test_rar_multivolumen_es_dudosa(self):
        p = self._fichero("v.cbr", sx.cabecera_rar4(False, extra=0x0001))
        r = self._con_listar((1, [], "bsdtar: Missing volume"), p, fm.RAR4, False)
        self.assertEqual(r["estado"], "dudosa")
        self.assertIn("multivolumen", r["nota"])

    def test_rar_conocido_que_no_lista_nada_sigue_roto(self):
        p = self._fichero("r.cbr", sx.cabecera_rar4(False))
        r = self._con_listar((1, [], "bsdtar: Damaged archive"), p, fm.RAR4, False)
        self.assertEqual(r["estado"], "rota")

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
