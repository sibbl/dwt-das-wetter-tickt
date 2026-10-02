package net.sibbl.dwt.phone

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

class CompanionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_companion)
        findViewById<View>(R.id.companion_scroll).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            requestApplyInsets()
        }
    }
}
