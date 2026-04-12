package com.phonefinder

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.math.sqrt

/**
 * Two-stage voice detection engine.
 *
 * Stage 1 — Amplitude gate: a background thread reads raw PCM via AudioRecord.
 *           When RMS exceeds AMPLITUDE_THRESHOLD someone is speaking.
 *
 * Stage 2 — SpeechRecognizer: runs on the MAIN thread (Android requirement on
 *           most devices). Checks recognized text against trigger phrases.
 *           AudioRecord is stopped while SpeechRecognizer holds the mic to
 *           avoid OEM mic-sharing conflicts.
 */
class AudioListenerEngine(
    private val context: Context,
    private val onPhraseDetected: () -> Unit,
    private val onEngineError: () -> Unit
) {
    companion object {
        private const val TAG = "AudioListenerEngine"
        private const val SAMPLE_RATE = 16000
        private const val AMPLITUDE_THRESHOLD = 500.0
        private const val READ_INTERVAL_MS = 100L
        private const val MAX_AUDIO_INIT_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        private val TRIGGER_PHRASES = listOf(
            "where's my phone",
            "wheres my phone",
            "where is my phone",
            "find my phone",
            "where my phone",
            "where phone",
            "hey phone"
        )
    }

    // All SpeechRecognizer operations happen on the main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var inSpeechSession = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var listeningThread: Thread? = null

    // Only accessed from the main thread
    private var speechRecognizer: SpeechRecognizer? = null

    private val speechAvailable = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (running) return
        running = true
        startAmplitudeLoop()
    }

    fun stop() {
        running = false
        listeningThread?.interrupt()
        listeningThread = null
        stopAudioRecord()
        // Destroy SpeechRecognizer on main thread where it was created
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            inSpeechSession = false
        }
    }

    // -------------------------------------------------------------------------
    // Stage 1 — Amplitude gate (background thread)
    // -------------------------------------------------------------------------

    private fun startAmplitudeLoop(retryCount: Int = 0) {
        Thread {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                Log.e(TAG, "getMinBufferSize failed: $minBuffer")
                mainHandler.post { onEngineError() }
                return@Thread
            }

            val bufferSize = maxOf(minBuffer, SAMPLE_RATE) // at least 0.5 s
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                if (retryCount < MAX_AUDIO_INIT_RETRIES) {
                    Log.w(TAG, "AudioRecord init failed, scheduling retry ${retryCount + 1}")
                    // postDelayed — never block the thread with sleep()
                    mainHandler.postDelayed({ startAmplitudeLoop(retryCount + 1) }, RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "AudioRecord init failed after $MAX_AUDIO_INIT_RETRIES retries")
                    mainHandler.post { onEngineError() }
                }
                return@Thread
            }

            audioRecord = record
            record.startRecording()

            val samples = ShortArray(bufferSize / 2)
            while (running && !inSpeechSession) {
                val read = record.read(samples, 0, samples.size)
                if (read > 0 && !inSpeechSession) {
                    val rms = computeRms(samples, read)
                    if (rms > AMPLITUDE_THRESHOLD) {
                        // Hand off to Stage 2 on the main thread
                        mainHandler.post { triggerSpeechRecognition() }
                        break
                    }
                }
                try { Thread.sleep(READ_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }

            // Release AudioRecord — SpeechRecognizer needs the mic
            record.stop()
            record.release()
            if (audioRecord === record) audioRecord = null
        }.also {
            it.name = "AmplitudeListenerThread"
            it.isDaemon = true
            it.start()
            listeningThread = it
        }
    }

    private fun computeRms(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toLong()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    // -------------------------------------------------------------------------
    // Stage 2 — SpeechRecognizer (main thread only)
    // -------------------------------------------------------------------------

    private fun triggerSpeechRecognition() {
        if (inSpeechSession || !running) return
        inSpeechSession = true

        if (!speechAvailable) {
            Log.w(TAG, "No speech recognizer — amplitude-only mode")
            onPhraseDetected()
            finishSession()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && checkForTriggerPhrase(matches)) {
                    onPhraseDetected()
                }
                finishSession()
            }

            override fun onPartialResults(partial: Bundle?) {
                val matches = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && checkForTriggerPhrase(matches)) {
                    onPhraseDetected()
                    speechRecognizer?.stopListening()
                }
            }

            override fun onError(error: Int) {
                Log.d(TAG, "SpeechRecognizer error: $error")
                // ERROR_CLIENT (5) = recognizer was busy; delay before returning to Stage 1
                val delay = if (error == SpeechRecognizer.ERROR_CLIENT) 600L else 0L
                mainHandler.postDelayed({ finishSession() }, delay)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        speechRecognizer?.startListening(intent)
    }

    /** Called on main thread after each SpeechRecognizer session ends. */
    private fun finishSession() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        inSpeechSession = false
        if (running) {
            startAmplitudeLoop()
        }
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        audioRecord = null
        listeningThread?.interrupt()
        listeningThread = null
    }

    private fun checkForTriggerPhrase(candidates: List<String>): Boolean {
        return candidates.any { result ->
            val lower = result.lowercase()
            TRIGGER_PHRASES.any { phrase -> lower.contains(phrase) }
        }
    }
}
