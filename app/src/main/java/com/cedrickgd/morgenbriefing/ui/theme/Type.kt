package com.cedrickgd.morgenbriefing.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Serif = FontFamily.Serif
private val Sans = FontFamily.Default

/** The UI is German only, whatever language the device runs in. */
internal val GermanText = LocaleList("de-DE")

/**
 * Breaks long German compounds ("Kriegsdienstverweigerer") at their syllables with a hyphen,
 * using German patterns, instead of splitting them at any letter on narrow lines or at large
 * font sizes. For running text: the reading styles ([Reading]), the pull quote and the teasers;
 * short UI titles stay unhyphenated.
 */
internal fun TextStyle.german(): TextStyle = copy(
    hyphens = Hyphens.Auto,
    lineBreak = LineBreak.Paragraph,
    localeList = GermanText,
)

/**
 * Editorial typography: serif SemiBold for display/headlines (the "newspaper" voice of the
 * brief), system sans for everything else, generous body line height (~1.45) for calm reading
 * and slightly tracked labels for the small uppercase overlines.
 */
internal val MorgenbriefingTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        displayMedium = base.displayMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.2).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.1).sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 28.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = Sans,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            letterSpacing = 0.15.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = Sans,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.1.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = Sans,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            letterSpacing = 0.15.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.2.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.6.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = Sans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.5.sp,
        ),
    )
}

/** Soft, generous corners: cards 24 dp, hero/sheets 28 dp. */
internal val MorgenbriefingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Letter spacing for the small uppercase overlines ("VOR DER TÜR · …", "ANZIEHEN"). */
internal val OverlineLetterSpacing = 1.4.sp
