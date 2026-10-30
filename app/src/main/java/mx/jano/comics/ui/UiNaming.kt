package mx.jano.comics.ui

import mx.jano.comics.library.Naming

/**
 * Orden natural y nombre inteligente para la interfaz. Delegan en [Naming] (frente «data»); si aún no está
 * implementado o falla, caen a una versión sencilla propia: la biblioteca no se rompe nunca por un nombre.
 */
object UiNaming {
    val natural: Comparator<String> by lazy {
        try {
            Naming.natural.compare("a 2", "a 10")
            Naming.natural
        } catch (t: Throwable) {
            FallbackNatural
        }
    }

    /** «Uncanny X-Men 174, October 1983.cbr» en «Uncanny X-Men» → «174, October 1983» (si [Naming] lo sabe hacer). */
    fun smartName(fileName: String, folderName: String): String = try {
        Naming.smartName(fileName, folderName).ifBlank { stripExtension(fileName) }
    } catch (t: Throwable) {
        stripExtension(fileName)
    }

    /** Quita una extensión corta («.cbz», «.pdf»); deja «Vol. 2», «.indice» y los nombres sin extensión. */
    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val ext = name.substring(dot + 1)
        val looksLikeExtension = ext.length in 1..4 && ext.all { it.isLetterOrDigit() } && ext.any { it.isLetter() }
        return if (looksLikeExtension) name.substring(0, dot) else name
    }
}

/** Comparador natural mínimo: 1 < 2 < 10, sin distinguir mayúsculas ni acentos; desempata por el texto tal cual. */
object FallbackNatural : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = TextNorm.fold(a)
        val y = TextNorm.fold(b)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                val si = i
                while (i < x.length && x[i].isDigit()) i++
                val sj = j
                while (j < y.length && y[j].isDigit()) j++
                val nx = x.substring(si, i).trimStart('0')
                val ny = y.substring(sj, j).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
            } else {
                if (cx != cy) return cx - cy
                i++
                j++
            }
        }
        val rest = (x.length - i) - (y.length - j)
        if (rest != 0) return rest
        return a.compareTo(b)
    }
}
