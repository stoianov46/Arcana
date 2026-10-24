package com.arcana.brain.app.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.ui.Color

private fun hex(rgb: Int) = Color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

private fun channel(rgb: Int, shift: Int) = (rgb shr shift) and 0xFF

/** [fg] laid over [bg] at [alpha] (0..1): what a see-through colour looks like on a terminal that cannot do transparency. */
private fun mix(fg: Int, bg: Int, alpha: Double): Int =
    listOf(16, 8, 0).fold(0) { acc, shift ->
        (acc shl 8) or (channel(bg, shift) + (alpha * (channel(fg, shift) - channel(bg, shift)))).toInt()
    }

/** Hue [h] (0..1), saturation and value (0..1) as 0xRRGGBB. */
private fun hsv(h: Double, s: Double, v: Double): Int {
    val sector = (h * 6).toInt() % 6
    val f = h * 6 - (h * 6).toInt()
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    val (r, g, b) = when (sector) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
}

private fun luminance(rgb: Int) = (0.299 * channel(rgb, 16) + 0.587 * channel(rgb, 8) + 0.114 * channel(rgb, 0)) / 255

/** The colour roles a theme must set (as 0xRRGGBB); everything else the UI draws is mixed from them. */
private val RequiredRoles = listOf(
    "background", "surface", "surfaceHigh", "text", "muted", "dim",
    "green", "red", "blue", "orange", "purple", "cyan",
)

class ThemeSpec(val id: String, val name: String, val colors: Map<String, Int>) {
    init {
        val missing = RequiredRoles.filter { it !in colors }
        require(missing.isEmpty()) { "Theme '$id' lacks colours: ${missing.joinToString()}" }
    }
}

/** The colours a theme is recognised by, for a preview strip: backgrounds, text and the accent colours. */
fun ThemeSpec.swatchColors(): List<Color> =
    listOf("background", "surface", "surfaceHigh", "text", "muted", "green", "red", "blue", "orange", "purple", "cyan").map { hex(colors.getValue(it)) }

object Themes {
    val Dark = ThemeSpec(
        "dark", "Dark",
        mapOf(
            "background" to 0x0A0A0A, "surface" to 0x161616, "surfaceHigh" to 0x262626,
            "text" to 0xEDEDED, "muted" to 0x8C8C8C, "dim" to 0x4A4A4A,
            "green" to 0x3DBE7C, "red" to 0xEF5350, "blue" to 0x4F8BF7, "orange" to 0xF59E0B,
            "purple" to 0x8B6CF6, "cyan" to 0x5BC8E6,
        ),
    )

    val Light = ThemeSpec(
        "light", "Light",
        mapOf(
            "background" to 0xE9E9E6, "surface" to 0xF6F6F4, "surfaceHigh" to 0xE1E1DD,
            "text" to 0x1F2328, "muted" to 0x5F6770, "dim" to 0x98A0A8,
            "green" to 0x1A7F37, "red" to 0xCF222E, "blue" to 0x0969DA, "orange" to 0xB35900,
            "purple" to 0x6F42C1, "cyan" to 0x0A7EA4,
        ),
    )


    /** A calmer dark: dark slate instead of near-black, off-white text and pastel accents, so nothing glares. */
    val SoftDark = ThemeSpec(
        "soft-dark", "Soft Dark",
        mapOf(
            "background" to 0x2B2D33, "surface" to 0x34373E, "surfaceHigh" to 0x3F434B,
            "text" to 0xD6D9DE, "muted" to 0xA3A9B2, "dim" to 0x7A808A,
            "green" to 0x8FD0A5, "red" to 0xEBA0A0, "blue" to 0x8DB4EE, "orange" to 0xE8C082, "purple" to 0xBBA6EA, "gray" to 0x8D939D, "cyan" to 0x8CCFD8,
        ),
    )

