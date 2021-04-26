package aiq.voicebot.example.android

import aiq.voicebot.v1alpha1.*
import aiq.voicebot.v1alpha1.RecognitionConfig.AudioEncoding.LINEAR16
import aiq.voicebot.v1alpha1.StreamingTalkRequest.StreamingTalkConfig
import aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent.*
import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioAttributes
import android.media.AudioAttributes.CONTENT_TYPE_MUSIC
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioFormat
import android.media.AudioFormat.*
import android.media.AudioRecord
import android.media.AudioRecord.*
import android.media.AudioTrack
import android.media.AudioTrack.MODE_STREAM
import android.media.MediaRecorder.AudioSource.MIC
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import android.widget.Toast.makeText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import com.google.protobuf.ByteString
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import org.conscrypt.Conscrypt
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.Security
import java.util.*
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext

class MainActivity : AppCompatActivity(), StreamObserver<StreamingTalkResponse> {
    private var isRecording = false
    private var channel: ManagedChannel? = null
    private var botStream: StreamObserver<StreamingTalkRequest>? = null
    private var audioTrack: AudioTrack? = null
    private var state = LISTENING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Add a click handler to start button
        findViewById<Button>(R.id.start_button)?.setOnClickListener { tryStart() }

        // Add a click handler to stop button
        findViewById<Button>(R.id.stop_button)?.setOnClickListener { stop() }
    }

    private fun tryStart() {
        if (checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) {
            requestPermissions(arrayOf(RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else {
            start()
        }
    }

    private fun start() {
        if (isRecording) {
            makeText(this, "Already running", Toast.LENGTH_LONG).show()
            return
        }
        startGrpc()
        startAudioIn()
        startAudioOut()
    }

    private fun stop() {
        stopGrpc()
        stopAudioIn()
        stopAudioOut()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        Log.d(TAG, "Receive the permission: $requestCode")
        when (requestCode) {
            REQUEST_RECORD_AUDIO -> if (grantResults.any { it == PERMISSION_GRANTED }) {
                start()
            } else {
                makeText(this, "MIC permission needed", Toast.LENGTH_LONG).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startAudioIn() {
        isRecording = false
        val recorder = Runnable {
            val samplingRate = 16_000
            val bufferSize =
                AudioRecord.getMinBufferSize(samplingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
                    .takeUnless { it == ERROR || it == ERROR_BAD_VALUE } ?: (2 * samplingRate)
            val audioRecord =
                AudioRecord(MIC, samplingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize)
            if (audioRecord.state == STATE_INITIALIZED) {
                audioRecord.startRecording()

                val shortArray = ShortArray(bufferSize / 2)
                isRecording = true
                while (isRecording) {
                    val nRead = audioRecord.read(shortArray, 0, shortArray.size)
                    if (state in arrayListOf(LISTENING, SPEAKING_FINISHED)) {
                        val buffer = ByteBuffer.allocate(2 * nRead).also { it.order(LITTLE_ENDIAN) }
                        buffer.asShortBuffer().put(shortArray, 0, nRead)
                        val bytes = buffer.array()
                        val req = StreamingTalkRequest.newBuilder()
                            .apply { audioContent = ByteString.copyFrom(bytes) }
                            .build()
                        botStream?.onNext(req)
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            }
        }
        Thread(recorder).start()
    }

    private fun stopAudioIn() {
        isRecording = false
    }

    private fun startGrpc() {
        channel = OkHttpChannelBuilder.forAddress(HOSTNAME, PORT)
            .maxInboundMessageSize(16 * 1024 * 1024)
            .overrideAuthority(HOSTNAME)
            .useTransportSecurity()
            .sslSocketFactory(
                SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }.socketFactory
            )
            .build()
        val stub = VoicebotGrpc.newStub(channel).withCallCredentials(object : CallCredentials() {
            override fun applyRequestMetadata(
                requestInfo: RequestInfo?,
                appExecutor: Executor?,
                applier: MetadataApplier
            ) {
                runCatching { applier.apply(Metadata().apply { put(
                    Metadata.Key.of("x-api-key",
                        Metadata.ASCII_STRING_MARSHALLER
                    ), API_KEY) }) }
                    .onFailure { applier.fail(Status.UNAUTHENTICATED.withCause(it)) }
                    .getOrNull()
            }

            override fun thisUsesUnstableApi() {
            }
        })

        val stream = stub.streamingTalk(this)
        val talkConfig = StreamingTalkConfig.newBuilder()
            .apply { streamingRecognitionConfig = StreamingRecognitionConfig.newBuilder()
                .apply { config = RecognitionConfig.newBuilder()
                    .apply { encoding = LINEAR16 }
                    .apply { sampleRateHertz = 16_000 }
                    .build() }
                .build()
                talkId = UUID.randomUUID().toString()
                startingUserContextJson= """
                    {
                      "variables": {
                        "cellphone": {
                          "string_": "01012341234"
                        }
                      }
                    }
                """.trimIndent()
            }
            .build()
        stream.onNext(StreamingTalkRequest.newBuilder()
            .apply { streamingTalkConfig = talkConfig }
            .build())
        botStream = stream
    }

    private fun stopGrpc() {
        val backup = channel
        channel = null
        backup?.shutdown()
    }

    /**
     * Start speaker for audio-out
     */
    private fun startAudioOut() {
        val samplingRate = 16_000
        val bufferSize =
            AudioTrack.getMinBufferSize(samplingRate, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT)
                .takeUnless { it == ERROR || it == ERROR_BAD_VALUE }
                ?: (samplingRate * 2)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(USAGE_MEDIA)
                    .setContentType(CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING_PCM_16BIT)
                    .setSampleRate(samplingRate)
                    .setChannelMask(CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    /**
     * Stop speaker for audio-out
     */
    private fun stopAudioOut() {
        val backup = audioTrack
        audioTrack = null
        backup?.stop()
        backup?.release()
    }

    override fun onNext(res: StreamingTalkResponse) {
        res.takeIf { state == NOTHING }
            ?.streamingSynthesizeSpeechResponse
            ?.audioContent
            ?.takeUnless { it.isEmpty }
            ?.let(ByteString::toByteArray)
            ?.let { bytes ->  audioTrack?.write(bytes, 0, bytes.size) }
        state = res.talkEvent
    }

    override fun onError(t: Throwable) {
        Log.e(TAG, "Unexpected exception", t)
        stopGrpc()
    }

    override fun onCompleted() {
        Log.d(TAG, "$this completed")
        stopGrpc()
    }

    companion object {
        const val REQUEST_RECORD_AUDIO = 300
        const val API_KEY = "<<Your API Key>>"
        const val HOSTNAME = "aiq.skelterlabs.com"
        const val PORT = 443
        const val TAG = "voicebot-client"

        init {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        }
    }
}