package edu.cse499a.rocobangla.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Calm clinical palette: navy text, white / cool-grey surfaces, teal primary.
internal val Navy900 = Color(0xFF0F1F3D)
internal val Navy700 = Color(0xFF34466B)
internal val Grey50 = Color(0xFFF5F7FA)
internal val Grey100 = Color(0xFFEDF1F6)
internal val Grey300 = Color(0xFFD5DCE6)
internal val Grey500 = Color(0xFF6B7A90)
internal val Teal700 = Color(0xFF0F766E)
internal val Teal100 = Color(0xFFCCEFEA)
internal val Teal300 = Color(0xFF5EC4B6)
internal val Red700 = Color(0xFFB3261E)
internal val Red50 = Color(0xFFFCE8E6)

internal val DarkBg = Color(0xFF0B1220)
internal val DarkSurface = Color(0xFF121B2C)
internal val DarkSurfaceHigh = Color(0xFF1A2538)
internal val DarkOutline = Color(0xFF2E3B52)
internal val DarkText = Color(0xFFE6ECF5)
internal val DarkTextMuted = Color(0xFFA7B3C6)

/** Status colours that Material's scheme has no slot for. */
@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
)

internal val LightStatusColors = StatusColors(
    success = Color(0xFF1B7F5A),
    successContainer = Color(0xFFDDF4E9),
    onSuccessContainer = Color(0xFF0D4A33),
    warning = Color(0xFFB26B00),
    warningContainer = Color(0xFFFFF1D6),
    onWarningContainer = Color(0xFF5C3A00),
    neutralContainer = Grey100,
    onNeutralContainer = Navy700,
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF6FD3A6),
    successContainer = Color(0xFF123A2B),
    onSuccessContainer = Color(0xFFBFF0D8),
    warning = Color(0xFFFFC266),
    warningContainer = Color(0xFF4A3300),
    onWarningContainer = Color(0xFFFFE2B0),
    neutralContainer = DarkSurfaceHigh,
    onNeutralContainer = DarkTextMuted,
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
