package edu.cse499a.rocobangla.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import edu.cse499a.rocobangla.R

/** Bundled Noto Sans Bengali (SIL OFL 1.1) so Bangla renders the same on every device. */
val NotoSansBengali = FontFamily(
    Font(R.font.noto_sans_bengali_regular, FontWeight.Normal),
    Font(R.font.noto_sans_bengali_semibold, FontWeight.SemiBold),
)

val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** The model's Bangla explanation: large, line height 1.5. */
val BanglaResultStyle = TextStyle(fontFamily = NotoSansBengali, fontSize = 20.sp, lineHeight = 30.sp)

/** Smaller Bangla text such as the disclaimer. */
val BanglaBodyStyle = TextStyle(fontFamily = NotoSansBengali, fontSize = 15.sp, lineHeight = 23.sp)
