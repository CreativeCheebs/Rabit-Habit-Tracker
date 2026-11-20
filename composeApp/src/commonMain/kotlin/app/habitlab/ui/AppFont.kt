package app.habitlab.ui

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.habitlab.resources.Res
import app.habitlab.resources.plus_jakarta_sans_bold
import app.habitlab.resources.plus_jakarta_sans_extrabold
import app.habitlab.resources.plus_jakarta_sans_medium
import app.habitlab.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

/** Plus Jakarta Sans (SIL OFL 1.1, licence in composeResources/files), bundled as static weight instances. */
@Composable internal fun appFontFamily(): FontFamily {
    val medium = Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold)
    val extraBold = Font(Res.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold)
    return remember(medium, semiBold, bold, extraBold) { FontFamily(medium, semiBold, bold, extraBold) }
}

/** Uppercase eyebrow labels: 11 / 800, tracking 1.2. */
internal val EyebrowStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)

/** Type scale from the redesign: display 28/800, title 18/800, body 15/600-700, meta 12.5/500, label 11/800. */
@Composable internal fun appTypography(): Typography {
    val family = appFontFamily()
    return remember(family) {
        fun style(
            size: Float,
            weight: FontWeight,
            tracking: Float = 0f,
            line: Float = size * 1.3f,
        ) = TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = weight, letterSpacing = tracking.sp, lineHeight = line.sp)
        Typography(
            displayLarge = style(40f, FontWeight.ExtraBold, -1f),
            displayMedium = style(34f, FontWeight.ExtraBold, -0.8f),
            displaySmall = style(28f, FontWeight.ExtraBold, -0.6f),
            headlineLarge = style(28f, FontWeight.ExtraBold, -0.6f),
            headlineMedium = style(26f, FontWeight.ExtraBold, -0.4f),
            headlineSmall = style(22f, FontWeight.ExtraBold, -0.3f),
            titleLarge = style(19f, FontWeight.ExtraBold, -0.2f),
            titleMedium = style(18f, FontWeight.ExtraBold, -0.2f),
            titleSmall = style(15f, FontWeight.Bold),
            bodyLarge = style(15f, FontWeight.SemiBold, line = 21f),
            bodyMedium = style(14f, FontWeight.Medium, line = 20f),
            bodySmall = style(12.5f, FontWeight.Medium, line = 18f),
            labelLarge = style(14f, FontWeight.Bold),
            labelMedium = style(13f, FontWeight.ExtraBold, 0.4f),
            labelSmall = style(11.5f, FontWeight.Bold, 0.2f),
        )
    }
}
