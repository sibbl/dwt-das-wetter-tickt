package net.sibbl.dwt

import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class AboutActivity : ComponentActivity() {
    private var aboutScrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AboutScreen(
                    onScrollViewReady = { scrollView ->
                        aboutScrollView = scrollView
                    }
                )
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val axisScroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (axisScroll != 0f && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                aboutScrollView?.scrollBy(0, (-axisScroll * ABOUT_NATIVE_SCROLL_PIXELS).toInt())
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}

@Composable
private fun AboutScreen(
    onScrollViewReady: (ScrollView) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ScrollView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isVerticalScrollBarEnabled = true
                    isScrollbarFadingEnabled = false
                    scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(
                        ComposeView(context),
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    onScrollViewReady(this)
                    post { requestFocus() }
                }
            },
            update = { scrollView ->
                onScrollViewReady(scrollView)
                val composeView = scrollView.getChildAt(0) as ComposeView
                composeView.setContent {
                    MaterialTheme {
                        AboutContent()
                    }
                }
            }
        )
    }
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 22.dp, vertical = 28.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color.White, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = context.getString(R.string.app_name),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 18.sp
                )
            )
            Text(
                text = context.getString(R.string.about_version_format, BuildConfig.VERSION_NAME),
                color = Color(0xFFC7D4E2),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
            )
            Text(
                text = context.getString(R.string.about_made_by),
                color = Color(0xFFEAF6FF),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 14.sp)
            )
            Text(
                text = context.getString(R.string.about_github),
                color = Color(0xFF8DEEFF),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
            )
            Text(
                text = context.getString(R.string.about_data_source),
                color = Color(0xFFB0BDCA),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
    }
}

private const val ABOUT_NATIVE_SCROLL_PIXELS = 48f
