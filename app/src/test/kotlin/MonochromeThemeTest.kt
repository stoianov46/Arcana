package com.arcana.brain.app

import com.arcana.brain.app.design.Themes
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Monochrome theme: one blue hue (red is the single exception), readable on every layer, layers that step up in brightness. */
class MonochromeThemeTest {
    private val c = Themes.Monochrome.colors

    private fun lum(rgb: Int): Double {
        fun f(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(rgb shr 16 and 255) + 0.7152 * f(rgb shr 8 and 255) + 0.0722 * f(rgb and 255)
    }
    private fun contrast(a: Int, b: Int) = (max(lum(a), lum(b)) + 0.05) / (min(lum(a), lum(b)) + 0.05)
    private fun hue(rgb: Int): Double {
        val r = (rgb shr 16 and 255) / 255.0; val g = (rgb shr 8 and 255) / 255.0; val b = (rgb and 255) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d == 0.0) return 0.0
        val h = when (mx) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return (h * 60 + 360) % 360
    }

    @Test
    fun isRegisteredAndFoundById() {
        assertTrue(Themes.Monochrome in Themes.all)
        assertEquals("Monochrome", Themes.byId("monochrome").name)
        assertEquals(0xA7B6D6, c.getValue("text"), "the base colour is the chosen light blue")
        assertEquals(0x000000, c.getValue("background"))
    }

    @Test
    fun everyColourIsTheBlueHueExceptTheRoseRed() {
        for ((role, rgb) in c) {
            if (role in setOf("background", "red", "todo")) continue
            assertTrue(hue(rgb) in 200.0..225.0, "$role is blue (hue ${hue(rgb).toInt()})")
        }
        assertTrue(hue(c.getValue("red")) in 320.0..350.0, "errors are a rose, so they still stand out")
    }

    @Test
    fun textAndAccentsAreReadableOnBlackAndOnTheLayers() {
        for (role in listOf("text", "green", "red", "blue", "orange", "purple", "cyan")) {
            for (layer in listOf("background", "surface", "entry")) assertTrue(contrast(c.getValue(role), c.getValue(layer)) >= 4.5, "$role on $layer")
            assertTrue(contrast(c.getValue(role), c.getValue("selected")) >= 4.5, "$role on the selected row")
        }
        assertTrue(contrast(c.getValue("muted"), c.getValue("background")) >= 4.5 && contrast(c.getValue("muted"), c.getValue("selected")) >= 4.0, "muted text")
        assertTrue(contrast(c.getValue("cyan"), c.getValue("background")) > contrast(c.getValue("text"), c.getValue("background")), "key hints are brighter than the body text")
    }

    @Test
    fun layersStepUpInBrightness() {
        val steps = listOf("background", "surface", "entry", "surfaceHigh").map { lum(c.getValue(it)) }
        assertEquals(steps.sorted(), steps, "background < surface < entry < surfaceHigh")
        assertTrue(lum(c.getValue("selected")) > lum(c.getValue("entry")), "the selected row is lighter than the rows around it")
    }

    @Test
    fun theTaskListIsARealGreenReadableOnEveryLayer() {
        assertTrue(hue(c.getValue("todo")) in 120.0..160.0, "todo is green")
        for (layer in listOf("background", "surface", "entry", "selected")) assertTrue(contrast(c.getValue("todo"), c.getValue(layer)) >= 4.5, "todo on $layer")
        assertEquals(c.getValue("todo"), com.arcana.brain.app.design.Themes.MonochromeAccent.colors.getValue("todo"))
        val d = com.arcana.brain.app.design.Colors(Themes.Dark)
        assertEquals(d.Green, d.Todo, "other themes keep the green role")
    }

    @Test
    fun theAccentVariantAddsOneAmberAndTheAccentsOnlyForThemesThatSetThem() {
        val a = Themes.MonochromeAccent
        assertTrue(a in Themes.all && Themes.byId("monochrome-accent") === a)
        val changed = a.colors.filter { (k, v) -> Themes.Monochrome.colors[k] != v }.keys
        assertEquals(setOf("orange", "consoleTool", "statusAccent", "thinkAccent", "contextAccent", "consoleBold", "consoleItalic", "consoleLink", "consoleNumber", "consoleHeadingMark", "consoleQuote", "syntaxString", "syntaxNumber"), changed, "only the amber roles and the console steps differ from Monochrome")
        val amber = changed - setOf("consoleItalic", "consoleLink", "consoleBold")
        for (k in amber) assertTrue(hue(a.colors.getValue(k)) in 30.0..50.0, "$k is amber")
        for (k in changed - "consoleQuote") assertTrue(contrast(a.colors.getValue(k), a.colors.getValue("background")) >= 7.0 && contrast(a.colors.getValue(k), a.colors.getValue("selected")) >= 4.5, "$k reads on black and on the selected row")
        assertEquals(null, com.arcana.brain.app.design.Colors(Themes.Monochrome).ThinkAccent, "the strict theme sets no accent: the usual scales stay")
        assertEquals(null, com.arcana.brain.app.design.Colors(Themes.Dark).thinkShade(0.5))
        val shades = (0..2).map { com.arcana.brain.app.design.Colors(a).thinkShade(it / 2.0)!! }
        assertEquals(3, shades.toSet().size, "low, middle and high effort are three different shades")
    }

    @Test
    fun theConsoleStepsFallBackToWhatTheConsoleUsedBefore() {
        val d = com.arcana.brain.app.design.Colors(Themes.Dark)
        assertEquals(d.ConsoleUser, d.ConsoleLink)
        assertEquals(d.Cta, d.ConsoleNumber)
        assertEquals(d.Accent, d.ConsoleHeadingMark)
        assertEquals(d.Accent, d.ConsoleQuote)
        assertEquals(d.Green, d.SyntaxString)
        assertEquals(d.Orange, d.SyntaxNumber)
        assertEquals(null, d.ConsoleBold)
        assertEquals(null, d.ConsoleItalic)
        val a = com.arcana.brain.app.design.Colors(Themes.MonochromeAccent)
        assertTrue(a.ConsoleBold != null && a.ConsoleBold != a.ConsoleText, "bold has its own colour in the accent theme")
    }
}
