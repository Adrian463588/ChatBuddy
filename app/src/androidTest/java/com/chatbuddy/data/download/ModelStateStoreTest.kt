package com.chatbuddy.data.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chatbuddy.data.model.ModelManifestDataSource
import com.chatbuddy.domain.model.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelStateStoreTest {
    @Test
    fun statePersistsAndActiveDownloadRecoversAsResumeState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PREFERENCES, 0)
        preferences.edit().clear().commit()
        val manifest = ModelManifestDataSource(context)
        val artifactId = "all-minilm-l6-v2-vocab"
        val artifact = manifest.read().first { it.id == artifactId }

        try {
            val first = ModelStateStore(manifest, context)
            first.beginDownload(artifactId, artifact.sizeBytes)
            first.update(artifactId, ModelStatus.Downloading(128L, artifact.sizeBytes))

            val recreated = ModelStateStore(manifest, context)
            val recovered = recreated.states.value.first { it.artifact.id == artifactId }.status

            assertEquals(ModelStatus.Paused(128L, artifact.sizeBytes), recovered)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun pauseFlagPreventsRacingWorkerProgressFromOverwritingPausedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PREFERENCES, 0)
        preferences.edit().clear().commit()
        val manifest = ModelManifestDataSource(context)
        val artifactId = "all-minilm-l6-v2-vocab"
        val artifact = manifest.read().first { it.id == artifactId }

        try {
            val store = ModelStateStore(manifest, context)
            store.beginDownload(artifactId, artifact.sizeBytes)
            store.update(artifactId, ModelStatus.Downloading(256L, artifact.sizeBytes))
            assertTrue(store.requestPause(artifactId))
            store.update(artifactId, ModelStatus.Downloading(512L, artifact.sizeBytes))

            assertEquals(
                ModelStatus.Paused(256L, artifact.sizeBytes),
                store.states.value.first { it.artifact.id == artifactId }.status
            )
        } finally {
            preferences.edit().clear().commit()
        }
    }

    companion object {
        private const val PREFERENCES = "chatbuddy_model_states"
    }
}
