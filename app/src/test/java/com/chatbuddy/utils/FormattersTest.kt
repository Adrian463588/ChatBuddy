package com.chatbuddy.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun smallArtifactsDoNotRenderAsZeroMegabytes() {
        assertEquals("231.5 KB", formatBytes(231_508))
    }

    @Test
    fun largeArtifactsUseReadableUnits() {
        assertEquals("2.8 GB", formatBytes(2_841_481_184))
    }
}
