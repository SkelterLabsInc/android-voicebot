package aiq.voicebot.example.android;

import aiq.voicebot.v1alpha1.*;
import aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent;
import aiq.voicebot.v1alpha1.VoicebotGrpc.VoicebotStub;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;

import javax.net.ssl.SSLContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;

import static aiq.voicebot.v1alpha1.RecognitionConfig.AudioEncoding.LINEAR16;
import static aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent.*;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.media.AudioFormat.*;
import static android.media.AudioRecord.*;
import static android.media.MediaRecorder.AudioSource.MIC;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.makeText;

public class MainActivity extends AppCompatActivity implements StreamObserver<StreamingTalkResponse> {
    /**
     * 안드로이드 기기에서 오디오 저장을 위한 권한을 요청하는 인식값(id). 임의의 값을 사용할 수 있다.
     */
    private static final int REQUEST_RECORD_AUDIO = 300;

    /**
     * 보이스봇 사용을 위한 API Key. 보이스 봇 프로젝트 설정에 사용한 값을 사용해야 한다.
     */
    private static final String API_KEY = "<<Your API Key>>>>";

    /**
     * 보이스봇 서버의 호스트명. SaaS의 경우 aiq.skelterlabs.com을 사용하지만, 자체 구축(on-premise)의 경우 해당 호스트명을 사용한다.
     */
    private static final String HOSTNAME = "aiq.skelterlabs.com";

    /**
     * 보이스봇 서버 접속을 위한 포트 번호
     */
    private static final int PORT = 443;

    /**
     * 안드로이드에서 로그 출력을 위한 태그
     */
    private static final String TAG = "voicebot-client";

    /**
     * 현재 보이스봇과 대화중 인지 여부
     */
    private boolean isRecording = false;

    /**
     * gRpc 통신 채널
     */
    private ManagedChannel channel = null;

    /**
     * gRpc 비동기 통신 방식에서 수신을 담당하는 객체
     */
    private StreamObserver<StreamingTalkRequest> botStream = null;

    /**
     * 안드로이드에서 오디오를 출력을 제어하는 객체
     */
    private AudioTrack audioTrack = null;

    /**
     * 현재 보이스봇의 상태
     */
    private TalkEvent state = LISTENING;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 현재 activity의 화면을 읽어들인다.
        setContentView(R.layout.activity_main);

