package com.chatbuddy.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.chatbuddy.ai.voice.WhisperEngine
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.VoiceCapabilities
import com.chatbuddy.domain.model.VoiceTranscript
import com.chatbuddy.domain.repository.VoiceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisper: WhisperEngine
) : VoiceRepository {
    override suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities> = withContext(Dispatchers.Main) {
        val ttsReady = checkOfflineTts(languageTag)
        AppResult.Success(
            VoiceCapabilities(
                whisperReady = whisper.isReady,
                offlineTtsReady = ttsReady,
                message = when {
                    !whisper.isReady -> "Whisper model/runtime is not installed"
                    !ttsReady -> "Offline Android voice is not available for this language"
                    else -> "Voice turn-taking is ready"
                }
            )
        )
    }

    override fun transcribe(): Flow<VoiceTranscript> = flow {
        if (!whisper.isReady) {
            emit(VoiceTranscript.Failed("Whisper JNI runtime is unavailable"))
            return@flow
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            emit(VoiceTranscript.Failed("Microphone permission is required"))
            return@flow
        }
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        var started = false
        try {
            recorder.startRecording()
            started = true
            val samples = ShortArray(bufferSize / Short.SIZE_BYTES)
            while (true) {
                val read = recorder.read(samples, 0, samples.size)
                if (read <= 0) continue
                whisper.transcribe(samples.copyOf(read)).collect { emit(it) }
            }
        } finally {
            if (started) recorder.stop()
            recorder.release()
        }
    }

    override suspend fun speak(text: String, languageTag: String): AppResult<Unit> = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext AppResult.Success(Unit)
        suspendCancellableCoroutine { continuation ->
            var tts: TextToSpeech? = null
            val utteranceId = UUID.randomUUID().toString()
            val requestedUtteranceId = utteranceId
            tts = TextToSpeech(context) { status ->
                val engine = tts ?: return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) {
                    if (continuation.isActive) continuation.resume(AppResult.Error("Offline TTS initialization failed"))
                    engine.shutdown()
                    return@TextToSpeech
                }
                val locale = Locale.forLanguageTag(languageTag)
                if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE ||
                    engine.voices.none { voice ->
                        voice.locale == locale && !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                    }
                ) {
                    if (continuation.isActive) continuation.resume(AppResult.Error("Offline TTS voice is unavailable"))
                    engine.shutdown()
                    return@TextToSpeech
                }
                engine.setLanguage(locale)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == requestedUtteranceId && continuation.isActive) {
                            continuation.resume(AppResult.Success(Unit))
                            engine.shutdown()
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(AppResult.Error("Offline TTS synthesis failed"))
                        engine.shutdown()
                    }
                })
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
            continuation.invokeOnCancellation { tts?.shutdown() }
        }
    }

    private suspend fun checkOfflineTts(languageTag: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val engine = tts
                val available = if (status == TextToSpeech.SUCCESS && engine != null) {
                    val locale = Locale.forLanguageTag(languageTag)
                    engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE &&
                        engine.voices.any { voice ->
                            voice.locale == locale && !voice.features.orEmpty()
                                .contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
                        }
                } else false
                if (continuation.isActive) continuation.resume(available)
                engine?.shutdown()
            }
            continuation.invokeOnCancellation { tts?.shutdown() }
        }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}
