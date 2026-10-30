import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import nombres as n  # noqa: E402


class Deleet(unittest.TestCase):
    def test_palabras_disfrazadas(self):
        casos = {
            "C0M1CS D3L PL4N3T4": "COMICS DEL PLANETA",
            "R0b0t G1g4nt3": "Robot Gigante",
            "Reina 4ntártica": "Reina Antártica",
            "N0ch3 4zul": "Noche Azul",
            "Ecos de Esperanz4": "Ecos de Esperanza",
            "Z-M3n": "Z-Men",
            "V3l0c1d4d": "Velocidad",
            "Canci0n3s": "Canciones",
        }
        for leet, limpio in casos.items():
            self.assertEqual(n.deleet(leet), limpio, leet)

    def test_numeros_de_verdad_no_se_tocan(self):
        for s in ("Vol1", "vol3", "Tomo2", "nº06", "Nº05b", "1de4", "4de4", "nº1de3", "2099",
                  "Spiderman2099", "17mo", "2da", "600a", "Arcano9", "estado3", "IMPACTO2", "14AU",
                  "G.0.W.#01", "Raven02", "T01", "300"):
            self.assertEqual(n.deleet(s), s, s)


class Limpieza(unittest.TestCase):
    def test_nfc_y_espacios(self):
        nfd = "Cómics".replace("ó", "ó") + " "
        self.assertEqual(n.componente_limpio(nfd), "Cómics")
        self.assertEqual(n.ruta_normalizada(nfd + "/Serie  Doble /a.cbr"), "Cómics/Serie Doble/a.cbr")

    def test_limpiar(self):
        self.assertEqual(n.limpiar("Acceso_Total_1de4"), "Acceso Total 1de4")
        self.assertEqual(n.limpiar("Superman _ Batman  Anual"), "Superman - Batman Anual")

    def test_serie_de_carpeta(self):
        self.assertEqual(n.serie_de_carpeta("R0b0t - Los Años Grises (Forum)"), "Robot - Los Años Grises")
        self.assertEqual(n.serie_de_carpeta("Canci0n3s 1-3"), "Canciones")
        self.assertEqual(n.serie_de_carpeta("Rayo Azul #1-5"), "Rayo Azul")
        self.assertEqual(n.serie_de_carpeta("Sombra y Luz Vol.2"), "Sombra y Luz Vol. 2")
        self.assertEqual(n.serie_de_carpeta("N0ch3 4zul Vol1 (2009-2010)"), "Noche Azul Vol. 1")
        self.assertEqual(n.serie_de_carpeta("Tiras (10 libros)"), "Tiras")
        self.assertEqual(n.serie_de_carpeta("(SERIE EN SEPIA)"), "SERIE EN SEPIA")

    def test_carpeta_generica(self):
        for s in ("Parte 1", "ARCO 01 – INFANCIA", "2. Saga Algo", "Crossovers", "Especiales"):
            self.assertTrue(n.es_carpeta_generica(s), s)
        self.assertFalse(n.es_carpeta_generica("Batman Vol. 2"))