    /** A calmer light: warm paper instead of near-white, charcoal text and muted accents. */
    val SoftLight = ThemeSpec(
        "soft-light", "Soft Light",
        mapOf(
            "background" to 0xF1ECE4, "surface" to 0xF9F6F0, "surfaceHigh" to 0xE6E0D6,
            "text" to 0x383C43, "muted" to 0x5F646C, "dim" to 0x8C929A,
            "green" to 0x3F7F5A, "red" to 0xA94F54, "blue" to 0x3F6FAE, "orange" to 0x8E6224, "purple" to 0x735AA6, "gray" to 0x6B7078, "cyan" to 0x31808C,
        ),
    )

    val Darcula = ThemeSpec(
        "darcula", "Darcula",
        mapOf(
            "background" to 0x232425, "surface" to 0x2B2B2B, "surfaceHigh" to 0x3C3F41, "entry" to 0x323436, "selected" to 0x45494A,
            "text" to 0xA9B7C6, "muted" to 0x808080, "dim" to 0x8B919B,
            "green" to 0x6A8759, "red" to 0xFF6B68, "blue" to 0x589DF6, "orange" to 0xCC7832, "purple" to 0x9876AA, "gray" to 0x6B6F73, "cyan" to 0x4FB8B8,
            "codeFg" to 0xFFC66D, "valueFg" to 0xA5C261,
            // The prompt line of a job: warm cream text on a semi-transparent yellow (Darcula's own yellow over the row).
            "promptBand" to 0x50493E, "promptBandActive" to 0x686050, "promptText" to 0xFFF1CF,
        ),
    )

    /**
     * Solarized's teal, cream and violet, with greens that suit the teal (the original olive looks dirty on it) and
     * more contrast than the original palette. The prompt line of a job sits on a semi-transparent amber strip (yellow
     * over a warm dark base, so it stays amber and does not turn olive on the teal): the status is in the badge, and a
     * green line on a green badge on a teal row is too many greens.
     */
    val SolarizedDark = ThemeSpec(
        "solarized-dark", "Solarized Dark",
        mapOf(
            "background" to 0x00212B, "surface" to 0x002B36, "surfaceHigh" to 0x0F4856, "entry" to 0x073642, "selected" to 0x17596B,
            "text" to 0xEEE8D5, "muted" to 0xAEBABA, "dim" to 0x8CA1A8,
            "green" to 0x4FC48C, "red" to 0xF25C58, "blue" to 0x4DAAF0, "orange" to 0xDDA82A, "purple" to 0x8F94EA, "gray" to 0x8AA0A6, "cyan" to 0x3CC2B8,
            "codeFg" to 0xF0763A,
            "promptBand" to 0x493611, "promptBandActive" to 0x594518, "promptText" to 0xFFF0C4,
        ),
    )

    val SolarizedLight = ThemeSpec(
        "solarized-light", "Solarized Light",
        mapOf(
            "background" to 0xFDF6E3, "surface" to 0xEEE8D5, "surfaceHigh" to 0xDDD6BC, "entry" to 0xFFFBEF, "selected" to 0xD3CAAA,
            "text" to 0x073642, "muted" to 0x40585F, "dim" to 0x667B80,
            "green" to 0x1D7F52, "red" to 0xC0272D, "blue" to 0x1B72B8, "orange" to 0x8F6600, "purple" to 0x5A5FB5, "gray" to 0x5F7277, "cyan" to 0x157A73,
            "codeFg" to 0xB23E0D,
            "promptBand" to 0xF0E4BF, "promptBandActive" to 0xCDBD88, "promptText" to 0x3B2E00,
        ),
    )

    val Nord = ThemeSpec(
        "nord", "Nord",
        mapOf(
            "background" to 0x242933, "surface" to 0x2E3440, "surfaceHigh" to 0x3B4252, "entry" to 0x343B49, "selected" to 0x434C5E,
            "text" to 0xD8DEE9, "muted" to 0x929CB0, "dim" to 0x5B667D,
            "green" to 0xA3BE8C, "red" to 0xBF616A, "blue" to 0x81A1C1, "orange" to 0xD08770, "purple" to 0xB48EAD, "gray" to 0x6B7690, "cyan" to 0x88C0D0,
            "codeFg" to 0xEBCB8B,
        ),
    )

