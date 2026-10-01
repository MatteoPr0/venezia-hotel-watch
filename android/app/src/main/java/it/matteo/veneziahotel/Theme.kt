package it.matteo.veneziahotel

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF0F0D0B)
    val Surface = Color(0xFF1A1714)
    val Surface2 = Color(0xFF25211C)
    val Line = Color(0xFF332D26)
    val Gold = Color(0xFFD4AF5F)
    val GoldDim = Color(0xFF8C7440)
    val Text = Color(0xFFF3EDE2)
    val Muted = Color(0xFFA39A8C)
    val Faint = Color(0xFF6E665B)
    val Good = Color(0xFF8BC39A)
    val Warn = Color(0xFFE0A85A)
    val Bad = Color(0xFFD9806A)
    val Lido = Color(0xFF6FB3AA)
    val Centro = Color(0xFFD2957A)
}

fun zoneColor(z: String) = if (z == "lido") C.Lido else C.Centro

val Serif = FontFamily.Serif

private val typo = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 44.sp),
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp),
)

@Composable
fun VeneziaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Gold,
            onPrimary = C.Bg,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.Surface2,
            onSurfaceVariant = C.Muted,
            surfaceContainer = C.Surface,
            surfaceContainerLow = C.Surface,
            surfaceContainerHigh = C.Surface2,
            outline = C.Line,
            secondaryContainer = C.Surface2,
            onSecondaryContainer = C.Gold,
        ),
        typography = typo,
        content = content,
    )
}
