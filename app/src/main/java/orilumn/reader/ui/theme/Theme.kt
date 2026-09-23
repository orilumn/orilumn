package orilumn.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * App root theme (M0 uses Material3 defaults for now; customize as needed later).
 */
@Composable
fun OrilumnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        content = content,
    )
}