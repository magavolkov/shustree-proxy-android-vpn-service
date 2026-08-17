package ru.shustree.shustreeproxy.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.accompanist.systemuicontroller.rememberSystemUiController

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF011B4D),
    surface = Color(0xFF011B4D)
)

@Composable
fun ShustreeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    // --- START OF CORRECTED AGGRESSIVE FIX ---

    // 1. The Modern Accompanist Way (Corrected)
    // We call rememberSystemUiController at the top level of the composable.
    val systemUiController = rememberSystemUiController()

    SideEffect {
        // Now we use the controller we "remembered"
        systemUiController.setSystemBarsColor(
            color = Color.Transparent,
            isNavigationBarContrastEnforced = false // Explicitly disable scrim
        )
        systemUiController.statusBarDarkContentEnabled = false
        systemUiController.navigationBarDarkContentEnabled = false

        // 2. The Classic Android View Way (still a good fallback)
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.navigationBarColor = Color.Transparent.toArgb()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    // --- END OF CORRECTED AGGRESSIVE FIX ---

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