        // 시작 버튼을 누른 경우
        findViewById(R.id.start_button).setOnClickListener(view -> tryStart());
        // 종료 버튼을 누른 경우
        findViewById(R.id.stop_button).setOnClickListener(view -> stop());
    }

    /**
     * 대화를 시도한 경우 마이크 장치에 대한 권한을 확인후
     * - 권한이 없는 경우 사용자 승인을 요청한다.
     * - 권한이 있는 경우 대화를 시작한다.
     */
    private void tryStart() {
        // 마이크 권한을 확인한다.
        if (checkSelfPermission(RECORD_AUDIO) != PERMISSION_GRANTED) { // 마이크 권한이 없는 경우
            // 사용자 승인을 요청한다.
            requestPermissions(new String[] { RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
        } else { // 마이크 권한이 있는 경우
            start();
        }
    }

    /**
     * 대화를 시작한다.
     */
    private void start() {
        // 현재 이미 대화 중인지 확인한다.
        if (isRecording) {
            // 현재 대화 중임을 사용자에게 알린다.
            makeText(this, "Already running", LENGTH_LONG).show();
            return ;
        }
        // 대화를 시작한다.
        try {
            startGrpc();
            startAudioIn();
            startAudioOut();
        } catch (final Throwable ex) {
            Log.e(TAG, "Unexpected exception:", ex);
            makeText(this, "Fail to start!! Check log", LENGTH_LONG).show();
        }
    }

    /**
     * 대화를 중단한다.
     */
    private void stop() {
        stopGrpc();
        stopAudioIn();
        stopAudioOut();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Log.d(TAG, "Receive the permission: " + requestCode);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            // 사용자가 마이크 권한을 변경한 경우
            if (Arrays.stream(grantResults).anyMatch(i -> i == PERMISSION_GRANTED)) { // 사용자가 승인한 경우
                start();
            } else { // 사용자가 거부한 경우
                makeText(this, "MIC permission needed", LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * 안드로이드의 마이크로부터 mono 16KHz 16bit 샘플링 음원을 읽을 수 있도록 한다.
     */
    private void startAudioIn() {
        isRecording = false;
        /**
         * 오디오 데이타를 처리하기 위한 객체.
         */
        final Runnable recorder = () -> {
            // 샘플링 비율은 16KHz
            final int sampingRate = 16_000;

            // 오디오 음원을 위한 버퍼 크기를 계산한다.
            int bufferSize = AudioRecord.getMinBufferSize(sampingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
            if (bufferSize == ERROR || bufferSize == ERROR_BAD_VALUE) {
                bufferSize = 2 * sampingRate;
            }

            // 오디오 입력을 제어하는 객체를 생성한다.
            final AudioRecord audioRecord = new AudioRecord(MIC, sampingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize);

            if (audioRecord.getState() == STATE_INITIALIZED) { // 정상적으로 생성되었다면
                audioRecord.startRecording();
                final short[] shortArray = new short[bufferSize / 2];
                isRecording = true;
                /*
                 * 대화 중 일 때, 오디오 장치에서 데이타를 읽어서 보이스봇에게 전달한다.
                 * 대화 상태 변수는 사용자가 종료 버튼을 누를 때 바뀐다.
                 */
                while (isRecording) {
                    // 오디오 장치에서 음원을 읽는다.
                    final int nRead = audioRecord.read(shortArray, 0, shortArray.length);

                    if (state == LISTENING || state == SPEAKING_FINISHED) { // 현재 보이스봇이 듣고 있다면

                        // 보이스 봇에 음성 데이타를 전달하기 위한 버퍼를 생성하고 데이타를 복사한다.
                        final ByteBuffer buffer = ByteBuffer.allocate(2 * nRead);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        final ShortBuffer shortBuffer = buffer.asShortBuffer();
                        shortBuffer.put(shortArray, 0, nRead);
                        final byte[] bytes = buffer.array();
                        // gRpc 데이타 객체
                        final StreamingTalkRequest req = StreamingTalkRequest.newBuilder()
                                .setAudioContent(ByteString.copyFrom(bytes))
                                .build();
                        // 보이스봇에게 데이타를 전송한다.
                        botStream.onNext(req);
                    }
                }

                // 마이크의 녹음을 중단한다.
                audioRecord.stop();
                // 장치를 해제한다.
                audioRecord.release();
            }

        };

        // 사용자 조작에 방해되지 않도록 백그라운드 쓰레드에서 처리한다.
        new Thread(recorder).start();
    }

    /**
     * 마이크로부터 오디오 입력을 중단한다.
     */
    private void stopAudioIn() {
        isRecording = false;
    }

    /**
     * 보이스봇과 의 gRpc 통식을 시작한다.
     *
     * @throws Exception 통신 과정에서 발생하는 예외들. 예외에 따른 처리를 하지 않는다.
     */
    private void startGrpc() throws Exception {
        // 통신을 위한 보안을 위한 TLS를 사용하는 경우, SSLContext 객체를 사용해야 한다.
        final SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        // 보안 객체 초기화
        sslContext.init(null, null, null);

        // 통신 채널을 연결한다.
        channel = OkHttpChannelBuilder.forAddress(HOSTNAME, PORT)
                .maxInboundMetadataSize(16 * 1024 * 1024)
                .overrideAuthority(HOSTNAME)
                .useTransportSecurity()
                .sslSocketFactory(sslContext.getSocketFactory())
                .build();

        // gRpc API 객체를 생성하고, API Key로 인증한다.
        final VoicebotStub stub = VoicebotGrpc.newStub(channel).withCallCredentials(new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                try {
                    final Metadata metadata = new Metadata();
                    metadata.put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), API_KEY);
                    applier.apply(metadata);
                } catch (final Throwable ex) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(ex));
                }
            }

            @Override
            public void thisUsesUnstableApi() {
            }
        });

        // 수신 객체
        final StreamObserver<StreamingTalkRequest> stream = stub.streamingTalk(this);

        // 데이터 교환을 위한 설정들 전달한다. mono 16KHz 16bit 데이타로 전달할 것임을 알린다.
        final StreamingTalkRequest.StreamingTalkConfig talkConfig = StreamingTalkRequest.StreamingTalkConfig.newBuilder()
                .setStreamingRecognitionConfig(
                        StreamingRecognitionConfig.newBuilder()
                                .setConfig(RecognitionConfig.newBuilder()
                                        .setEncoding(LINEAR16)
                                        .setSampleRateHertz(16_000)
                                        .build())
                                .build()
                )
                // 대화 세션을 위한 id를 생성하여 전달한다.
                .setTalkId(UUID.randomUUID().toString())
                // 사용자 변수가 필요하다면, 다음과 같이 전달한다.
                .setStartingUserContextJson(
                        "{" +
                        "\"variables\": {" +
                        "\"cellphone\": {" +
                        "\"string_\": \"01012341234\"" +
                        "}" +
                        "}" +
                        "}"
                )
                .build();
        // 데이타를 전송한다.
        stream.onNext(StreamingTalkRequest.newBuilder().setStreamingTalkConfig(talkConfig).build());
        botStream = stream;
    }

    /**
     * gRpc 통신을 종료한다.
     */
    private void stopGrpc() {
        final ManagedChannel backup = channel;
        channel = null;
        if (null != backup) {
            backup.shutdown();
        }
    }

    /**
     * 안드로이드의 스피커로 보이스 봇의 음성을 출력한다.
     */
    private void startAudioOut() {
        // 샘플링 비율은 16KHz
        final int sampingRate = 16_000;

        // 출력 음성을 위한 버퍼 크기
        int bufferSize = AudioRecord.getMinBufferSize(sampingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
        if (bufferSize == ERROR || bufferSize == ERROR_BAD_VALUE) {
            bufferSize = 2 * sampingRate;
        }

        // 음성 출력을 위해 장치를 제어하는 객체
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(ENCODING_PCM_16BIT)
                        .setSampleRate(sampingRate)
                        .setChannelMask(CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        // 음성 출력을 시작한다.
        audioTrack.play();
    }

    /**
     * 안드로이드 스피커의 출력을 중단한다.
     */
    void stopAudioOut() {
        final AudioTrack backup = audioTrack;
        audioTrack = null;
        if (backup != null) {
            backup.stop();
            backup.release();
        }
    }

    /**
     * gRpc의 데이터 수신의 call back 함수
     * @param res 전달받은 데이타
     */
    @Override
    public void onNext(StreamingTalkResponse res) {
        // 현재 상태를 확인한다.
        if (state == NOTHING) {
            // 보이스봇의 음성 데이타
            final ByteString audioContent = res.getStreamingSynthesizeSpeechResponse()
                    .getAudioContent();
            if (audioContent != null && !audioContent.isEmpty()) {
                // 음성 데이타를 스피커로 출력한다.
                final byte[] bytes = audioContent.toByteArray();
                audioTrack.write(bytes, 0, bytes.length);
            }
        }
        state = res.getTalkEvent();
    }

    /**
     * gRpc 통신 과정에 예외가 발생한 경우에 대한 call back 함수
     * @param t 발생한 예외
     */
    @Override
    public void onError(final Throwable t) {
        Log.e(TAG, "Unexpected exception", t);
        stop();
    }

    /**
     * gRpc 통신이 종료된 경우의 call back 함수
     */
    @Override
    public void onCompleted() {
        Log.d(TAG, this.toString() + " completed");
    }
}
