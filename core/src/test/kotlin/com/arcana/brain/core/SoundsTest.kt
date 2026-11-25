package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sounds: chosen by id, played as bell, system sound or file, and only when the settings allow (fake player). */
class SoundsTest {
    @Test
    fun soundsAreChosenByIdAndPlayedAsBellSystemSoundOrFile() {
        assertEquals("Off", SoundChoices.label("off")); assertEquals("System · Hero", SoundChoices.label("sys:Hero")); assertEquals("Terminal bell × 3", SoundChoices.label("bell3"))
        assertTrue(SoundChoices.isValid("sys:Glass") && SoundChoices.isValid("file") && !SoundChoices.isValid("sys:Nope") && !SoundChoices.isValid("loud"))
        assertEquals("sys:Hero", SoundChoices.suggested(ChatSoundEvent.Done))
        fun player(macOs: Boolean, files: Set<String> = emptySet(), log: MutableList<String>) = CommandSoundPlayer(
            macOs = macOs, ring = { log += "bell" }, run = { log += it.joinToString(" ") }, exists = { it in files }, pause = {}, background = { it() }, home = "/home/me",
        )
        val log = ArrayList<String>()
        player(true, setOf("/System/Library/Sounds/Hero.aiff"), log).play("sys:Hero", 50, "", ChatSoundEvent.Done)
        assertEquals(listOf("afplay -v 0.50 /System/Library/Sounds/Hero.aiff"), log)
        log.clear(); player(true, setOf("/home/me/a.wav"), log).play("file", 100, "~/a.wav", null)
        assertEquals(listOf("afplay -v 1.00 /home/me/a.wav"), log)
        log.clear(); player(true, emptySet(), log).play("file", 100, "/missing.wav", ChatSoundEvent.Failed)
        assertEquals(listOf("bell", "bell", "bell"), log, "a missing file falls back to the bell, one beep more for each kind of event")
        log.clear(); player(false, emptySet(), log).play("sys:Hero", 80, "", ChatSoundEvent.Attention)
        assertEquals(listOf("bell"), log, "not macOS: the bell stands in")
        log.clear(); player(true, emptySet(), log).play("bell2", 80, "", null)
        assertEquals(listOf("bell", "bell"), log)
        log.clear(); player(true, emptySet(), log).play("off", 80, "", ChatSoundEvent.Done); player(true, emptySet(), log).play("garbage", 80, "", ChatSoundEvent.Done)
        assertTrue(log.isEmpty(), "off and unknown ids are silent")
    }

    @Test
    fun soundsPlayOnlyWhenTheSettingsAllowAndTheChoiceIsNotOff() {
        val heard = ArrayList<String>()
        val before = Sounds.player
        Sounds.player = SoundPlayer { choice, volume, file, event -> heard += "$choice/$volume/$file/$event" }
        try {
            val config = AppConfig(soundAttention = "sys:Ping", soundDone = "off", soundFailed = "bell2", soundVolume = 60, soundFile = "x.wav")
            Sounds.notify(config, ChatSoundEvent.Attention, onChatScreen = true)
            assertTrue(heard.isEmpty(), "away: nothing while Chat is on screen")
            Sounds.notify(config, ChatSoundEvent.Attention, onChatScreen = false)
            Sounds.notify(config.copy(soundWhere = "always"), ChatSoundEvent.Failed, onChatScreen = true)
            Sounds.notify(config, ChatSoundEvent.Done, onChatScreen = false)
            assertEquals(listOf("sys:Ping/60/x.wav/Attention", "bell2/60/x.wav/Failed", "off/60/x.wav/Done"), heard, "off is passed on and the player stays silent")
            assertEquals("off", AppConfig().soundAttention, "all sounds are off until chosen")
        } finally {
            Sounds.player = before
        }
    }
}
