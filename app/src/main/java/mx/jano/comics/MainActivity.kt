package mx.jano.comics

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mx.jano.comics.reader.VolumeKeyBus
import mx.jano.comics.ui.ComicsRoot
import mx.jano.comics.ui.LocalAppGraph
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.theme.ComicsTheme
import mx.jano.comics.ui.theme.ThemeResolver

class MainActivity : ComponentActivity() {
    private val app: ComicsApp get() = application as ComicsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        // Los dos temas son oscuros: iconos claros en las barras pase lo que pase con el tema del sistema.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = app.graph
        val env = app.ui
        setContent {
            val appSettings by graph.settings.app.collectAsStateWithLifecycle()
            val ink by env.inkMode.values.collectAsStateWithLifecycle()
            val prefs by env.prefs.state.collectAsStateWithLifecycle()
            val highContrast = ThemeResolver.useHighContrast(appSettings.theme, ink, prefs.inkPaperValues)
            CompositionLocalProvider(LocalAppGraph provides graph, LocalUiEnv provides env) {
                ComicsTheme(highContrast = highContrast) {
                    ComicsRoot()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Por si el modo NXTPAPER cambió sin que el ContentObserver avisara mientras la app estaba detrás.
        app.ui.inkMode.refresh()
    }

    /**
     * Teclas de volumen para el lector: mientras [VolumeKeyBus.consume] (el lector en pantalla con la opción activa)
     * se tragan las dos fases de la tecla y la pulsación inicial pasa página (+1 arriba, -1 abajo); sin repetición
     * al mantenerla. Fuera del lector el volumen funciona como siempre.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val delta = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> +1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> 0
        }
        if (delta != 0 && VolumeKeyBus.consume) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) VolumeKeyBus.offer(delta)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
