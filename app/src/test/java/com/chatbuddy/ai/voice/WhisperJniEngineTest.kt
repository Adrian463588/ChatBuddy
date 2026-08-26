package com.chatbuddy.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WhisperJniEngineTest {
    @Test
    fun safDescriptorPathUsesProcFd() {
        assertEquals("/proc/self/fd/12", WhisperJniEngine.safFdPath(12))
    }

    @Test
    fun safDescriptorPathRejectsInvalidDescriptor() {
        try {
            WhisperJniEngine.safFdPath(-1)
            fail("Expected invalid descriptor to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: native loading must not receive an invalid descriptor path.
        }
    }
}
