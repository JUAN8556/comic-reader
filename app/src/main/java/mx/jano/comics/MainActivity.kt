package mx.jano.comics

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mx.jano.comics.probe.SondaScreen
import mx.jano.comics.ui.theme.ComicsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // La paleta de Cover es siempre oscura: iconos claros en las barras pase lo que pase con el tema del sistema.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ComicsTheme {
                SondaScreen()
            }
        }
    }
}