    val all = listOf(Dark, Light, SoftDark, SoftLight, Darcula, SolarizedDark, SolarizedLight, Nord)

    fun byId(id: String): ThemeSpec = all.firstOrNull { it.id == id } ?: Dark
}

/** Every colour of one theme, resolved to terminal colours: what is set in the [ThemeSpec], and the rest derived from it. */
class Colors(val spec: ThemeSpec) {
    private val c = spec.colors
    private val backgroundRgb = c.getValue("background")
    private val surfaceRgb = c.getValue("surface")
    private val surfaceHighRgb = c.getValue("surfaceHigh")
    private val textRgb = c.getValue("text")
    private val mutedRgb = c.getValue("muted")
    private val greenRgb = c.getValue("green")
    private val redRgb = c.getValue("red")
    private val blueRgb = c.getValue("blue")
    private val orangeRgb = c.getValue("orange")
    private val purpleRgb = c.getValue("purple")
    private val cyanRgb = c.getValue("cyan")

    val light = luminance(backgroundRgb) > 0.5

    /** A colour the theme may set itself; otherwise it is derived from the required ones. */
    private fun opt(key: String, default: () -> Int) = c[key] ?: default()

    private val entryRgb = opt("entry") { mix(surfaceHighRgb, surfaceRgb, 0.5) }
    private val selectedRgb = opt("selected") { mix(textRgb, surfaceHighRgb, 0.06) }
    private val codeBgRgb = opt("codeBg") { mix(blueRgb, surfaceRgb, 0.12) }
    private val codeFgRgb = opt("codeFg") { mix(orangeRgb, textRgb, 0.35) }

    val Background = hex(backgroundRgb)
    val Surface = hex(surfaceRgb)
    val SurfaceHigh = hex(surfaceHighRgb)

    /** A list entry's own block, a step away from the card it sits on. */
    val EntryBg = hex(entryRgb)
    val Selected = hex(selectedRgb)

    /** The background of the input that has the keyboard. */
    val Focus = hex(opt("focus") { selectedRgb })
    val Text = hex(textRgb)
    val Muted = hex(mutedRgb)
    val Dim = hex(c.getValue("dim"))
    val Green = hex(greenRgb)
    val GreenBg = hex(opt("greenBg") { mix(greenRgb, surfaceRgb, 0.15) })
    val Red = hex(redRgb)
    val RedBg = hex(opt("redBg") { mix(redRgb, surfaceRgb, 0.17) })
    val Blue = hex(blueRgb)
    val Orange = hex(orangeRgb)
    val Purple = hex(purpleRgb)

    /** The brand colour: key badges, the selection bar, the logo. */
    val Accent = hex(purpleRgb)
    val AccentBg = hex(mix(purpleRgb, surfaceRgb, 0.16))

    // ---- Message roles: one colour per kind of message, so text of different meaning never looks the same ----
    /** Call to action: the next thing to press. Cyan, bold, marked with "→". */
    val Cta = hex(cyanRgb)

    /** The title of an input field: a light blue of the theme's own blue. */
    val FieldLabel = hex(mix(blueRgb, textRgb, 0.35))

    /** Hint: a tip or an explanation. A soft steel blue, marked with "›". */
    val Hint = hex(opt("hint") { mix(blueRgb, mutedRgb, 0.5) })

    /** Warning: something is blocked, limited or needs care. Marked with "⚠". */
    val Warn = hex(orangeRgb)

    // ---- The console: model output (Markdown), styled apart from the other panels ----
    val ConsoleText = hex(opt("consoleText") { textRgb })
    val ConsoleMuted = hex(opt("consoleMuted") { mutedRgb })
    val ConsoleDim = hex(opt("consoleDim") { c.getValue("dim") })

    /** Headings, links and the speaker of a message. */
    val ConsoleUser = hex(opt("consoleUser") { blueRgb })

