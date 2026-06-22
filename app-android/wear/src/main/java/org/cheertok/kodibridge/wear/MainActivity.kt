package org.cheertok.kodibridge.wear

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private lateinit var messenger: KodiMessenger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantén la pantalla encendida mientras el mando está abierto (accesibilidad:
        // evita que se apague a media navegación).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        messenger = KodiMessenger(this, lifecycleScope)
        setContent { RemoteScreen(messenger) }
    }

    override fun onStart() {
        super.onStart()
        messenger.start()
    }

    override fun onStop() {
        messenger.stop()
        super.onStop()
    }
}
