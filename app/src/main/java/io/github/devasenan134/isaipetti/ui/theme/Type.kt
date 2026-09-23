package io.github.devasenan134.isaipetti.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import io.github.devasenan134.isaipetti.R

// Both are variable fonts bundled in res/font (SIL Open Font License), so they work offline.
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Display face: headings and the wordmark. */
val Bricolage = FontFamily(
    variable(R.font.bricolage_grotesque, FontWeight.Medium),
    variable(R.font.bricolage_grotesque, FontWeight.SemiBold),
    variable(R.font.bricolage_grotesque, FontWeight.Bold),
)

/** Body face; also covers Tamil. */
val Catamaran = FontFamily(
    variable(R.font.catamaran, FontWeight.Normal),
    variable(R.font.catamaran, FontWeight.Medium),
    variable(R.font.catamaran, FontWeight.SemiBold),
    variable(R.font.catamaran, FontWeight.Bold),
    variable(R.font.catamaran, FontWeight.ExtraBold),
)

private val base = Typography()

private fun TextStyle.display(weight: FontWeight = FontWeight.Bold) =
    copy(fontFamily = Bricolage, fontWeight = weight, letterSpacing = (-0.02).em)

private fun TextStyle.body(weight: FontWeight) = copy(fontFamily = Catamaran, fontWeight = weight)

val IsaipettiTypography = Typography(
    displayLarge = base.displayLarge.display(),
    displayMedium = base.displayMedium.display(),
    displaySmall = base.displaySmall.display(),
    headlineLarge = base.headlineLarge.display(),
    headlineMedium = base.headlineMedium.display(),
    headlineSmall = base.headlineSmall.display(),
    titleLarge = base.titleLarge.display(FontWeight.SemiBold),
    titleMedium = base.titleMedium.body(FontWeight.Bold),
    titleSmall = base.titleSmall.body(FontWeight.Bold),
    bodyLarge = base.bodyLarge.body(FontWeight.Medium),
    bodyMedium = base.bodyMedium.body(FontWeight.Medium),
    bodySmall = base.bodySmall.body(FontWeight.Medium),
    labelLarge = base.labelLarge.body(FontWeight.Bold),
    labelMedium = base.labelMedium.body(FontWeight.Bold),
    labelSmall = base.labelSmall.body(FontWeight.Bold),
)
