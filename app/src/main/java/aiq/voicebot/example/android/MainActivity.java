package aiq.voicebot.example.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;

import aiq.voicebot.v1alpha1.RecognitionConfig;
import aiq.voicebot.v1alpha1.StreamingRecognitionConfig;
import aiq.voicebot.v1alpha1.StreamingTalkRequest;
import aiq.voicebot.v1alpha1.StreamingTalkResponse;
import aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent;
import aiq.voicebot.v1alpha1.VoicebotGrpc;
import aiq.voicebot.v1alpha1.VoicebotGrpc.VoicebotStub;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.StreamObserver;

import static aiq.voicebot.v1alpha1.RecognitionConfig.AudioEncoding.LINEAR16;
import static aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent.LISTENING;
import static aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent.NOTHING;
import static aiq.voicebot.v1alpha1.StreamingTalkResponse.TalkEvent.SPEAKING_FINISHED;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.media.AudioFormat.CHANNEL_IN_MONO;
import static android.media.AudioFormat.CHANNEL_OUT_MONO;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;
import static android.media.AudioRecord.ERROR;
import static android.media.AudioRecord.ERROR_BAD_VALUE;
import static android.media.AudioRecord.STATE_INITIALIZED;
import static android.media.MediaRecorder.AudioSource.MIC;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.makeText;

public class MainActivity extends AppCompatActivity implements StreamObserver<StreamingTalkResponse> {
    private static final int REQUEST_RECORD_AUDIO = 300;
    private static final String API_KEY = "<<Your API Key>>>>";
    private static final String HOSTNAME = "aiq.skelterlabs.com";
    private static final int PORT = 443;
    private static final String TAG = "voicebot-client";

    private boolean isRecording = false;
    private ManagedChannel channel = null;
    private StreamObserver<StreamingTalkRequest> botStream = null;
    private AudioTrack audioTrack = null;
    private TalkEvent state = LISTENING;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.start_button).setOnClickListener(view -> tryStart());
        findViewById(R.id.stop_button).setOnClickListener(view -> stop());
    }

    private void tryStart() {
        if (checkSelfPermission(RECORD_AUDIO) != PERMISSION_GRANTED) {
            requestPermissions(new String[] { RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
        } else {
            start();
        }
    }

    private void start() {
        if (isRecording) {
            makeText(this, "Already running", LENGTH_LONG).show();
            return ;
        }
        try {
            startGrpc();
            startAudioIn();
            startAudioOut();
        } catch (final Throwable ex) {
            Log.e(TAG, "Unexpected exception:", ex);
            makeText(this, "Fail to start!! Check log", LENGTH_LONG).show();
        }
    }

    private void stop() {
        stopGrpc();
        stopAudioIn();
        stopAudioOut();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Log.d(TAG, "Receive the permission: " + requestCode);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (Arrays.stream(grantResults).anyMatch(i -> i == PERMISSION_GRANTED)) {
                start();
            } else {
                makeText(this, "MIC permission needed", LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startAudioIn() {
        isRecording = false;
        final Runnable recorder = () -> {
            final int sampingRate = 16_000;
            int bufferSize = AudioRecord.getMinBufferSize(sampingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
            if (bufferSize == ERROR || bufferSize == ERROR_BAD_VALUE) {
                bufferSize = 2 * sampingRate;
            }
            final AudioRecord audioRecord = new AudioRecord(MIC, sampingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize);
            if (audioRecord.getState() == STATE_INITIALIZED) {
                audioRecord.startRecording();
                final short[] shortArray = new short[bufferSize / 2];
                isRecording = true;
                while (isRecording) {
                    final int nRead = audioRecord.read(shortArray, 0, shortArray.length);
                    if (state == LISTENING || state == SPEAKING_FINISHED) {
                        final ByteBuffer buffer = ByteBuffer.allocate(2 * nRead);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        final ShortBuffer shortBuffer = buffer.asShortBuffer();
                        shortBuffer.put(shortArray, 0, nRead);
                        final byte[] bytes = buffer.array();
                        final StreamingTalkRequest req = StreamingTalkRequest.newBuilder()
                                .setAudioContent(ByteString.copyFrom(bytes))
                                .build();
                        botStream.onNext(req);
                    }
                }
                audioRecord.stop();
                audioRecord.release();
            }

        };
        new Thread(recorder).start();
    }
    private void stopAudioIn() {
        isRecording = false;
    }

    private void startGrpc() throws Exception {
        final SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, null, null);
        channel = OkHttpChannelBuilder.forAddress(HOSTNAME, PORT)
                .maxInboundMetadataSize(16 * 1024 * 1024)
                .overrideAuthority(HOSTNAME)
                .useTransportSecurity()
                .sslSocketFactory(sslContext.getSocketFactory())
                .build();
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

        final StreamObserver<StreamingTalkRequest> stream = stub.streamingTalk(this);
        final StreamingTalkRequest.StreamingTalkConfig talkConfig = StreamingTalkRequest.StreamingTalkConfig.newBuilder()
                .setStreamingRecognitionConfig(
                        StreamingRecognitionConfig.newBuilder()
                                .setConfig(RecognitionConfig.newBuilder()
                                        .setEncoding(LINEAR16)
                                        .setSampleRateHertz(16_000)
                                        .build())
                                .build()
                )
                .setTalkId(UUID.randomUUID().toString())
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
        stream.onNext(StreamingTalkRequest.newBuilder().setStreamingTalkConfig(talkConfig).build());
        botStream = stream;
    }

    private void stopGrpc() {
        final ManagedChannel backup = channel;
        channel = null;
        if (null != backup) {
            backup.shutdown();
        }
    }

    private void startAudioOut() {
        final int sampingRate = 16_000;
        int bufferSize = AudioRecord.getMinBufferSize(sampingRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
        if (bufferSize == ERROR || bufferSize == ERROR_BAD_VALUE) {
            bufferSize = 2 * sampingRate;
        }
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
        audioTrack.play();
    }

    void stopAudioOut() {
        final AudioTrack backup = audioTrack;
        audioTrack = null;
        if (backup != null) {
            backup.stop();
            backup.release();
        }
    }

    @Override
    public void onNext(StreamingTalkResponse res) {
        if (state == NOTHING) {
            final ByteString audioContent = res.getStreamingSynthesizeSpeechResponse()
                    .getAudioContent();
            if (audioContent != null && !audioContent.isEmpty()) {
                final byte[] bytes = audioContent.toByteArray();
                audioTrack.write(bytes, 0, bytes.length);
            }
        }
        state = res.getTalkEvent();
    }

    @Override
    public void onError(final Throwable t) {
        Log.e(TAG, "Unexpected exception", t);
        stopGrpc();
    }

    @Override
    public void onCompleted() {
        Log.d(TAG, this.toString() + " completed");
    }
}
