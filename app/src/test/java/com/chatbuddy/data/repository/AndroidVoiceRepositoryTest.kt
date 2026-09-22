package com.chatbuddy.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVoiceRepositoryTest {
    @Test
    fun negativeAudioReadIsTerminal() {
        assertEquals(AudioReadState.TERMINAL, classifyAudioRead(-6))
    }

    @Test
    fun zeroAudioReadIsEmptyButNotTerminal() {
        assertEquals(AudioReadState.EMPTY, classifyAudioRead(0))
    }

    @Test
    fun rmsOfSilenceIsZero() {
        assertEquals(0f, calculateAudioRms(ShortArray(320)), 0f)
    }

    @Test
    fun rmsDetectsPcmSignal() {
        val rms = calculateAudioRms(ShortArray(320) { Short.MAX_VALUE })

        assertTrue(rms > 0.99f)
        assertTrue(rms <= 1f)
    }

    @Test
    fun normalizeAudioVolumeBoostsSoftAudio() {
        val soft = shortArrayOf(1000, 2000, -3000, 6000)
        val boosted = normalizeAudioVolume(soft)
        // Max peak was 6000, target is 24000 (gain 4.0x) -> 6000 * 4 = 24000
        assertEquals(24000.toShort(), boosted[3])
    }

    @Test
    fun normalizeAudioVolumePreservesLoudAudio() {
        val loud = shortArrayOf(10000, -20000, 25000)
        val preserved = normalizeAudioVolume(loud)
        assertEquals(loud[2], preserved[2])
    }

    @Test
    fun indonesianCandidateLocalesIncludeIdAndInWithCountry() {
        val candidatesFromId = resolveTtsCandidateLocales("id")
        assertTrue(candidatesFromId.isNotEmpty())
        assertEquals(java.util.Locale("id", "ID"), candidatesFromId[0])
        assertTrue(candidatesFromId.contains(java.util.Locale("in", "ID")))

        val candidatesFromIn = resolveTtsCandidateLocales("in")
        assertTrue(candidatesFromIn.isNotEmpty())
        assertEquals(java.util.Locale("id", "ID"), candidatesFromIn[0])
        assertTrue(candidatesFromIn.contains(java.util.Locale("in", "ID")))
    }

    @Test
    fun languageMatcherTreatsIdAndInEquivalently() {
        assertTrue(isLanguageMatch(java.util.Locale("in", "ID"), java.util.Locale("id", "ID")))
        assertTrue(isLanguageMatch(java.util.Locale("id", "ID"), java.util.Locale("in", "ID")))
        assertTrue(isLanguageMatch(java.util.Locale("in"), java.util.Locale("id", "ID")))
        org.junit.Assert.assertFalse(isLanguageMatch(java.util.Locale.US, java.util.Locale("id", "ID")))
    }

    @Test
    fun englishCandidateLocalesIncludeUsAndUk() {
        val candidates = resolveTtsCandidateLocales("en")
        assertTrue(candidates.contains(java.util.Locale.US))
    }
}
