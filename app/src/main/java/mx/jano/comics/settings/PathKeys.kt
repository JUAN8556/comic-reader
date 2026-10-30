package mx.jano.comics.settings

import java.text.Normalizer

/**
 * Rutas relativas a la raíz de la biblioteca usadas como clave (tabla de RTL, índice, metadatos).
 *
 * Forma canónica: NFC (lo que sube la Mac llega a veces en NFD), separador «/», sin «/» al principio ni al final,
 * sin segmentos vacíos ni «.». La raíz es «».
 */
internal object PathKeys {

    fun normalize(path: String): String {
        val nfc = Normalizer.normalize(path, Normalizer.Form.NFC)
        if (nfc.isEmpty()) return ""
        return nfc.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
    }

    /** Carpeta que contiene [path] («A/B/c.cbr» → «A/B»; «c.cbr» → «»; «» → null). Espera una ruta normalizada. */
    fun parent(path: String): String? = when {
        path.isEmpty() -> null
        '/' !in path -> ""
        else -> path.substringBeforeLast('/')
    }

    /** [path] y todas sus carpetas hacia arriba, hasta la raíz («») incluida. Espera una ruta normalizada. */
    fun selfAndAncestors(path: String): Sequence<String> = generateSequence(path) { parent(it) }

    /**
     * Un nombre de Drive como segmento de ruta. Drive admite «/» dentro de un nombre; aquí se cambia por «／»
     * (U+FF0F), que es lo mismo que hace rclone al subir, para que las rutas de la app y las del índice de la
     * migración coincidan. Además NFC.
     */
    fun segment(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).replace('/', '／')

    fun join(parent: String, name: String): String = if (parent.isEmpty()) segment(name) else parent + "/" + segment(name)
}
