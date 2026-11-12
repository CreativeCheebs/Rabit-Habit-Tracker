package app.habitlab.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collect

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onBack: () -> Unit,
) {
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { onProgress(it.progress) }
            onBack()
        } finally {
            onProgress(0f)
        }
    }
}
