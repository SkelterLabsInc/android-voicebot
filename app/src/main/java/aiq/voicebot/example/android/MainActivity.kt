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
    /**
     * 현재 보이스봇과 대화중 인지 여부
     */
    private var isRecording = false
    /**
     * gRpc 통신 채널
     */
    private var channel: ManagedChannel? = null
    /**
     * gRpc 비동기 통신 방식에서 수신을 담당하는 객체
     */
    private var botStream: StreamObserver<StreamingTalkRequest>? = null
    /**
     * 안드로이드에서 오디오를 출력을 제어하는 객체
     */
    private var audioTrack: AudioTrack? = null
    /**
     * 현재 보이스봇의 상태
     */
    private var state = LISTENING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 현재 activity의 화면을 읽어들인다.
        setContentView(R.layout.activity_main)

        // 시작 버튼을 누른 경우
        findViewById<Button>(R.id.start_button)?.setOnClickListener { tryStart() }

        // 종료 버튼을 누른 경우
        findViewById<Button>(R.id.stop_button)?.setOnClickListener { stop() }
    }

    /**
     * 대화를 시도한 경우 마이크 장치에 대한 권한을 확인후
     * - 권한이 없는 경우 사용자 승인을 요청한다.
     * - 권한이 있는 경우 대화를 시작한다.
     */
    private fun tryStart() {
        // 마이크 권한을 확인한다.
        if (checkSelfPermission(this, RECORD_AUDIO) != PERMISSION_GRANTED) { // 마이크 권한이 없는 경우
            // 사용자 승인을 요청한다.
            requestPermissions(arrayOf(RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else { // 마이크 권한이 있는 경우
            start()
        }
    }

    /**
     * 대화를 시작한다.
     */
    private fun start() {
        // 현재 이미 대화 중인지 확인한다.
        if (isRecording) {
            // 현재 대화 중임을 사용자에게 알린다.
            makeText(this, "Already running", Toast.LENGTH_LONG).show()
            return
        }
        // 대화를 시작한다.
        startGrpc()
        startAudioIn()
        startAudioOut()
    }

    /**
     * 대화를 중단한다.
     */
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
            // 사용자가 마이크 권한을 변경한 경우
            REQUEST_RECORD_AUDIO -> if (grantResults.any { it == PERMISSION_GRANTED }) { // 사용자가 승인한 경우
                start()
            } else { // 사용자가 거부한 경우
                makeText(this, "MIC permission needed", Toast.LENGTH_LONG).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * 안드로이드의 마이크로부터 mono 16KHz 16bit 샘플링 음원을 읽을 수 있도록 한다.
     */
    private fun startAudioIn() {
        isRecording = false
        /**
         * 오디오 데이타를 처리하기 위한 객체.
         */
        val recorder = Runnable {
            // 샘플링 비율은 16KHz
            val samplingRate = 16_000

            // 오디오 음원을 위한 버퍼 크기를 계산한다.
            val bufferSize = getMinBufferSize(samplingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
                    .takeUnless { it == ERROR || it == ERROR_BAD_VALUE } ?: (2 * samplingRate)

            // 오디오 입력을 제어하는 객체를 생성한다.
            val audioRecord =
                AudioRecord(MIC, samplingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize)
            if (audioRecord.state == STATE_INITIALIZED) { // 정상적으로 생성되었다면
                audioRecord.startRecording()

                val shortArray = ShortArray(bufferSize / 2)
                isRecording = true

                /*
                 * 대화 중 일 때, 오디오 장치에서 데이타를 읽어서 보이스봇에게 전달한다.
                 * 대화 상태 변수는 사용자가 종료 버튼을 누를 때 바뀐다.
                 */
                while (isRecording) {
                    // 오디오 장치에서 음원을 읽는다.
                    val nRead = audioRecord.read(shortArray, 0, shortArray.size)
                    if (state in arrayListOf(LISTENING, SPEAKING_FINISHED)) { // 현재 보이스봇이 듣고 있다면
                        // 보이스 봇에 음성 데이타를 전달하기 위한 버퍼를 생성하고 데이타를 복사한다.
                        val buffer = ByteBuffer.allocate(2 * nRead).also { it.order(LITTLE_ENDIAN) }
                        buffer.asShortBuffer().put(shortArray, 0, nRead)
                        val bytes = buffer.array()
                        // gRpc 데이타 객체
                        val req = StreamingTalkRequest.newBuilder()
                            .apply { audioContent = ByteString.copyFrom(bytes) }
                            .build()
                        // 보이스봇에게 데이타를 전송한다.
                        botStream?.onNext(req)
                    }
                }

                // 마이크의 녹음을 중단한다.
                audioRecord.stop()
                // 장치를 해제한다.
                audioRecord.release()
            }
        }

        // 사용자 조작에 방해되지 않도록 백그라운드 쓰레드에서 처리한다.
        Thread(recorder).start()
    }

    /**
     * 마이크로부터 오디오 입력을 중단한다.
     */
    private fun stopAudioIn() {
        isRecording = false
    }

    /**
     * 보이스봇과 의 gRpc 통식을 시작한다.
     *
     * @throws Exception 통신 과정에서 발생하는 예외들. 예외에 따른 처리를 하지 않는다.
     */
    private fun startGrpc() {
        // 통신 채널을 연결한다.
        channel = OkHttpChannelBuilder.forAddress(HOSTNAME, PORT)
            .maxInboundMessageSize(16 * 1024 * 1024)
            .overrideAuthority(HOSTNAME)
            .useTransportSecurity()
            .sslSocketFactory(
                // 통신을 위한 보안을 위한 TLS를 사용하는 경우, SSLContext 객체를 사용해야 한다.
                SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }.socketFactory
            )
            .build()

        // gRpc API 객체를 생성하고, API Key로 인증한다.
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

        // 수신 객체
        val stream = stub.streamingTalk(this)

        // 데이터 교환을 위한 설정들 전달한다. mono 16KHz 16bit 데이타로 전달할 것임을 알린다.
        val talkConfig = StreamingTalkConfig.newBuilder()
            .apply { streamingRecognitionConfig = StreamingRecognitionConfig.newBuilder()
                .apply { config = RecognitionConfig.newBuilder()
                    .apply { encoding = LINEAR16 }
                    .apply { sampleRateHertz = 16_000 }
                    .build() }
                .build()
                // 대화 세션을 위한 id를 생성하여 전달한다.
                talkId = UUID.randomUUID().toString()
                // 사용자 변수가 필요하다면, 다음과 같이 전달한다.
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
        // 데이타를 전송한다.
        stream.onNext(StreamingTalkRequest.newBuilder()
            .apply { streamingTalkConfig = talkConfig }
            .build())
        botStream = stream
    }

    /**
     * gRpc 통신을 종료한다.
     */
    private fun stopGrpc() {
        val backup = channel
        channel = null
        backup?.shutdown()
    }

    /**
     * 안드로이드의 스피커로 보이스 봇의 음성을 출력한다.
     */
    private fun startAudioOut() {
        // 샘플링 비율은 16KHz
        val samplingRate = 16_000

        // 출력 음성을 위한 버퍼 크기
        val bufferSize =
            AudioTrack.getMinBufferSize(samplingRate, CHANNEL_OUT_MONO, ENCODING_PCM_16BIT)
                .takeUnless { it == ERROR || it == ERROR_BAD_VALUE }
                ?: (samplingRate * 2)

        // 음성 출력을 위해 장치를 제어하는 객체
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
        // 음성 출력을 시작한다.
        audioTrack?.play()
    }

    /**
     * 안드로이드 스피커의 출력을 중단한다.
     */
    private fun stopAudioOut() {
        val backup = audioTrack
        audioTrack = null
        backup?.stop()
        backup?.release()
    }

    /**
     * gRpc의 데이터 수신의 call back 함수
     * @param res 전달받은 데이타
     */
    override fun onNext(res: StreamingTalkResponse) {
        // 음성 데이타를 스피커로 출력한다.
        res.takeIf { state == NOTHING }
            ?.streamingSynthesizeSpeechResponse
            ?.audioContent
            ?.takeUnless { it.isEmpty }
            ?.let(ByteString::toByteArray)
            ?.let { bytes ->  audioTrack?.write(bytes, 0, bytes.size) }
        state = res.talkEvent
    }

    /**
     * gRpc 통신 과정에 예외가 발생한 경우에 대한 call back 함수
     * @param t 발생한 예외
     */
    override fun onError(t: Throwable) {
        Log.e(TAG, "Unexpected exception", t)
        stopGrpc()
    }

    /**
     * gRpc 통신이 종료된 경우의 call back 함수
     */
    override fun onCompleted() {
        Log.d(TAG, "$this completed")
        stopGrpc()
    }

    companion object {
        /**
         * 안드로이드 기기에서 오디오 저장을 위한 권한을 요청하는 인식값(id). 임의의 값을 사용할 수 있다.
         */
        const val REQUEST_RECORD_AUDIO = 300
        /**
         * 보이스봇 사용을 위한 API Key. 보이스 봇 프로젝트 설정에 사용한 값을 사용해야 한다.
         */
        const val API_KEY = "<<Your API Key>>"
        /**
         * 보이스봇 서버의 호스트명. SaaS의 경우 aiq.skelterlabs.com을 사용하지만, 자체 구축(on-premise)의 경우 해당 호스트명을 사용한다.
         */
        const val HOSTNAME = "aiq.skelterlabs.com"
        /**
         * 보이스봇 서버 접속을 위한 포트 번호
         */
        const val PORT = 443
        /**
         * 안드로이드에서 로그 출력을 위한 태그
         */
        const val TAG = "voicebot-client"

        init {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        }
    }
}