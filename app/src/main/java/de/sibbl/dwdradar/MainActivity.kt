package de.sibbl.dwdradar

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import de.sibbl.dwdradar.ui.RadarApp
import de.sibbl.dwdradar.ui.RadarViewModel

class MainActivity : ComponentActivity() {
    private val radarViewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    RadarApp(viewModel = radarViewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        radarViewModel.onAppOpened()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val axisScroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (axisScroll != 0f) {
                val scrollFactor = ViewConfiguration.get(this).scaledVerticalScrollFactor
                val deltaTicks = if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    -axisScroll
                } else {
                    -axisScroll / (if (scrollFactor != 0f) scrollFactor else 1f)
                }
                radarViewModel.scrubByRotary(deltaTicks)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