class Interpretar(unittest.TestCase):
    def nombre(self, stem, serie, k=10, raiz=False):
        return n.interpretar(stem, serie, k, raiz=raiz)

    def test_patron_basico(self):
        r = self.nombre("Titan.La Caida Del Olimpo 01", "Titán - La Caída del Olimpo", 8)
        self.assertTrue(r["interpretado"])
        self.assertEqual(r["base"], "Titán - La Caída del Olimpo 001")

    def test_miniserie_con_arco(self):
        r = self.nombre("Robot - Los Años Grises 1 (Forum)", "Robot - Los Años Grises", 4)
        self.assertEqual(r["base"], "Robot - Los Años Grises 001")

    def test_numero_y_anio(self):
        self.assertEqual(self.nombre("Serie 001 1955", "Serie")["base"], "Serie 001 (1955)")
        self.assertEqual(self.nombre("Serie #84 (2011)", "Serie")["base"], "Serie 084 (2011)")
        self.assertEqual(self.nombre("Serie 2003 #94 (2012)", "Serie")["base"], "Serie 094 (2012)")

    def test_tomos_y_marcas(self):
        self.assertEqual(self.nombre("Serie Tomo 12", "Serie")["base"], "Serie 012")
        self.assertEqual(self.nombre("Serie Vol 07", "Serie")["base"], "Serie 007")
        self.assertEqual(self.nombre("Capítulo 1", "Serie")["base"], "Serie 001")
        self.assertEqual(self.nombre("Serie nº06", "Serie")["base"], "Serie 006")
        self.assertEqual(self.nombre("Serie 01 de 03", "Serie")["base"], "Serie 001")
        self.assertEqual(self.nombre("Serie_Doble_2de4", "Serie Doble")["base"], "Serie Doble 002")
        self.assertEqual(self.nombre("01", "Serie Vol. 1")["base"], "Serie Vol. 1 001")
        self.assertEqual(self.nombre("Serie #0.1", "Serie")["base"], "Serie 000.1")

    def test_volumen_de_la_serie(self):
        self.assertEqual(self.nombre("Batman #01", "Batman Vol. 2")["base"], "Batman Vol. 2 001")
        self.assertEqual(self.nombre("Sombra Y Luz  vol1  -  02", "Sombra y Luz Vol. 1")["base"],
                         "Sombra y Luz Vol. 1 002")
        self.assertEqual(self.nombre("050 Serie Vol.2", "Serie Vol. 2")["base"], "Serie Vol. 2 050")

    def test_etiquetas_fuera(self):
        self.assertEqual(self.nombre("Serie 001 [MQ][CRG]", "Serie")["base"], "Serie 001")
        self.assertEqual(self.nombre("Serie (Colección) 07", "Serie")["base"], "Serie 007")

    def test_numero_unico(self):
        r = self.nombre("Viajero - Extraños (Forum)", "Viajero - Extraños", 1)
        self.assertTrue(r["interpretado"])
        self.assertEqual(r["base"], "Viajero - Extraños")
        r = self.nombre("Un Título (1999)", "Un Título", 1)
        self.assertEqual(r["base"], "Un Título (1999)")

    def test_suelto_en_raiz(self):
        r = self.nombre("300 - Autor", "300 - Autor", 1, raiz=True)
        self.assertEqual(r["base"], "300 - Autor")

    def test_conservador(self):
        # el título no es la serie: se conserva el nombre limpio
        for stem, serie, motivo in [
            ("001 Las sombras del río - Escaneador", "Leyendas", "el título no coincide con la serie"),
            ("Otra Serie #01", "Serie", "el título no coincide con la serie"),
            ("Serie 01-09", "Serie", "rango de números"),
            ("Serie - Tomo 54 (#505-514)", "Serie", "cifras entre paréntesis"),
            ("Serie #12 Otra #196", "Serie", "varios números"),
            ("7.- Otra Serie #462", "Evento Grande", "cifras sueltas"),
        ]:
            r = self.nombre(stem, serie)
            self.assertFalse(r["interpretado"], stem)
            self.assertEqual(r["motivo"], motivo, stem)
            self.assertEqual(r["base"], n.limpiar(stem))

    def test_anio_no_es_numero(self):
        r = self.nombre("1984", "1984 - Autor", 3)
        self.assertFalse(r["interpretado"])
        self.assertEqual(r["base"], "1984")

    def test_leet_en_el_nombre_conservado(self):
        r = self.nombre("Archivos R0b0t 1 - El Regreso", "Archivos")
        self.assertEqual(r["base"], "Archivos Robot 1 - El Regreso")

    def test_extension_pegada(self):
        r = self.nombre("Serie #12cbr", "Serie")
        self.assertEqual(r["base"], "Serie 012")


class Utilidades(unittest.TestCase):
    def test_formatear_numero(self):
        self.assertEqual(n.formatear_numero("1"), "001")
        self.assertEqual(n.formatear_numero("05b"), "005b")
        self.assertEqual(n.formatear_numero("1000"), "1000")

    def test_recortar(self):
        largo = "x" * 300
        r = n.recortar_nombre(largo, ".cbz")
        self.assertTrue(r.endswith("….cbz"))
        self.assertLessEqual(len(r.encode()), 200)

    def test_componente_seguro(self):
        self.assertEqual(n.componente_seguro("a/b"), "a∕b")
        self.assertEqual(n.componente_seguro(".."), "_")

    def test_orden_natural(self):
        xs = ["Serie 10.cbz", "Serie 2.cbz", "serie 1.cbz"]
        self.assertEqual(sorted(xs, key=n.clave_natural), ["serie 1.cbz", "Serie 2.cbz", "Serie 10.cbz"])


if __name__ == "__main__":
    unittest.main()
