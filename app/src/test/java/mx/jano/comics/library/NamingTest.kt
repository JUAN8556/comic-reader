package mx.jano.comics.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class NamingTest {

    private fun sorted(vararg names: String) = names.toList().sortedWith(Naming.natural)

    @Test fun numeros_por_valor_no_por_texto() {
        assertEquals(
            listOf("Serie 1.cbr", "Serie 2.cbr", "Serie 10.cbr", "Serie 100.cbr"),
            sorted("Serie 10.cbr", "Serie 100.cbr", "Serie 2.cbr", "Serie 1.cbr"),
        )
        assertEquals(
            listOf("Marvel 2099 01.cbr", "Marvel 2099 02.cbr", "Marvel 2099 10.cbr"),
            sorted("Marvel 2099 10.cbr", "Marvel 2099 02.cbr", "Marvel 2099 01.cbr"),
        )
    }

    @Test fun insensible_a_mayusculas_y_acentos() {
        assertTrue(Naming.natural.compare("Ángel", "angel 2") < 0)
        assertTrue(Naming.natural.compare("última", "Uncanny") < 0) // «ultima» < «uncanny»
        assertEquals(
            listOf("Spider-man La Última Cacería de Kraven #04.cbr", "spider-man la ultima caceria de kraven #05.cbr"),
            sorted("spider-man la ultima caceria de kraven #05.cbr", "Spider-man La Última Cacería de Kraven #04.cbr"),
        )
        // NFD (de la Mac) y NFC ordenan juntos.
        val nfd = Normalizer.normalize("Cacería 2", Normalizer.Form.NFD)
        assertTrue(Naming.natural.compare(nfd, "Caceria 10") < 0)
        assertTrue(Naming.natural.compare("Caceria 1", nfd) < 0)
    }

    @Test fun almohadilla_ignorada() {
        assertEquals(
            listOf("Kraven #3", "Kraven 4", "Kraven #10"),
            sorted("Kraven #10", "Kraven 4", "Kraven #3"),
        )
    }

    @Test fun orden_total_y_estable() {
        // Mismo valor y misma clave: deciden los acentos y luego la cadena; nunca 0 si son distintas.
        assertTrue(Naming.natural.compare("Tomo 01", "Tomo 1") != 0)
        assertTrue(Naming.natural.compare("Tomo 1", "Tomo 01") == -Naming.natural.compare("Tomo 01", "Tomo 1"))
        assertTrue(Naming.natural.compare("Ángel", "Angel") != 0)
        assertEquals(0, Naming.natural.compare("Igual", "Igual"))
    }

    @Test fun numeros_muy_largos_no_desbordan() {
        assertTrue(Naming.natural.compare("x 99999999999999999999999", "x 100000000000000000000000") < 0)
    }

    @Test fun ordena_los_nombres_reales() {
        val names = listOf(
            "Naruto - Tomo 54 (#505-514).cbr",
            "Naruto - Tomo 6.cbr",
            "Naruto - Tomo 12 (#99-107).cbr",
        )
        assertEquals(
            listOf("Naruto - Tomo 6.cbr", "Naruto - Tomo 12 (#99-107).cbr", "Naruto - Tomo 54 (#505-514).cbr"),
            names.sortedWith(Naming.natural),
        )
        assertEquals(
            listOf("Serie 001 (1999).cbr", "Serie 002 (1999).cbr", "Serie 010 (2000).cbr"),
            sorted("Serie 010 (2000).cbr", "Serie 001 (1999).cbr", "Serie 002 (1999).cbr"),
        )
    }

    // ------------------------------------------------------------------ smartName

    @Test fun smart_name_con_los_nombres_reales() {
        val cases = listOf(
            Triple("Marvel 2099 01.cbr", "Marvel 2099", "01"),
            Triple("Hulk.La Caida Del Panteon 01.cbr", "Hulk.La Caida Del Panteon", "01"),
            Triple("Hulk.La Caida Del Panteon 01.cbr", "Hulk - La Caída del Panteón (Forum)", "01"),
            Triple("00 Guardians of The Galaxy 07.cbr", "Guardians of the Galaxy", "07"),
            Triple("00 Guardians of The Galaxy 07.cbr", "00 Guardians of The Galaxy", "07"),
            Triple("Naruto - Tomo 54 (#505-514).cbr", "Naruto", "Tomo 54 (#505-514)"),
            Triple("Condorito 335 1999.cbr", "Condorito", "335 1999"),
            Triple("Death Note Tomo 12.cbz", "Death Note", "Tomo 12"),
            Triple("Spider-man La Última Cacería de Kraven #04.cbr", "Spider-Man La Ultima Caceria de Kraven", "#04"),
            Triple("Spider-man La Última Cacería de Kraven #04.cbr", "Spider-Man", "La Última Cacería de Kraven #04"),
            Triple("Iron Man - Los Años Perdidos 1 (Forum).cbr", "Iron Man - Los Años Perdidos", "1 (Forum)"),
            Triple("Iron Man - Los Años Perdidos 1 (Forum).cbr", "Iron Man", "Los Años Perdidos 1 (Forum)"),
            Triple("Serie 001 (1999).cbr", "Serie", "001 (1999)"),
            Triple("Uncanny X-Men 174, October 1983.cbr", "Uncanny X-Men", "174, October 1983"),
        )
        for ((file, folder, expected) in cases) {
            assertEquals("«$file» en «$folder»", expected, Naming.smartName(file, folder))
        }
    }

    @Test fun smart_name_sin_prefijo_deja_el_nombre_sin_extension() {
        assertEquals("Death Note Tomo 12", Naming.smartName("Death Note Tomo 12.cbz", "Manga"))
        assertEquals("Hulk.La Caida Del Panteon 01", Naming.smartName("Hulk.La Caida Del Panteon 01.cbr", "Marvel"))
        assertEquals("Condorito 335 1999", Naming.smartName("Condorito 335 1999.cbr", ""))
    }

    @Test fun smart_name_frontera_de_palabra() {
        assertEquals("Batman 01", Naming.smartName("Batman 01.cbr", "Bat"))
        assertEquals("01", Naming.smartName("Batman 01.cbr", "batman"))
    }

    @Test fun smart_name_si_no_queda_nada_deja_el_nombre() {
        assertEquals("Batman Year One", Naming.smartName("Batman Year One.cbz", "Batman Year One"))
        assertEquals("Batman Year One", Naming.smartName("Batman Year One.pdf", "Batman - Year One"))
    }

    @Test fun smart_name_numero_unico_con_anio_deja_el_nombre() {
        // Patrón «número único» de la migración: «Título/Título (1999).cbz».
        assertEquals("Título (1999)", Naming.smartName("Título (1999).cbz", "Título"))
        assertEquals("Watchmen (1986)", Naming.smartName("Watchmen (1986).cbz", "Watchmen"))
        assertEquals("Watchmen [DC] (1986)", Naming.smartName("Watchmen [DC] (1986).cbz", "Watchmen"))
        // Con un número de orden delante, igual.
        assertEquals("01 Watchmen (1986)", Naming.smartName("01 Watchmen (1986).cbz", "Watchmen"))
        // Lo que no es solo coletilla se sigue recortando.
        assertEquals("001 (1999)", Naming.smartName("Serie 001 (1999).cbr", "Serie"))
    }

    @Test fun smart_name_extensiones_y_guiones_bajos() {
        assertEquals("Batman 01 (2011)", Naming.smartName("Batman_01_(2011).CBZ", "Otros"))
        assertEquals("01 (2011)", Naming.smartName("Batman_01_(2011).cbz", "Batman"))
        // Un punto que no es extensión de cómic no se corta.
        assertEquals("Vol.12", Naming.smartName("Vol.12", "Otros"))
        assertEquals("Tomo 1", Naming.smartName("Saga.Tomo 1.pdf", "Saga"))
    }

    @Test fun smart_name_nfd_del_fichero_y_del_estante() {
        val file = Normalizer.normalize("Iron Man - Los Años Perdidos 1 (Forum).cbr", Normalizer.Form.NFD)
        val folder = Normalizer.normalize("Iron Man - Los Años Perdidos", Normalizer.Form.NFD)
        assertEquals("1 (Forum)", Naming.smartName(file, folder))
        val nfd = Normalizer.normalize("La Última Cacería de Kraven #04", Normalizer.Form.NFD)
        assertEquals("La Última Cacería de Kraven #04", Naming.smartName("Spider-man $nfd.cbr", "Spider-man"))
    }

    // ------------------------------------------------------------------ número del ejemplar

    @Test fun numero_del_ejemplar() {
        assertEquals(1, SmartNames.issueNumber("Marvel 2099 01.cbr", "Marvel 2099"))
        assertEquals(1, SmartNames.issueNumber("Marvel 2099 01.cbr", "Otros")) // 2099 parece un año
        assertEquals(54, SmartNames.issueNumber("Naruto - Tomo 54 (#505-514).cbr", "Naruto"))
        assertEquals(335, SmartNames.issueNumber("Condorito 335 1999.cbr", "Condorito"))
        assertEquals(12, SmartNames.issueNumber("Death Note Tomo 12.cbz", "Death Note"))
        assertEquals(4, SmartNames.issueNumber("Spider-man La Última Cacería de Kraven #04.cbr", "Spider-man"))
        assertEquals(1, SmartNames.issueNumber("Iron Man - Los Años Perdidos 1 (Forum).cbr", "Iron Man - Los Años Perdidos"))
        assertEquals(1, SmartNames.issueNumber("Serie 001 (1999).cbr", "Serie"))
        assertEquals(174, SmartNames.issueNumber("Uncanny X-Men 174, October 1983.cbr", "Uncanny X-Men"))
        assertEquals(7, SmartNames.issueNumber("00 Guardians of The Galaxy 07.cbr", "Guardians of the Galaxy"))
        assertNull(SmartNames.issueNumber("Batman Year One.cbz", "Batman"))
        // Un año solo no es un número de ejemplar.
        assertNull(SmartNames.issueNumber("Título (1999).cbz", "Título"))
        assertNull(SmartNames.issueNumber("Watchmen (1986).cbz", "Watchmen"))
        assertNull(SmartNames.issueNumber("Otros (2001).cbz", "Otros"))
    }

    @Test fun ruta_en_orden_natural_por_segmentos() {
        val paths = listOf("Serie 2/01.cbr", "Serie/10.cbr", "Serie/2.cbr", "Serie 10/01.cbr")
        assertEquals(
            listOf("Serie/2.cbr", "Serie/10.cbr", "Serie 2/01.cbr", "Serie 10/01.cbr"),
            paths.sortedWith(PathOrder),
        )
    }
}
