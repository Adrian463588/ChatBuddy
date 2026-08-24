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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.cancelAndJoin
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisper: WhisperEngine
) : VoiceRepository {
    override suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities> {
        val whisperSetup = whisper.ensureLoaded()
        val whisperReady = whisperSetup is AppResult.Success
        val ttsReady = withContext(Dispatchers.Main) { checkOfflineTts(languageTag) }
        return AppResult.Success(
            VoiceCapabilities(
                whisperReady = whisperReady,
                offlineTtsReady = ttsReady,
                message = when {
                    !whisperReady -> (whisperSetup as? AppResult.Error)?.message
                        ?: "Whisper model/runtime is not installed"
                    !ttsReady -> "Offline Android voice is not available for this language"
                    else -> "Voice turn-taking is ready"
                }
            )
        )
    }

    override fun transcribe(languageTag: String): Flow<VoiceTranscript> = channelFlow {
        when (val setup = whisper.ensureLoaded()) {
            is AppResult.Error -> {
                send(VoiceTranscript.Failed(setup.message))
                return@channelFlow
            }
            is AppResult.Success -> Unit
            AppResult.Loading -> {
                send(VoiceTranscript.Failed("Whisper model is still preparing"))
                return@channelFlow
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            send(VoiceTranscript.Failed("Microphone permission is required for live translation"))
            return@channelFlow
        }
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE / 2)
        val frameChannel = Channel<ShortArray>(capacity = FRAME_CHANNEL_CAPACITY)
        val captureJob = launch(Dispatchers.IO) {
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrElse { error ->
                send(VoiceTranscript.Failed("Unable to open microphone: ${error.message}"))
                frameChannel.close()
                return@launch
            }
            var started = false
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    send(VoiceTranscript.Failed("Microphone is unavailable on this device"))
                    return@launch
                }
                recorder.startRecording()
                started = true
                val readBuffer = ShortArray((frameSize(bufferSize)).coerceAtLeast(AUDIO_FRAME_SAMPLES * 2))
                while (isActive) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    var offset = 0
                    while (offset < read && isActive) {
                        val frameEnd = (offset + AUDIO_FRAME_SAMPLES).coerceAtMost(read)
                        if (frameEnd - offset == AUDIO_FRAME_SAMPLES) {
                            frameChannel.send(readBuffer.copyOfRange(offset, frameEnd))
                        }
                        offset = frameEnd
                    }
                }
            } catch (error: Exception) {
                if (isActive) send(VoiceTranscript.Failed("Microphone capture stopped: ${error.message}"))
            } finally {
                if (started) runCatching { recorder.stop() }
                recorder.release()
                frameChannel.close()
            }
        }

        try {
            withContext(Dispatchers.Default) {
                val preRoll = java.util.ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
                val utterance = ArrayList<Short>(MAX_UTTERANCE_SAMPLES)
                var speechFrames = 0
                var silenceFrames = 0
                var inSpeech = false
                var lastPartialAt = 0L

                suspend fun transcribeSnapshot(partial: Boolean) {
                    if (utterance.size < MIN_UTTERANCE_SAMPLES) return
                    whisper.transcribe(utterance.toShortArray(), languageTag, partial).collect { send(it) }
                }

                suspend fun finishUtterance() {
                    transcribeSnapshot(partial = false)
                    utterance.clear()
                    preRoll.clear()
                    speechFrames = 0
                    silenceFrames = 0
                    inSpeech = false
                    lastPartialAt = 0L
                }

                for (frame in frameChannel) {
                    val speech = rms(frame) >= SPEECH_RMS_THRESHOLD
                    if (!inSpeech) {
                        preRoll.addLast(frame)
                        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        speechFrames = if (speech) speechFrames + 1 else 0
                        if (speechFrames >= MIN_SPEECH_FRAMES) {
                            inSpeech = true
                            preRoll.forEach { samples -> utterance.addAll(samples.toList()) }
                            preRoll.clear()
                            silenceFrames = 0
                            lastPartialAt = android.os.SystemClock.elapsedRealtime()
                        }
                        continue
                    }

                    utterance.addAll(frame.toList())
                    silenceFrames = if (speech) 0 else silenceFrames + 1
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (utterance.size >= PARTIAL_MIN_SAMPLES &&
                        now - lastPartialAt >= PARTIAL_INTERVAL_MS
                    ) {
                        transcribeSnapshot(partial = true)
                        lastPartialAt = now
                    }
                    if (silenceFrames >= TRAILING_SILENCE_FRAMES ||
                        utterance.size >= MAX_UTTERANCE_SAMPLES
                    ) {
                        finishUtterance()
                    }
                }
                if (inSpeech) finishUtterance()
            }
        } finally {
            captureJob.cancelAndJoin()
            frameChannel.cancel()
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
                    offlineVoice(engine, locale) == null
                ) {
                    if (continuation.isActive) continuation.resume(AppResult.Error("Offline TTS voice is unavailable"))
                    engine.shutdown()
                    return@TextToSpeech
                }
                val languageStatus = engine.setLanguage(locale)
                val voice = offlineVoice(engine, locale)
                if (languageStatus < TextToSpeech.LANG_AVAILABLE || voice == null ||
                    engine.setVoice(voice) == TextToSpeech.ERROR
                ) {
                    if (continuation.isActive) continuation.resume(AppResult.Error("Offline TTS voice is unavailable"))
                    engine.shutdown()
                    return@TextToSpeech
                }
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
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resume(AppResult.Error("Offline TTS synthesis failed"))
                    engine.shutdown()
                }
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
                        offlineVoice(engine, locale) != null
                } else false
                if (continuation.isActive) continuation.resume(available)
            engine?.shutdown()
        }
            continuation.invokeOnCancellation { tts?.shutdown() }
        }

    private fun offlineVoice(
        engine: TextToSpeech,
        locale: Locale
    ): android.speech.tts.Voice? = engine.voices.firstOrNull { voice ->
        val sameLanguage = voice.locale == locale || voice.locale.language == locale.language
        sameLanguage && !voice.features.orEmpty()
            .contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val AUDIO_FRAME_SAMPLES = 320 // 20 ms at 16 kHz
        private const val FRAME_CHANNEL_CAPACITY = 64
        private const val PRE_ROLL_FRAMES = 8 // 160 ms
        private const val MIN_SPEECH_FRAMES = 2
        private const val TRAILING_SILENCE_FRAMES = 35 // 700 ms
        private const val MIN_UTTERANCE_SAMPLES = SAMPLE_RATE / 2
        private const val PARTIAL_MIN_SAMPLES = SAMPLE_RATE
        private const val PARTIAL_INTERVAL_MS = 1_200L
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 30
        private const val SPEECH_RMS_THRESHOLD = 0.018f

        private fun frameSize(bufferSize: Int): Int =
            (bufferSize / Short.SIZE_BYTES).coerceAtLeast(AUDIO_FRAME_SAMPLES * 2)

        private fun rms(samples: ShortArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            samples.forEach { sample ->
                val normalized = sample.toDouble() / Short.MAX_VALUE
                sum += normalized * normalized
            }
            return sqrt(sum / samples.size).toFloat()
        }
    }
}
