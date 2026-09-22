package com.chatbuddy.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.chatbuddy.ai.voice.SileroVad
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.delay
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

internal fun normalizeAudioVolume(samples: ShortArray): ShortArray {
    if (samples.isEmpty()) return samples
    var maxAbs = 0
    for (sample in samples) {
        val absVal = kotlin.math.abs(sample.toInt())
        if (absVal > maxAbs) maxAbs = absVal
    }
    if (maxAbs < 500) return samples
    val targetPeak = 24_000f
    val gain = (targetPeak / maxAbs).coerceIn(1.0f, 4.0f)
    if (gain <= 1.05f) return samples

    val boosted = ShortArray(samples.size)
    for (i in samples.indices) {
        val scaled = (samples[i] * gain).toInt()
        boosted[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return boosted
}
internal fun normalizeLanguageTag(languageTag: String): String? =
    languageTag.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() && it != "auto" }

/**
 * Maps a BCP-47/Android language tag to the 2-letter ISO 639-1 code that
 * whisper.cpp expects (e.g. "in" → "id", "zh-CN" → "zh").
 * Whisper does NOT accept the legacy "in" code for Indonesian — it only
 * recognises "id". This function is the single place where all such
 * normalisations live so the C++ layer never sees ambiguous codes.
 */
internal fun normalizeWhisperLanguageTag(languageTag: String): String? {
    val raw = normalizeLanguageTag(languageTag) ?: return null
    val lang = raw.substringBefore('-').substringBefore('_')
    return when (lang) {
        "in" -> "id"        // legacy Indonesian BCP-47 code → Whisper code
        else -> lang        // take base language only; Whisper doesn't use region codes
    }
}

internal fun resolveTtsCandidateLocales(languageTag: String): List<Locale> {
    val tag = normalizeLanguageTag(languageTag) ?: return emptyList()
    val langCode = tag.substringBefore('-').substringBefore('_')
    return when (langCode) {
        "id", "in" -> listOf(
            Locale("id", "ID"),
            Locale("in", "ID"),
            Locale("id"),
            Locale("in")
        )
        "en" -> listOf(Locale.US, Locale.UK, Locale("en"))
        "ja" -> listOf(Locale.JAPAN, Locale("ja"))
        "ko" -> listOf(Locale.KOREA, Locale("ko"))
        "zh" -> listOf(Locale.SIMPLIFIED_CHINESE, Locale.CHINA, Locale("zh"))
        "fr" -> listOf(Locale.FRANCE, Locale("fr"))
        "es" -> listOf(Locale("es", "ES"), Locale("es"))
        "de" -> listOf(Locale.GERMANY, Locale("de"))
        "it" -> listOf(Locale.ITALY, Locale("it"))
        "ru" -> listOf(Locale("ru", "RU"), Locale("ru"))
        "ar" -> listOf(Locale.forLanguageTag("ar-SA"), Locale("ar"))
        "th" -> listOf(Locale.forLanguageTag("th-TH"), Locale("th"))
        "vi" -> listOf(Locale.forLanguageTag("vi-VN"), Locale("vi"))
        "pt" -> listOf(Locale("pt", "BR"), Locale("pt"))
        else -> listOfNotNull(runCatching { Locale.forLanguageTag(tag) }.getOrNull())
    }
}

internal fun isLanguageMatch(voiceLocale: Locale, targetLocale: Locale): Boolean {
    val vLang = voiceLocale.language.lowercase(Locale.ROOT)
    val tLang = targetLocale.language.lowercase(Locale.ROOT)
    val langMatches = (vLang == tLang) || (vLang in listOf("id", "in") && tLang in listOf("id", "in"))
    if (!langMatches) return false
    val vCountry = voiceLocale.country.lowercase(Locale.ROOT)
    val tCountry = targetLocale.country.lowercase(Locale.ROOT)
    return tCountry.isEmpty() || vCountry.isEmpty() || vCountry == tCountry
}

@Singleton
class AndroidVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisper: WhisperEngine,
    private val sileroVad: SileroVad
) : VoiceRepository {
    // AtomicBoolean guard prevents concurrent recording sessions without holding a mutex
    // for the entire session duration (which caused deadlocks on speaker switch).
    private val recordingActive = AtomicBoolean(false)
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
                runCatching { sileroVad.ensureLoaded() }
                whisper.ensureLoaded()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Whisper runtime is unavailable.", error)
            }
            val whisperReady = whisperSetup is AppResult.Success
            val ttsReady = if (whisperReady) checkOfflineTts(languageTag) else false
            val headset = isHeadsetConnected()
            AppResult.Success(
                VoiceCapabilities(
                    whisperReady = whisperReady,
                    offlineTtsReady = ttsReady,
                    message = when {
                        !whisperReady -> (whisperSetup as? AppResult.Error)?.message
                            ?: "Whisper model/runtime is not installed"
                        !ttsReady -> "Offline Android voice is not available for this language"
                        else -> "Voice turn-taking is ready"
                    },
                    headsetConnected = headset
                )
            )
        }

    @SuppressLint("MissingPermission")
    override fun transcribe(languageTag: String): Flow<VoiceTranscript> = channelFlow {
        // Guard: if a previous session is still tearing down, give it a moment then reject.
        // This avoids the deadlock caused by voiceSessionMutex.withLock wrapping the entire
        // long-running session — the new speaker's transcribe() call would block forever
        // waiting for the old session's mutex to release during cleanup.
        if (!recordingActive.compareAndSet(false, true)) {
            // Give previous AudioRecord up to 300ms to release before failing
            var waited = 0
            while (recordingActive.get() && waited < 300) {
                delay(50)
                waited += 50
            }
            if (recordingActive.get()) {
                Log.w(TAG, "transcribe: previous session still active after 300ms, rejecting")
                send(VoiceTranscript.Failed("Microphone is busy — previous session still stopping"))
                return@channelFlow
            }
            recordingActive.set(true)
        }
        Log.d(TAG, "transcribe: session starting for lang=$languageTag")

        var activeRecorder: AudioRecord? = null
        try {
            if (!hasRecordAudioPermission()) {
                send(VoiceTranscript.Failed("Microphone permission is required for live translation"))
                return@channelFlow
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
                    Log.e(TAG, "transcribe: Whisper not ready: ${setup.message}")
                    send(VoiceTranscript.Failed(setup.message))
                    return@channelFlow
                }
                is AppResult.Success -> Unit
                AppResult.Loading -> {
                    send(VoiceTranscript.Failed("Whisper model is still preparing"))
                    return@channelFlow
                }
            }
            Log.d(TAG, "transcribe: Whisper ready, opening AudioRecord")

            val normalizedLanguage = normalizeWhisperLanguageTag(languageTag)
                ?: run {
                    send(VoiceTranscript.Failed("A valid source language is required for live translation"))
                    return@channelFlow
                }
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                send(VoiceTranscript.Failed("This device cannot provide a 16 kHz microphone stream"))
                return@channelFlow
            }

            val bufferSize = minBufferSize.coerceAtLeast(AUDIO_FRAME_SAMPLES * Short.SIZE_BYTES * 2)
            val frameChannel = Channel<ShortArray>(
                capacity = FRAME_CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            val captureJob = launch(Dispatchers.IO) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    trySend(VoiceTranscript.Failed("Microphone permission was revoked"))
                    frameChannel.close()
                    return@launch
                }
                val recorder = try {
                    // Priority 1: UNPROCESSED — provides the rawest signal for Whisper's
                    // own log-mel pipeline. Only available on devices that declare support.
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val supportsUnprocessed = audioManager?.getProperty(
                        android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
                    ) != null
                    val unprocessedRecorder = if (supportsUnprocessed) {
                        runCatching {
                            AudioRecord(
                                MediaRecorder.AudioSource.UNPROCESSED,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                        }.getOrNull()
                    } else null

                    when {
                        unprocessedRecorder != null -> {
                            Log.d(TAG, "transcribe: AudioRecord opened with UNPROCESSED (best for ASR)")
                            unprocessedRecorder
                        }
                        else -> {
                            unprocessedRecorder?.release()
                            // Priority 2: VOICE_RECOGNITION — bypasses most OEM processing.
                            val vrRecorder = AudioRecord(
                                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize
                            )
                            if (vrRecorder.state == AudioRecord.STATE_INITIALIZED) {
                                Log.d(TAG, "transcribe: AudioRecord opened with VOICE_RECOGNITION")
                                vrRecorder
                            } else {
                                vrRecorder.release()
                                Log.w(TAG, "transcribe: VOICE_RECOGNITION failed, falling back to MIC")
                                // Priority 3: MIC (last resort)
                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    SAMPLE_RATE,
                                    AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    bufferSize
                                )
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "transcribe: Failed to open AudioRecord: ${safeMessage(error)}")
                    trySend(VoiceTranscript.Failed("Unable to open microphone: ${safeMessage(error)}"))
                    frameChannel.close()
                    return@launch
                }
                activeRecorder = recorder

                val audioSessionId = recorder.audioSessionId
                // AGC is intentionally DISABLED: hardware AGC boosts noise during
                // silence, causing amplitude spikes that fool the energy VAD into
                // triggering a Whisper call, which then hallucinates text.
                // Whisper's internal log-mel normalization handles gain variation better.
                val agc: android.media.audiofx.AutomaticGainControl? = null
                // NoiseSuppressor remains enabled only when NOT on UNPROCESSED source,
                // where it can provide modest SNR improvement without the AGC side-effects.
                val ns = if (NoiseSuppressor.isAvailable()) {
                    runCatching {
                        NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                } else null

                var started = false
                try {
                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "transcribe: AudioRecord not initialized (state=${recorder.state})")
                        trySend(VoiceTranscript.Failed("Microphone is unavailable on this device"))
                        return@launch
                    }
                    recorder.startRecording()
                    started = true
                    Log.d(TAG, "transcribe: AudioRecord started (sessionId=$audioSessionId, agc=disabled, ns=${ns != null})")
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
                                frameChannel.trySend(frame.copyOf(read))
                            }
                            AudioReadState.EMPTY -> yield()
                            AudioReadState.TERMINAL -> {
                                Log.w(TAG, "transcribe: AudioRecord read returned terminal code $read")
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
                    Log.e(TAG, "transcribe: capture error: ${safeMessage(error)}")
                    if (isActive) {
                        trySend(VoiceTranscript.Failed("Microphone capture stopped: ${safeMessage(error)}"))
                    }
                } finally {
                    if (started) runCatching { recorder.stop() }
                    runCatching { agc?.release() }
                    runCatching { ns?.release() }
                    recorder.release()
                    activeRecorder = null
                    frameChannel.close()
                    Log.d(TAG, "transcribe: AudioRecord released")
                }
            }

            try {
                withContext(Dispatchers.Default) {
                    sileroVad.ensureLoaded()
                    sileroVad.resetState()

                    val preRoll = ArrayDeque<ShortArray>(PRE_ROLL_FRAMES)
                    val utterance = ArrayList<Short>(MAX_UTTERANCE_SAMPLES)
                    var consecutiveSpeechFrames = 0
                    var consecutiveSilenceFrames = 0
                    var confirmedSpeechFrames = 0
                    var inSpeech = false
                    var pipelineFailed = false

                    fun appendSamples(samples: ShortArray) {
                        samples.forEach(utterance::add)
                    }

                    suspend fun finishUtterance(): Boolean {
                        val totalAudioSamples = utterance.size
                        val totalDurationMs = totalAudioSamples * 1000L / SAMPLE_RATE
                        val speechDurationMs = confirmedSpeechFrames * (AUDIO_FRAME_SAMPLES * 1000L / SAMPLE_RATE)

                        // Speech content verification:
                        // Discard if total audio is too short (< 600ms) OR if confirmed speech frames < 350ms.
                        // This prevents background noise, room hum, chair creaks, or coughs from triggering Whisper!
                        if (totalDurationMs < 600L || speechDurationMs < MIN_CONFIRMED_SPEECH_MS) {
                            Log.d(TAG, "transcribe: discarded non-speech audio (total=${totalDurationMs}ms, speech=${speechDurationMs}ms)")
                            utterance.clear()
                            preRoll.clear()
                            consecutiveSpeechFrames = 0
                            consecutiveSilenceFrames = 0
                            confirmedSpeechFrames = 0
                            inSpeech = false
                            return true
                        }

                        var healthy = true
                        try {
                            val rawSamples = utterance.toShortArray()
                            val normalizedSamples = normalizeAudioVolume(rawSamples)
                            Log.d(TAG, "transcribe: running Whisper on ${rawSamples.size} samples (${totalDurationMs}ms, speech=${speechDurationMs}ms)")
                            whisper.transcribe(normalizedSamples, normalizedLanguage, partial = false)
                                .collect { transcript ->
                                    currentCoroutineContext().ensureActive()
                                    if (transcript is VoiceTranscript.Failed) {
                                        Log.e(TAG, "transcribe: Whisper failed: ${transcript.message}")
                                        healthy = false
                                    } else {
                                        Log.d(TAG, "transcribe: got transcript: $transcript")
                                    }
                                    send(transcript)
                                }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            send(VoiceTranscript.Failed("Whisper transcription failed: ${safeMessage(error)}"))
                            healthy = false
                        }
                        utterance.clear()
                        preRoll.clear()
                        consecutiveSpeechFrames = 0
                        consecutiveSilenceFrames = 0
                        confirmedSpeechFrames = 0
                        inSpeech = false
                        return healthy
                    }

                    var noiseFloor = 0.008f

                    for (frame in frameChannel) {
                        currentCoroutineContext().ensureActive()

                        // 1. Convert frame to FloatArray [-1.0, 1.0] for Silero VAD
                        val floatFrame = FloatArray(frame.size) { frame[it] / 32768.0f }
                        val speechProb = if (sileroVad.isReady && frame.size == SileroVad.FRAME_SIZE) {
                            sileroVad.infer(floatFrame)
                        } else -1f

                        val rms = calculateAudioRms(frame)
                        val dynamicThreshold = (noiseFloor * 1.6f + 0.005f).coerceIn(0.015f, 0.08f)

                        // 2. Determine if this frame is speech
                        val isSpeech = if (speechProb >= 0f) {
                            // Neural Silero VAD decision with hysteresis
                            if (inSpeech) {
                                speechProb >= VAD_SILENCE_PROB_THRESHOLD
                            } else {
                                speechProb >= VAD_SPEECH_PROB_THRESHOLD
                            }
                        } else {
                            // Fallback to energy-based VAD if Silero is unavailable
                            rms >= dynamicThreshold
                        }

                        if (!inSpeech) {
                            if (speechProb < 0f && rms < dynamicThreshold * 0.70f) {
                                noiseFloor = 0.98f * noiseFloor + 0.02f * rms
                            }
                            preRoll.addLast(frame)
                            while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

                            consecutiveSpeechFrames = if (isSpeech) consecutiveSpeechFrames + 1 else 0
                            if (consecutiveSpeechFrames >= MIN_SPEECH_TRIGGER_FRAMES) {
                                inSpeech = true
                                consecutiveSilenceFrames = 0
                                confirmedSpeechFrames = consecutiveSpeechFrames
                                preRoll.forEach(::appendSamples)
                                preRoll.clear()
                                Log.d(TAG, "transcribe: VAD speech start (prob=$speechProb rms=$rms)")
                            }
                            continue
                        }

                        // While inSpeech
                        appendSamples(frame)
                        if (isSpeech) {
                            confirmedSpeechFrames++
                            consecutiveSilenceFrames = 0
                        } else {
                            consecutiveSilenceFrames++
                        }

                        val silenceDurationMs = consecutiveSilenceFrames * (AUDIO_FRAME_SAMPLES * 1000L / SAMPLE_RATE)
                        val utteranceSamples = utterance.size

                        // Natural end-of-speech: require TRAILING_SILENCE_MS (1200ms) of silence
                        if (silenceDurationMs >= TRAILING_SILENCE_MS || utteranceSamples >= MAX_UTTERANCE_SAMPLES) {
                            Log.d(TAG, "transcribe: VAD speech end (samples=$utteranceSamples, silence=${silenceDurationMs}ms, confirmedSpeech=${confirmedSpeechFrames * 32}ms)")
                            if (!finishUtterance()) {
                                pipelineFailed = true
                                break
                            }
                        }
                    }
                    if (!pipelineFailed && utterance.isNotEmpty()) {
                        finishUtterance()
                    }
                }
            } finally {
                sileroVad.resetState()
                runCatching { activeRecorder?.stop() }
                captureJob.cancelAndJoin()
                frameChannel.cancel()
                Log.d(TAG, "transcribe: session ended for lang=$languageTag")
            }
        } finally {
            recordingActive.set(false)
            Log.d(TAG, "transcribe: recordingActive released for lang=$languageTag")
        }
    }

    private var warmTts: TextToSpeech? = null
    private var warmTtsInitJob: CompletableDeferred<Boolean>? = null

    private suspend fun getOrCreateWarmTts(): TextToSpeech? = withContext(Dispatchers.Main) {
        ttsMutex.withLock {
            warmTts?.let { return@withContext it }
            val initDeferred = CompletableDeferred<Boolean>()
            warmTtsInitJob = initDeferred
            val created = try {
                TextToSpeech(context) { status ->
                    initDeferred.complete(status == TextToSpeech.SUCCESS)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to instantiate TextToSpeech", e)
                return@withContext null
            }
            val success = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) {
                initDeferred.await()
            } ?: false
            if (success) {
                warmTts = created
                created
            } else {
                runCatching { created.shutdown() }
                warmTts = null
                null
            }
        }
    }

    override suspend fun stopSpeaking(): AppResult<Unit> = withContext(Dispatchers.Main) {
        ttsMutex.withLock {
            runCatching { warmTts?.stop() }
            AppResult.Success(Unit)
        }
    }

    override suspend fun speak(text: String, languageTag: String): AppResult<Unit> =
        withContext(Dispatchers.Main) {
            if (text.isBlank()) return@withContext AppResult.Success(Unit)
            val engine = getOrCreateWarmTts()
                ?: return@withContext AppResult.Error("Offline TTS engine could not be initialized")
            val candidates = resolveTtsCandidateLocales(languageTag)
            if (candidates.isEmpty()) {
                return@withContext AppResult.Error("Invalid target language for offline TTS")
            }

            ttsMutex.withLock {
                val supportedLocale = applyTtsLanguage(engine, candidates)
                    ?: return@withLock AppResult.Error("Offline TTS language is not supported on this device")
                val voice = offlineVoice(engine, supportedLocale)
                if (voice != null) {
                    runCatching { engine.voice = voice }
                }

                val requestId = UUID.randomUUID().toString()
                val synthesisResult = withTimeoutOrNull(TTS_SPEAK_TIMEOUT_MS) {
                    suspendCancellableCoroutine<AppResult<Unit>> { continuation ->
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) {
                                if (utteranceId == requestId && continuation.isActive) {
                                    runCatching { continuation.resume(AppResult.Success(Unit)) }
                                }
                            }

                            override fun onError(utteranceId: String?) {
                                if (utteranceId == requestId && continuation.isActive) {
                                    runCatching { continuation.resume(AppResult.Error("Offline TTS synthesis failed")) }
                                }
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                if (utteranceId == requestId && continuation.isActive) {
                                    runCatching { continuation.resume(AppResult.Error("Offline TTS synthesis error code: $errorCode")) }
                                }
                            }
                        })

                        val result = runCatching {
                            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, requestId)
                        }.getOrDefault(TextToSpeech.ERROR)

                        if (result == TextToSpeech.ERROR) {
                            if (continuation.isActive) {
                                continuation.resume(AppResult.Error("Offline TTS synthesis failed to queue"))
                            }
                        }

                        continuation.invokeOnCancellation {
                            runCatching { engine.stop() }
                        }
                    }
                }
                if (synthesisResult == null) {
                    runCatching { engine.stop() }
                    AppResult.Error("Offline TTS playback timed out")
                } else {
                    synthesisResult
                }
            }
        }

    private suspend fun checkOfflineTts(languageTag: String): Boolean {
        val candidates = resolveTtsCandidateLocales(languageTag)
        if (candidates.isEmpty()) return false
        val engine = getOrCreateWarmTts() ?: return false
        return withContext(Dispatchers.Main) {
            ttsMutex.withLock {
                candidates.any { loc ->
                    runCatching { engine.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
                }
            }
        }
    }

    private fun applyTtsLanguage(engine: TextToSpeech, candidateLocales: List<Locale>): Locale? {
        for (loc in candidateLocales) {
            val status = runCatching { engine.setLanguage(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (status >= TextToSpeech.LANG_AVAILABLE) {
                return loc
            }
        }
        return null
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun offlineVoice(engine: TextToSpeech, locale: Locale): android.speech.tts.Voice? =
        runCatching {
            val voices = engine.voices ?: return@runCatching null
            voices.firstOrNull { voice ->
                isLanguageMatch(voice.locale, locale) && !voice.isNetworkConnectionRequired
            } ?: voices.firstOrNull { voice ->
                isLanguageMatch(voice.locale, locale)
            }
        }.getOrNull()


    private fun safeMessage(error: Throwable): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: "device error"

    private fun isHeadsetConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val devices = runCatching {
                audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS or android.media.AudioManager.GET_DEVICES_INPUTS)
            }.getOrNull() ?: emptyArray()
            val hasHeadset = devices.any { device ->
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            if (hasHeadset) return true
        }
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn || audioManager.isWiredHeadsetOn
    }

    companion object {
        private const val TAG = "ChatBuddyVoice"
        private const val SAMPLE_RATE = 16_000
        // 512 samples = 32ms @ 16kHz, exactly matching Silero VAD v5 input chunk
        private const val AUDIO_FRAME_SAMPLES = 512
        private const val FRAME_CHANNEL_CAPACITY = 64
        // 10 frames * 32ms = 320ms pre-roll to capture initial consonants/syllables
        private const val PRE_ROLL_FRAMES = 10
        // 3 consecutive speech frames (96ms) to trigger speech onset (rejects clicks/taps)
        private const val MIN_SPEECH_TRIGGER_FRAMES = 3
        // 1200ms trailing silence allows natural human conversational pauses without cutting off mid-sentence
        private const val TRAILING_SILENCE_MS = 1200L
        // Must contain at least 350ms of confirmed human speech to be sent to Whisper (blocks noise/silence)
        private const val MIN_CONFIRMED_SPEECH_MS = 350L
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 25 // 25s max
        // Silero VAD probability thresholds with hysteresis
        private const val VAD_SPEECH_PROB_THRESHOLD = 0.50f
        private const val VAD_SILENCE_PROB_THRESHOLD = 0.35f
        private const val TTS_INIT_TIMEOUT_MS = 10_000L
        private const val TTS_SPEAK_TIMEOUT_MS = 60_000L
    }
}
