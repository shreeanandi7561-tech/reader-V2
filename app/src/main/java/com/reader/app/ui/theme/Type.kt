package com.reader.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System sans for Swiss feel (Roboto on Android maps cleanly to a neo-grotesque look).
private val Sans = FontFamily.SansSerif

val ReaderTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light,  fontSize = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleLarge   = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    titleMedium  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.4.sp),
    bodyLarge    = TextStyle(
        fontFamily = Sans, 
        fontWeight = FontWeight.Normal, 
        fontSize = 17.sp, 
        lineHeight = 28.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true)
    ),
    bodyMedium   = TextStyle(
        fontFamily = Sans, 
        fontWeight = FontWeight.Normal, 
        fontSize = 14.sp, 
        lineHeight = 22.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = true)
    ),
    labelLarge   = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 1.2.sp),
)