    /** List markers. */
    val ConsoleTool = hex(opt("consoleTool") { orangeRgb })
    val ConsoleOk = hex(opt("consoleOk") { greenRgb })
    val ConsoleErr = hex(opt("consoleErr") { redRgb })

    /** Inline code: a warm foreground on a tinted chip; fenced code blocks sit on the same tint. */
    val ConsoleCodeFg = hex(opt("consoleCodeFg") { codeFgRgb })
    val ConsoleCodeBg = hex(opt("consoleCodeBg") { codeBgRgb })

    /**
     * Your own messages in a conversation: an amber name on a semi-transparent amber band with warm cream text, so they
     * stand out from the model's replies. A theme can set the band itself (`promptBand`, `promptText`), as Darcula does.
     */
    val UserName = hex(orangeRgb)
    val UserBg = hex(opt("promptBand") { mix(orangeRgb, surfaceRgb, if (light) 0.22 else 0.15) })
    val UserText = hex(opt("promptText") { mix(orangeRgb, textRgb, 0.22) })

    /** One colour of the animated logo's rainbow, [t] (0..1, wrapping) round the colour wheel: soft pastels. */
    fun logoShade(t: Double): Color = hex(hsv((t % 1.0 + 1.0) % 1.0, if (light) 0.45 else 0.34, if (light) 0.70 else 0.86))

    /** The "you are typing" marker. */
    val Editing = hex(if (light) 0xB5001F else 0xE0143C)
}

/**
 * The colours the UI draws with. Every `Palette.X` reads the active theme, and the active theme is Compose state, so
 * choosing another theme redraws every screen that has read a colour.
 */
object Palette {
    private var colors by mutableStateOf(Colors(Themes.Dark))

    val themeId: String get() = colors.spec.id

    fun use(spec: ThemeSpec) {
        colors = Colors(spec)
    }

    val Background get() = colors.Background
    val Surface get() = colors.Surface
    val SurfaceHigh get() = colors.SurfaceHigh
    val EntryBg get() = colors.EntryBg
    val Selected get() = colors.Selected
    val Focus get() = colors.Focus
    val Text get() = colors.Text
    val Muted get() = colors.Muted
    val Dim get() = colors.Dim
    val Green get() = colors.Green
    val GreenBg get() = colors.GreenBg
    val Red get() = colors.Red
    val RedBg get() = colors.RedBg
    val Blue get() = colors.Blue
    val Orange get() = colors.Orange
    val Purple get() = colors.Purple
    val Accent get() = colors.Accent
    val AccentBg get() = colors.AccentBg
    val Cta get() = colors.Cta
    val FieldLabel get() = colors.FieldLabel
    val Hint get() = colors.Hint
    val Warn get() = colors.Warn
    val Editing get() = colors.Editing
    fun logoShade(t: Double) = colors.logoShade(t)
    val UserName get() = colors.UserName
    val UserBg get() = colors.UserBg
    val UserText get() = colors.UserText
    val light: Boolean get() = colors.light
    val ConsoleText get() = colors.ConsoleText
    val ConsoleMuted get() = colors.ConsoleMuted
    val ConsoleDim get() = colors.ConsoleDim
    val ConsoleUser get() = colors.ConsoleUser
    val ConsoleTool get() = colors.ConsoleTool
    val ConsoleOk get() = colors.ConsoleOk
    val ConsoleErr get() = colors.ConsoleErr
    val ConsoleCodeFg get() = colors.ConsoleCodeFg
    val ConsoleCodeBg get() = colors.ConsoleCodeBg
}

object Dimens {
    /** Below this there is no sensible layout; above it, everything shrinks / hides sections to fit. */
    const val MinColumns = 60
    const val MinRows = 12

    /** Terminal rows left unused at the bottom (the cursor row), so the inline frame never scrolls the screen. */
    const val ReservedRows = 1
    const val SidebarWidth = 24
    const val ContentPadding = 2
}
