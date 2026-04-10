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
 * Two-stage voice detection engine:
 *
 *  Stage 1 — Amplitude gate: AudioRecord continuously reads raw PCM.
 *             When RMS exceeds AMPLITUDE_THRESHOLD, we know someone is speaking.
 *
 *  Stage 2 — SpeechRecognizer: launched on the provided Looper (HandlerThread).
 *             Checks recognized text against trigger phrases.
 *             AudioRecord is paused during recognition to avoid OEM mic conflicts.
 */
class AudioListenerEngine(
    private val context: Context,
    private val speechLooper: Looper,
    private val onPhraseDetected: () -> Unit,
    private val onEngineError: () -> Unit
) {
    companion object {
        private const val TAG = "AudioListenerEngine"
        private const val SAMPLE_RATE = 16000
        // Tune: quiet room ~300-500; noisy room ~800-1000
        private const val AMPLITUDE_THRESHOLD = 500.0
        private const val READ_INTERVAL_MS = 100L
        private const val MAX_AUDIO_INIT_RETRIES = 3

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

    private val speechHandler = Handler(speechLooper)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var inSpeechSession = false

    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null
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
        speechHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
            inSpeechSession = false
        }
        stopAudioRecord()
    }

    private fun startAmplitudeLoop(retryCount: Int = 0) {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize failed")
            onEngineError()
            return
        }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 2 * 2) // 0.5s worth at 16kHz 16-bit

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
                Log.w(TAG, "AudioRecord init failed, retry ${retryCount + 1}")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
                startAmplitudeLoop(retryCount + 1)
            } else {
                Log.e(TAG, "AudioRecord init failed after $MAX_AUDIO_INIT_RETRIES retries")
                onEngineError()
            }
            return
        }

        audioRecord = record
        record.startRecording()

        val samples = ShortArray(bufferSize / 2)
        listeningThread = Thread {
            while (running) {
                val read = record.read(samples, 0, samples.size)
                if (read > 0 && !inSpeechSession) {
                    val rms = computeRms(samples, read)
                    if (rms > AMPLITUDE_THRESHOLD) {
                        triggerSpeechRecognition()
                    }
                }
                try { Thread.sleep(READ_INTERVAL_MS) } catch (_: InterruptedException) { break }
            }
        }.apply {
            name = "AmplitudeListenerThread"
            isDaemon = true
            start()
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

    private fun triggerSpeechRecognition() {
        if (!speechAvailable) {
            // No speech engine installed — treat any loud sound as the trigger (degraded mode)
            Log.w(TAG, "No speech recognizer available, using amplitude-only mode")
            mainHandler.post { onPhraseDetected() }
            return
        }

        inSpeechSession = true

        // Stop AudioRecord before handing microphone to SpeechRecognizer
        stopAudioRecord()

        speechHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty() && checkForTriggerPhrase(matches)) {
                        mainHandler.post { onPhraseDetected() }
                    }
                    finishSession()
                }

                override fun onPartialResults(partial: Bundle?) {
                    val matches = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty() && checkForTriggerPhrase(matches)) {
                        mainHandler.post { onPhraseDetected() }
                        speechRecognizer?.stopListening()
                    }
                }

                override fun onError(error: Int) {
                    Log.d(TAG, "SpeechRecognizer error code: $error")
                    // error 5 = ERROR_CLIENT (recognizer busy) — back off before retry
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                    }
                    finishSession()
                }

                // Required stubs
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
    }

    private fun finishSession() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        inSpeechSession = false
        // Hand the microphone back to AudioRecord
        if (running) {
            startAmplitudeLoop()
        }
    }

    private fun stopAudioRecord() {
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
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
