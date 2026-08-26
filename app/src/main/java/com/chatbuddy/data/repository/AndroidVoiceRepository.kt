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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import kotlin.coroutines.resume
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

internal enum class AudioReadState {
    DATA,
    EMPTY,
    TERMINAL
}

internal fun classifyAudioRead(read: Int): AudioReadState = when {
    read > 0 -> AudioReadState.DATA
    read == 0 -> AudioReadState.EMPTY
    else -> AudioReadState.TERMINAL
}

internal fun calculateAudioRms(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    samples.forEach { sample ->
        val normalized = sample.toDouble() / Short.MAX_VALUE
        sum += normalized * normalized
    }
    return sqrt(sum / samples.size).toFloat()
}

@Singleton
class AndroidVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisper: WhisperEngine
) : VoiceRepository {
    private val voiceSessionMutex = Mutex()
    private val ttsMutex = Mutex()

    override suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities> =
        withContext(Dispatchers.IO) {
            if (!hasRecordAudioPermission()) {
                return@withContext AppResult.Success(
                    VoiceCapabilities(
                        whisperReady = false,
                        offlineTtsReady = false,
                        message = "Microphone permission is required for live translation"
                    )
                )
            }

            val whisperSetup = try {
                whisper.ensureLoaded()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Whisper runtime is unavailable.", error)
            }
            val whisperReady = whisperSetup is AppResult.Success
            val ttsReady = if (whisperReady) checkOfflineTts(languageTag) else false
            AppResult.Success(
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
        voiceSessionMutex.withLock {
            if (!hasRecordAudioPermission()) {
                send(VoiceTranscript.Failed("Microphone permission is required for live translation"))
                return@withLock
            }

            val setup = try {
                whisper.ensureLoaded()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Whisper runtime is unavailable.", error)
            }
            when (setup) {
                is AppResult.Error -> {
                    send(VoiceTranscript.Failed(setup.message))
                    return@withLock
                }
                is AppResult.Success -> Unit
                AppResult.Loading -> {
                    send(VoiceTranscript.Failed("Whisper model is still preparing"))
                    return@withLock
                }
            }

            val normalizedLanguage = normalizeLanguageTag(languageTag)
                ?: run {
                    send(VoiceTranscript.Failed("A valid source language is required for live translation"))
                    return@withLock
                }
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                send(VoiceTranscript.Failed("This device cannot provide a 16 kHz microphone stream"))
                return@withLock
            }

            val bufferSize = minBufferSize.coerceAtLeast(AUDIO_FRAME_SAMPLES * Short.SIZE_BYTES * 2)
            val frameChannel = Channel<ShortArray>(capacity = FRAME_CHANNEL_CAPACITY)
            val captureJob = launch(Dispatchers.IO) {
                val recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                } catch (error: Throwable) {
                    trySend(VoiceTranscript.Failed("Unable to open microphone: ${safeMessage(error)}"))
                    frameChannel.close()
                    return@launch
                }

                var started = false
                try {
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        trySend(VoiceTranscript.Failed("Microphone is unavailable on this device"))
                        return@launch
                    }
                    recorder.startRecording()
                    started = true
                    while (isActive) {
                        val frame = ShortArray(AUDIO_FRAME_SAMPLES)
                        val read = recorder.read(
                            frame,
                            0,
                            frame.size,
                            AudioRecord.READ_BLOCKING
                        )
                        when (classifyAudioRead(read)) {
                            AudioReadState.DATA -> {
                                if (!frameChannel.trySend(frame.copyOf(read)).isSuccess) break
                            }
                            AudioReadState.EMPTY -> yield()
                            AudioReadState.TERMINAL -> {
                                if (isActive) {
                                    trySend(VoiceTranscript.Failed("Microphone capture stopped unexpectedly"))
                                }
                                break
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    if (isActive) {
                        trySend(VoiceTranscript.Failed("Microphone capture stopped: ${safeMessage(error)}"))
                    }
                } finally {
                    if (started) runCatching { recorder.stop() }
                    recorder.release()
                    frameChannel.close()
                }
            }

            try {
                withContext(Dispatchers.Default) {
                    val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
                    val utterance = ArrayList<Short>(MAX_UTTERANCE_SAMPLES)
                    var speechFrames = 0
                    var silenceFrames = 0
                    var inSpeech = false
                    var lastPartialAt = 0L
                    var pipelineFailed = false

                    fun appendSamples(samples: ShortArray) {
                        samples.forEach(utterance::add)
                    }

                    suspend fun transcribeSnapshot(partial: Boolean): Boolean {
                        if (utterance.size < MIN_UTTERANCE_SAMPLES) return true
                        var healthy = true
                        try {
                            whisper.transcribe(utterance.toShortArray(), normalizedLanguage, partial)
                                .collect { transcript ->
                                    currentCoroutineContext().ensureActive()
                                    if (transcript is VoiceTranscript.Failed) healthy = false
                                    send(transcript)
                                }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            send(VoiceTranscript.Failed("Whisper transcription failed: ${safeMessage(error)}"))
                            healthy = false
                        }
                        return healthy
                    }

                    suspend fun finishUtterance(): Boolean {
                        val healthy = transcribeSnapshot(partial = false)
                        utterance.clear()
                        preRoll.clear()
                        speechFrames = 0
                        silenceFrames = 0
                        inSpeech = false
                        lastPartialAt = 0L
                        return healthy
                    }

                    for (frame in frameChannel) {
                        currentCoroutineContext().ensureActive()
                        val speech = calculateAudioRms(frame) >= SPEECH_RMS_THRESHOLD
                        if (!inSpeech) {
                            preRoll.addLast(frame)
                            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                            speechFrames = if (speech) speechFrames + 1 else 0
                            if (speechFrames >= MIN_SPEECH_FRAMES) {
                                inSpeech = true
                                preRoll.forEach(::appendSamples)
                                preRoll.clear()
                                silenceFrames = 0
                                lastPartialAt = android.os.SystemClock.elapsedRealtime()
                            }
                            continue
                        }

                        appendSamples(frame)
                        silenceFrames = if (speech) 0 else silenceFrames + 1
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (utterance.size >= PARTIAL_MIN_SAMPLES &&
                            now - lastPartialAt >= PARTIAL_INTERVAL_MS
                        ) {
                            if (!transcribeSnapshot(partial = true)) {
                                pipelineFailed = true
                                break
                            }
                            lastPartialAt = now
                        }
                        if (silenceFrames >= TRAILING_SILENCE_FRAMES ||
                            utterance.size >= MAX_UTTERANCE_SAMPLES
                        ) {
                            if (!finishUtterance()) {
                                pipelineFailed = true
                                break
                            }
                        }
                    }
                    if (!pipelineFailed && inSpeech) finishUtterance()
                }
            } finally {
                captureJob.cancelAndJoin()
                frameChannel.cancel()
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { whisper.close() }
                }
            }
        }
    }

    override suspend fun speak(text: String, languageTag: String): AppResult<Unit> =
        ttsMutex.withLock {
            if (text.isBlank()) return@withLock AppResult.Success(Unit)
            val locale = localeFor(languageTag)
                ?: return@withLock AppResult.Error("A valid target language is required for offline TTS")
            withContext(Dispatchers.Main) {
                speakOnMain(text, locale)
            }
        }

    private suspend fun checkOfflineTts(languageTag: String): Boolean {
        val locale = localeFor(languageTag) ?: return false
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val lock = Any()
                    var engine: TextToSpeech? = null
                    var pendingStatus: Int? = null
                    var closed = false

                    fun shutdown() {
                        val toClose = synchronized(lock) {
                            if (closed) null else {
                                closed = true
                                engine.also { engine = null }
                            }
                        }
                        toClose?.shutdown()
                    }

                    fun complete(value: Boolean) {
                        if (continuation.isActive) {
                            runCatching { continuation.resume(value) }
                        }
                        shutdown()
                    }

                    fun handleInit(status: Int) {
                        val current = synchronized(lock) {
                            if (closed) return
                            if (engine == null) {
                                pendingStatus = status
                                return
                            }
                            engine
                        } ?: return
                        val available = if (status == TextToSpeech.SUCCESS) {
                            runCatching {
                                current.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE &&
                                    offlineVoice(current, locale) != null
                            }.getOrDefault(false)
                        } else false
                        complete(available)
                    }

                    val created = try {
                        TextToSpeech(context, ::handleInit)
                    } catch (error: Throwable) {
                        complete(false)
                        return@suspendCancellableCoroutine
                    }
                    var statusToHandle: Int? = null
                    var closeCreated = false
                    synchronized(lock) {
                        if (closed) {
                            closeCreated = true
                        } else {
                            engine = created
                            statusToHandle = pendingStatus
                            pendingStatus = null
                        }
                    }
                    if (closeCreated) {
                        created.shutdown()
                    } else {
                        statusToHandle?.let(::handleInit)
                    }
                    continuation.invokeOnCancellation { shutdown() }
                }
            } ?: false
        }
    }

    private suspend fun speakOnMain(text: String, locale: Locale): AppResult<Unit> =
        withTimeoutOrNull(TTS_SPEAK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val lock = Any()
                var engine: TextToSpeech? = null
                var pendingStatus: Int? = null
                var closed = false
                val requestId = UUID.randomUUID().toString()

                fun shutdown() {
                    val toClose = synchronized(lock) {
                        if (closed) null else {
                            closed = true
                            engine.also { engine = null }
                        }
                    }
                    toClose?.shutdown()
                }

                fun complete(result: AppResult<Unit>) {
                    if (continuation.isActive) {
                        runCatching { continuation.resume(result) }
                    }
                    shutdown()
                }

                fun configureAndSpeak(status: Int) {
                    val current = synchronized(lock) {
                        if (closed) return
                        if (engine == null) {
                            pendingStatus = status
                            return
                        }
                        engine
                    } ?: return
                    if (status != TextToSpeech.SUCCESS) {
                        complete(AppResult.Error("Offline TTS initialization failed"))
                        return
                    }
                    val voice = runCatching { offlineVoice(current, locale) }.getOrNull()
                    val languageStatus = runCatching { current.setLanguage(locale) }.getOrDefault(
                        TextToSpeech.LANG_NOT_SUPPORTED
                    )
                    if (languageStatus < TextToSpeech.LANG_AVAILABLE || voice == null ||
                        runCatching { current.setVoice(voice) }.getOrDefault(TextToSpeech.ERROR) ==
                        TextToSpeech.ERROR
                    ) {
                        complete(AppResult.Error("Offline TTS voice is unavailable"))
                        return
                    }
                    current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == requestId &&
                                continuation.isActive
                            ) {
                                complete(AppResult.Success(Unit))
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            if (utteranceId == requestId) {
                                complete(AppResult.Error("Offline TTS synthesis failed"))
                            }
                        }
                    })
                    val result = runCatching {
                        current.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId)
                    }.getOrDefault(TextToSpeech.ERROR)
                    if (result == TextToSpeech.ERROR) {
                        complete(AppResult.Error("Offline TTS synthesis failed"))
                    }
                }

                val created = try {
                    TextToSpeech(context, ::configureAndSpeak)
                } catch (error: Throwable) {
                    complete(AppResult.Error("Offline TTS initialization failed: ${safeMessage(error)}"))
                    return@suspendCancellableCoroutine
                }
                var statusToHandle: Int? = null
                var closeCreated = false
                synchronized(lock) {
                    if (closed) {
                        closeCreated = true
                    } else {
                        engine = created
                        statusToHandle = pendingStatus
                        pendingStatus = null
                    }
                }
                if (closeCreated) {
                    created.shutdown()
                } else {
                    statusToHandle?.let(::configureAndSpeak)
                }
                continuation.invokeOnCancellation { shutdown() }
            }
        } ?: AppResult.Error("Offline TTS timed out")

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun normalizeLanguageTag(languageTag: String): String? =
        languageTag.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() && it != "auto" }

    private fun localeFor(languageTag: String): Locale? = normalizeLanguageTag(languageTag)
        ?.let(Locale::forLanguageTag)
        ?.takeIf { it.language.isNotBlank() }

    private fun offlineVoice(engine: TextToSpeech, locale: Locale): android.speech.tts.Voice? =
        engine.voices.firstOrNull { voice ->
            val sameLanguage = voice.locale == locale || voice.locale.language == locale.language
            sameLanguage && !voice.isNetworkConnectionRequired
        }

    private fun safeMessage(error: Throwable): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "device error"

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
        private const val TTS_INIT_TIMEOUT_MS = 10_000L
        private const val TTS_SPEAK_TIMEOUT_MS = 60_000L
    }
}
