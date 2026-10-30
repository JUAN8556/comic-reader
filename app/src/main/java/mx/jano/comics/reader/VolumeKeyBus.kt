package mx.jano.comics.reader

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * CONTRATO COMÚN del MVP. Puente entre la Activity y el lector para las teclas de volumen.
 * MainActivity (frente ui-library) llama a [offer] desde dispatchKeyEvent para KEYCODE_VOLUME_UP/DOWN cuando [consume]
 * es true y devuelve true (la tecla no cambia el volumen). El lector (frente ui-reader) pone [consume] mientras está en
 * pantalla y escucha [events]: +1 = página siguiente, -1 = anterior (ya en el sentido de lectura).
 */
object VolumeKeyBus {
    @Volatile var consume: Boolean = false
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    /** [delta] +1 (volumen arriba) o -1 (abajo). Devuelve true si el lector lo consume. */
    fun offer(delta: Int): Boolean = if (consume) events.tryEmit(delta) || true else false
}
