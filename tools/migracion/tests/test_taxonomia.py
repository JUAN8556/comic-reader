"""taxonomia.json: categoría «Otro», `subcarpeta`, `revisar` y la entrada de una carpeta contenedor."""

import json
import os
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(AQUI))

import migrar  # noqa: E402
import nombres  # noqa: E402


def fichero(ruta, tamano=1000):
    return {"ruta": ruta, "ruta_nfc": nombres.ruta_normalizada(ruta), "clase": "comic", "tamano": tamano,
            "mtime": 0.0, "bloques": 0, "sin_datos": False}


TAXONOMIA = {
    ".": {"categoria": "Otro", "editorial_carpeta": "Otros", "serie_canonica": "Novelas gráficas sueltas",
          "subcarpeta": None, "revisar": True, "aviso": "Mezcla: repartir una a una."},
    "DC Comics": {"categoria": "Americano", "editorial_carpeta": "DC", "serie_canonica": "DC Comics - La Enciclopedia",
                  "subcarpeta": None, "revisar": False, "aviso": "Contenedor: solo el fichero suelto."},
    "1984 (Cómic)": {"categoria": "Otro", "editorial_carpeta": "Otros", "serie_canonica": "1984 (Fido Nesti)",
                     "subcarpeta": None, "revisar": False, "aviso": None},
    "1984 (Cómic)/Parte 1": {"categoria": "Otro", "editorial_carpeta": "Otros", "serie_canonica": "1984 (Fido Nesti)",
                             "subcarpeta": "Parte 1", "revisar": False, "aviso": None},
    "1984 (Cómic)/Parte 2": {"categoria": "Otro", "editorial_carpeta": "Otros", "serie_canonica": "1984 (Fido Nesti)",
                             "subcarpeta": "Parte 2", "revisar": False, "aviso": None},
    "Marvel/Dudosa": {"categoria": "Americano", "editorial_carpeta": "Marvel", "serie_canonica": "Dudosa",
                      "subcarpeta": None, "revisar": True, "aviso": "Confianza baja."},
    "Dragon Ball/Arco 1": {"categoria": "Manga", "editorial_carpeta": None, "serie_canonica": "Dragon Ball",
                           "subcarpeta": "Arco 01 - Infancia", "revisar": False, "aviso": None},
    "Mala/Barra": {"categoria": "Americano", "editorial_carpeta": "Marvel", "serie_canonica": "Serie",
                   "subcarpeta": "a/b", "revisar": False, "aviso": None},
}

CENSO = [
    fichero("300.cbr"), fichero("Habibi.cbz"),
    fichero("DC Comics/DC Comics - La Enciclopedia.pdf"),
    fichero("DC Comics/Batman/Batman 01.cbr"),
    fichero("1984 (Cómic)/1984 - Tomo único.cbr"),
    fichero("1984 (Cómic)/Parte 1/1984 01.cbr"), fichero("1984 (Cómic)/Parte 2/1984 02.cbr"),
    fichero("Marvel/Dudosa/Dudosa 01.cbr", 5000), fichero("Marvel/Dudosa/Dudosa 02.cbr", 5000),
    fichero("Marvel/Clara/Clara 01.cbr"),
    fichero("Dragon Ball/Arco 1/Dragon Ball 01.cbz"),
    fichero("Mala/Barra/Serie 01.cbr"),
]


class ValidaTax(unittest.TestCase):
    def test_otro_subcarpeta_revisar_aviso(self):
        v = migrar._valida_tax(TAXONOMIA["."])
        self.assertEqual((v["categoria"], v["editorial"], v["serie"]), ("Otro", "Otros", "Novelas gráficas sueltas"))
        self.assertTrue(v["revisar"])
        self.assertEqual(v["aviso"], "Mezcla: repartir una a una.")
        self.assertIsNone(v["subcarpeta"])
        v = migrar._valida_tax(TAXONOMIA["1984 (Cómic)/Parte 1"])
        self.assertEqual(v["subcarpeta"], "Parte 1")
        self.assertFalse(v["revisar"])
        self.assertIsNone(v["aviso"])

    def test_manga_sin_editorial(self):
        v = migrar._valida_tax(TAXONOMIA["Dragon Ball/Arco 1"])
        self.assertEqual(v["editorial"], "")
        self.assertEqual(v["subcarpeta"], "Arco 01 - Infancia")

    def test_subcarpeta_con_barra_no_vale(self):
        self.assertIsNone(migrar._valida_tax(TAXONOMIA["Mala/Barra"]))

    def test_categoria_desconocida_no_vale(self):
        self.assertIsNone(migrar._valida_tax({"categoria": "Marciano", "serie_canonica": "X"}))


class Destino(unittest.TestCase):
    def test_subcarpeta_en_la_ruta(self):
        si = {"categoria": "Otro", "editorial": "Otros", "serie": "1984 (Fido Nesti)", "subcarpeta": "Parte 1"}
        self.assertEqual(migrar.destino_de(si, "1984 (Fido Nesti) 001", ".cbr"),
                         "Otro/Otros/1984 (Fido Nesti)/Parte 1/1984 (Fido Nesti) 001.cbr")
        si = {"categoria": "Manga", "editorial": "", "serie": "Dragon Ball", "subcarpeta": "Arco 01 - Infancia"}
        self.assertEqual(migrar.destino_de(si, "Dragon Ball 001", ".cbz"),
                         "Manga/Dragon Ball/Arco 01 - Infancia/Dragon Ball 001.cbz")

    def test_sin_subcarpeta_igual_que_antes(self):
        si = {"categoria": "Americano", "editorial": "Marvel", "serie": "Clara", "subcarpeta": None}
        self.assertEqual(migrar.destino_de(si, "Clara 001", ".cbr"), "Americano/Marvel/Clara/Clara 001.cbr")


