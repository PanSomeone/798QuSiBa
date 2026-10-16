package com.killhuishenghuo.testing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.killhuishenghuo.ui.components.WaterAmbienceBox
import com.killhuishenghuo.ui.theme.KillHuiShengHuoTheme

/**
 * A bare activity that shows the ambience and nothing else.
 *
 * Exists because the ambience cannot be measured through `createComposeRule`. That
 * rule owns the frame clock and waits for idle on the way out, and an effect whose
 * whole job is to keep running while the water is on is never idle — the water-on
 * case fails teardown with `ComposeNotIdleException` no matter what it asserted.
 *
 * Here the frame clock is the platform's, the frames counted are the ones the real
 * render pipeline actually produced, and nothing is waiting for quiescence.
 *
 * Lives in `src/debug` and is declared in the debug manifest, so it is not in the
 * shipped APK.
 */
class AmbienceHostActivity : ComponentActivity() {

    /** Which of the two states to show on launch. */
    private val waterOn = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        waterOn.value = intent.getBooleanExtra(ExtraWaterOn, false)

        setContent {
            KillHuiShengHuoTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    WaterAmbienceBox(
                        active = waterOn.value,
                        // A button-sized rectangle in the middle of the page, so the
                        // steam has somewhere to rise from. The draw lambda bails out
                        // on an empty one, which would make a "water on" run produce no
                        // frames and quietly look like a pass.
                        buttonBounds = Rect(
                            left = 120f,
                            top = 900f,
                            right = 960f,
                            bottom = 1140f,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Empty on purpose: this activity measures the ambience and
                        // nothing else, and content here would add frames of its own.
                    }
                }
            }
        }
    }

    companion object {
        const val ExtraWaterOn = "waterOn"
    }
}
