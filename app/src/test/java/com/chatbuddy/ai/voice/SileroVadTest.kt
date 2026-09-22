package com.chatbuddy.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SileroVadTest {

    private lateinit var sileroVad: SileroVad
    private val modelFile = File("src/main/assets/silero_vad.onnx").let {
        if (it.exists()) it else File("app/src/main/assets/silero_vad.onnx")
    }

    @Before
    fun setUp() {
        sileroVad = SileroVad(context = null)
    }

    @Test
    fun sileroVadInitializesSafelyWithoutNativeException() {
        assertFalse("Initially isReady should be false", sileroVad.isReady)
    }

    @Test
    fun unreadySileroVadReturnsZeroSpeechProbabilitySafely() {
        val dummyFrame = FloatArray(SileroVad.FRAME_SIZE) { 0f }
        val prob = sileroVad.infer(dummyFrame)
        assertEquals(0f, prob, 0.0001f)
    }

    @Test
    fun constantsMatchSileroV5Specification() {
        assertEquals(512, SileroVad.FRAME_SIZE)
        assertEquals(64, SileroVad.CONTEXT_SIZE)
        assertEquals(16_000, SileroVad.SAMPLE_RATE)
        assertEquals("silero_vad.onnx", SileroVad.MODEL_ASSET)
    }

    @Test
    fun modelFileExistsInAssets() {
        assertTrue("silero_vad.onnx must exist in assets", modelFile.exists())
        assertTrue("silero_vad.onnx size should be > 2MB", modelFile.length() > 2_000_000)
    }
}