class ResolverSeries(unittest.TestCase):
    def setUp(self):
        tax = {nombres.ruta_normalizada(k): v for k, v in TAXONOMIA.items()}
        self.info = migrar.resolver_series(CENSO, tax, {"primer_nivel": {}, "raiz": {}})

    def test_raiz_usa_la_entrada_de_la_carpeta_contenedor(self):
        v = self.info["file:300.cbr"]
        self.assertEqual((v["categoria"], v["editorial"], v["serie"], v["fuente"]),
                         ("Otro", "Otros", "Novelas gráficas sueltas", "taxonomia"))
        self.assertTrue(v["revisar"])
        self.assertEqual(v["aviso"], "Mezcla: repartir una a una.")

    def test_contenedor_dc_comics_solo_el_suelto(self):
        v = self.info["file:DC Comics/DC Comics - La Enciclopedia.pdf"]
        self.assertEqual((v["categoria"], v["editorial"], v["serie"]), ("Americano", "DC", "DC Comics - La Enciclopedia"))
        self.assertFalse(v["revisar"])
        # la subcarpeta Batman no hereda nada: heurística normal
        b = self.info["DC Comics/Batman"]
        self.assertEqual(b["fuente"], "heuristica")
        self.assertEqual((b["categoria"], b["editorial"]), ("Americano", "DC"))

    def test_subcarpetas_de_la_misma_serie_no_chocan(self):
        p1, p2 = self.info["1984 (Cómic)/Parte 1"], self.info["1984 (Cómic)/Parte 2"]
        self.assertEqual(p1["serie"], p2["serie"])
        self.assertEqual((p1["subcarpeta"], p2["subcarpeta"]), ("Parte 1", "Parte 2"))
        self.assertNotIn("nota", p1)
        self.assertNotIn("nota", p2)
        self.assertEqual(self.info["1984 (Cómic)"]["serie"], "1984 (Fido Nesti)")

    def test_revisar_en_carpeta(self):
        self.assertTrue(self.info["Marvel/Dudosa"]["revisar"])
        self.assertFalse(self.info["Marvel/Clara"]["revisar"])
        self.assertIsNone(self.info["Marvel/Clara"]["aviso"])

    def test_entrada_invalida_cae_a_heuristica(self):
        v = self.info["Mala/Barra"]
        self.assertEqual(v["fuente"], "heuristica")
        self.assertIsNone(v["subcarpeta"])


class PlanEnEspera(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mig-tax-")
        os.makedirs(os.path.join(self.tmp, "data", "metadatos"))
        with open(os.path.join(self.tmp, "data", "metadatos", "taxonomia.json"), "w", encoding="utf-8") as f:
            json.dump(TAXONOMIA, f, ensure_ascii=False)
        self.previo = {k: os.environ.get(k) for k in ("COMICS_DATA", "COMICS_ORIGEN")}
        os.environ["COMICS_DATA"] = os.path.join(self.tmp, "data")
        os.environ["COMICS_ORIGEN"] = os.path.join(self.tmp, "origen")
        self.cfg = migrar.Config()

    def tearDown(self):
        for k, v in self.previo.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v

    def test_pendientes_excluye_revisar_y_en_espera_los_recoge(self):
        plan = migrar.Plan(self.cfg, CENSO, None)
        pend = {f["ruta"] for f in plan.pendientes()}
        espera = {f["ruta"] for f in plan.en_espera()}
        self.assertEqual(espera, {"300.cbr", "Habibi.cbz", "Marvel/Dudosa/Dudosa 01.cbr", "Marvel/Dudosa/Dudosa 02.cbr"})
        self.assertFalse(pend & espera)
        self.assertEqual(len(pend) + len(espera), len(CENSO))
        # los destinos llevan la subcarpeta y la categoría «Otro»
        self.assertEqual(plan.nombres["1984 (Cómic)/Parte 1/1984 01.cbr"]["destino"].split("/")[:4],
                         ["Otro", "Otros", "1984 (Fido Nesti)", "Parte 1"])
        self.assertTrue(plan.nombres["Dragon Ball/Arco 1/Dragon Ball 01.cbz"]["destino"].startswith(
            "Manga/Dragon Ball/Arco 01 - Infancia/"))
        self.assertTrue(plan.nombres["300.cbr"]["destino"].startswith("Otro/Otros/Novelas gráficas sueltas/"))

    def test_base_vieja_gana_las_columnas_nuevas(self):
        import sqlite3
        os.makedirs(self.cfg.dir_estado, exist_ok=True)
        viejo = migrar.ESQUEMA.replace("    revisar INTEGER,            -- 1 = taxonomia.json pide la decisión de Jano: no entra en ninguna tanda\n", "")
        viejo = viejo.replace("    aviso TEXT,                 -- motivo de ese «revisar»\n", "")
        self.assertNotIn("revisar", viejo)
        c = sqlite3.connect(self.cfg.db)
        c.executescript(viejo)
        c.close()
        conn = migrar.abrir_db(self.cfg)
        try:
            cols = {r[1] for r in conn.execute("PRAGMA table_info(ficheros)")}
        finally:
            conn.close()
        self.assertIn("revisar", cols)
        self.assertIn("aviso", cols)


if __name__ == "__main__":
    unittest.main()
