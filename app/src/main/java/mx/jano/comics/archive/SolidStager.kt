package mx.jano.comics.archive

import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Caché de páginas para archivos SÓLIDOS (RAR4 sólido y RAR5 sólido).
 *
 * Qué hace junrar 8.1.2 (leído con `javap -c` en `Archive.extractFile`): en un sólido guarda el índice de la última
 * entrada descomprimida. Si se pide una entrada POSTERIOR, descomprime las de en medio a un NullOutputStream y sigue;
 * si se pide una ANTERIOR o la misma, reinicia el descompresor y vuelve a descomprimir desde la primera entrada.
 * Ir hacia atrás página a página (o en un orden natural distinto del orden físico) es O(n) por página y O(n²) en total.
 *
 * Mitigación: al pedir la página k se descomprimen EN ORDEN FÍSICO todas las entradas hasta la de k y cada página que
 * pasa por delante se deja en [dir] (una vez). Así cada entrada se descomprime una sola vez mientras el archivo esté
 * abierto: hacia atrás, saltos y orden natural ≠ físico son aciertos de caché. Si alguien borra una página ya dejada,
 * se rebobina (el motor reinicia solo) y se vuelve a dejar lo que falte.
 *
 * No es seguro entre hilos: quien lo usa lo llama con su lock tomado.
 *
 * @param pageEntryIndex página (orden natural) → posición de su entrada en el orden físico del archivo.
 * @param decompress descomprime la entrada física [Int] a la salida; se llama en orden creciente salvo al rebobinar.
 */
internal class SolidStager(
    private val pageEntryIndex: IntArray,
    private val decompress: (entryIndex: Int, out: OutputStream) -> Unit,
) {
    /** Páginas ordenadas por su posición física. */
    private val pagesInArchiveOrder: IntArray =
        pageEntryIndex.indices.sortedBy { pageEntryIndex[it] }.toIntArray()

    /** Posición física de la última entrada que se pidió al motor (−1 = ninguna o hay que rebobinar). */
    private var cursor = -1

    /** Entradas que se han pedido al motor (diagnóstico y pruebas). */
    var decompressCalls = 0
        private set

    fun stagedFile(dir: File, page: Int): File = File(dir, String.format(Locale.ROOT, "%05d.page", page))

    /** Devuelve el fichero de [dir] con la página [page], descomprimiendo lo que haga falta. */
    fun stage(dir: File, page: Int): File {
        val target = stagedFile(dir, page)
        if (target.isFile) return target
        val goal = pageEntryIndex[page]
        if (goal <= cursor) cursor = -1 // la página se perdió de la caché: hay que rebobinar
        try {
            for (p in pagesInArchiveOrder) {
                val entry = pageEntryIndex[p]
                if (entry <= cursor) continue
                if (entry > goal) break
                val file = stagedFile(dir, p)
                // Las que ya están en la caché no se piden: el motor las salta (descomprime a la nada) al pedir la siguiente.
                if (entry == goal || !file.isFile) {
                    writeAtomically(file) { out -> decompress(entry, out) }
                    decompressCalls++
                    cursor = entry
                }
            }
        } catch (t: Throwable) {
            // junrar, tras un fallo, se reinicia en la siguiente llamada; el cursor tiene que pensar lo mismo.
            cursor = -1
            throw t
        }
        return target
    }
}
