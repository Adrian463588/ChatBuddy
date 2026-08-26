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
}
