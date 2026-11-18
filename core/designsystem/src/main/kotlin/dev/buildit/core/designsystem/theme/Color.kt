package dev.buildit.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Build It palette. "Blueprint" blue is the primary action color, "Kiln" orange marks build and
 * run actions, graphite neutrals keep editors calm. Tuned for WCAG AA text contrast on surfaces.
 */
internal object Palette {
    val Blueprint10 = Color(0xFF001849)
    val Blueprint20 = Color(0xFF0B2C75)
    val Blueprint30 = Color(0xFF1A4199)
    val Blueprint40 = Color(0xFF2D57C0)
    val Blueprint80 = Color(0xFFB4C5FF)
    val Blueprint90 = Color(0xFFDBE1FF)

    val Kiln10 = Color(0xFF370E00)
    val Kiln20 = Color(0xFF5A1C00)
    val Kiln30 = Color(0xFF7F2C00)
    val Kiln40 = Color(0xFFA63D0B)
    val Kiln80 = Color(0xFFFFB597)
    val Kiln90 = Color(0xFFFFDBCD)

    val Mint20 = Color(0xFF003828)
    val Mint30 = Color(0xFF00513B)
    val Mint40 = Color(0xFF136B50)
    val Mint80 = Color(0xFF85D7B4)
    val Mint90 = Color(0xFFA1F4CF)

    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    val Graphite4 = Color(0xFF0E1014)
    val Graphite6 = Color(0xFF13151A)
    val Graphite10 = Color(0xFF1A1C21)
    val Graphite12 = Color(0xFF1E2025)
    val Graphite17 = Color(0xFF282A30)
    val Graphite22 = Color(0xFF33353B)
    val Graphite30 = Color(0xFF45474E)
    val Graphite50 = Color(0xFF75777F)
    val Graphite60 = Color(0xFF8F9099)
    val Graphite80 = Color(0xFFC5C6D0)
    val Graphite90 = Color(0xFFE2E2E9)
    val Graphite94 = Color(0xFFEEEDF4)
    val Graphite96 = Color(0xFFF4F3FA)
    val Graphite98 = Color(0xFFFAF9FF)
    val White = Color(0xFFFFFFFF)
}

/** Semantic colors that Material's scheme does not cover (success, build state, code tokens). */
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val build: Color,
    val onBuild: Color,
    val codeKeyword: Color,
    val codeString: Color,
    val codeNumber: Color,
    val codeComment: Color,
    val codeType: Color,
    val codeTag: Color,
    val codeAttribute: Color,
)

internal val LightExtended = ExtendedColors(
    success = Palette.Mint40, onSuccess = Palette.White,
    successContainer = Palette.Mint90, onSuccessContainer = Palette.Mint20,
    warning = Color(0xFF8A5A00),
    build = Palette.Kiln40, onBuild = Palette.White,
    codeKeyword = Color(0xFF7A2DB8), codeString = Color(0xFF136B50), codeNumber = Color(0xFF1A4199),
    codeComment = Color(0xFF6E7079), codeType = Color(0xFFA63D0B), codeTag = Color(0xFF1A4199),
    codeAttribute = Color(0xFF7A2DB8),
)

internal val DarkExtended = ExtendedColors(
    success = Palette.Mint80, onSuccess = Palette.Mint20,
    successContainer = Palette.Mint30, onSuccessContainer = Palette.Mint90,
    warning = Color(0xFFF5C26B),
    build = Palette.Kiln80, onBuild = Palette.Kiln20,
    codeKeyword = Color(0xFFD5A6FF), codeString = Color(0xFF85D7B4), codeNumber = Color(0xFFB4C5FF),
    codeComment = Color(0xFF8F9099), codeType = Color(0xFFFFB597), codeTag = Color(0xFFB4C5FF),
    codeAttribute = Color(0xFFD5A6FF),
)
