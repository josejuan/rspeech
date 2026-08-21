package com.example.rspeech;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioNetworkManager {
    private static final String TAG = "AudioNetworkManager";

    public static final byte TYPE_AUTH = 0x01;
    public static final byte TYPE_AUDIO = 0x02;
    public static final byte TYPE_PING = 0x03;
    public static final byte TYPE_PONG = 0x04;

    private static final int MAX_AUDIO_QUEUE_SIZE = 12; // ~120ms máx de buffer elástico a 10ms por paquete

    public interface Listener {
        void onConnectionStatus(boolean connected, String message);
        void onMicStateChanged(boolean active);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String serverIp = "192.168.0.3";
    private int serverPort = 14144;
    private String user = "pepe";
    private String pass = "23rc2rc";
    private int sampleRate = 48000;
    private long maxAllowedLatencyMs = 200;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isMicActive = new AtomicBoolean(false);
    private final Object socketLock = new Object();

    private Thread receiveThread;
    private Thread syncThread;
    private Thread recordThread;
    private Thread playbackThread;

    private DatagramSocket udpSocket;
    private InetAddress serverAddress;
    private AudioTrack audioTrack;
    private AudioRecord audioRecord;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final BlockingQueue<byte[]> playbackQueue = new ArrayBlockingQueue<>(MAX_AUDIO_QUEUE_SIZE);

    private volatile long lastPacketReceivedTime = 0;
    private int sendSeq = 0;
    private int lastRecvSeq = -1;

    // Sincronización de reloj con el servidor
    private volatile long clockOffsetMs = 0;
    private volatile boolean isClockSynced = false;

    // Métricas
    private long totalAudioPacketsReceived = 0;
    private long droppedPacketsLatency = 0;
    private long lastMetricLogTime = 0;

    // Buffers pre-asignados para evitar GC Churn
    private final byte[] sendAudioBuffer = new byte[2048];
    private final byte[] pingBuffer = new byte[9];

    public AudioNetworkManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initWakeAndWifiLocks();
    }

    private void initWakeAndWifiLocks() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RSpeech:AudioWakeLock");
                wakeLock.setReferenceCounted(false);
            }

            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RSpeech:WifiLock");
                } else {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RSpeech:WifiLock");
                }
                wifiLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error inicializando WakeLock/WifiLock: " + e.getMessage());
        }
    }

    private void acquireLocks() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
                Log.d(TAG, "WakeLock adquirido");
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
                Log.d(TAG, "WifiLock adquirido (Low Latency / High Perf)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adquiriendo locks: " + e.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock liberado");
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                Log.d(TAG, "WifiLock liberado");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error liberando locks: " + e.getMessage());
        }
    }

    public void updateConfig(String ip, int port, String user, String pass, int sampleRate, long maxLatencyMs) {
        this.serverIp = ip;
        this.serverPort = port;
        this.user = user;
        this.pass = pass;
        this.sampleRate = (sampleRate > 0) ? sampleRate : 48000;
        this.maxAllowedLatencyMs = (maxLatencyMs > 0) ? maxLatencyMs : 100;
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        isRunning.set(true);
        lastRecvSeq = -1;
        sendSeq = 0;
        isClockSynced = false;
        totalAudioPacketsReceived = 0;
        droppedPacketsLatency = 0;
        playbackQueue.clear();

        acquireLocks();

        playbackThread = new Thread(this::playbackLoop, "AudioPlaybackWorker");
        playbackThread.start();

        receiveThread = new Thread(this::receiveLoop, "UdpReceiveWorker");
        receiveThread.start();

        syncThread = new Thread(this::timeSyncAndHeartbeatLoop, "UdpSyncWorker");
        syncThread.start();
    }

    public synchronized void stop() {
        isRunning.set(false);
        setMicActive(false);
        closeSocket();

        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        if (syncThread != null) {
            syncThread.interrupt();
            syncThread = null;
        }
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }

        playbackQueue.clear();
        stopAudioTrack();
        releaseLocks();
    }

    public void setMicActive(boolean active) {
        boolean prev = isMicActive.getAndSet(active);
        if (prev != active) {
            mainHandler.post(() -> listener.onMicStateChanged(active));
            if (active) {
                startRecording();
            } else {
                stopRecording();
            }
        }
    }

    private void notifyStatus(boolean connected, String msg) {
        mainHandler.post(() -> listener.onConnectionStatus(connected, msg));
    }

    private void timeSyncAndHeartbeatLoop() {
        while (isRunning.get()) {
            try {
                sendAuth();
                for (int i = 0; i < 3; i++) {
                    sendPing();
                    Thread.sleep(50);
                }
            } catch (Exception ignored) {}

            long elapsed = System.currentTimeMillis() - lastPacketReceivedTime;
            if (lastPacketReceivedTime > 0 && elapsed < 4000) {
                String syncInfo = isClockSynced ? (" (Sync: " + clockOffsetMs + "ms)") : " (Sincronizando reloj...)";
                notifyStatus(true, "Conectado UDP a " + serverIp + ":" + serverPort + syncInfo);
            } else if (lastPacketReceivedTime > 0) {
                notifyStatus(false, "Sin respuesta UDP (reintentando...)");
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void receiveLoop() {
        while (isRunning.get()) {
            try {
                serverAddress = InetAddress.getByName(serverIp);
                synchronized (socketLock) {
                    if (udpSocket == null || udpSocket.isClosed()) {
                        udpSocket = new DatagramSocket();
                        udpSocket.setReceiveBufferSize(256 * 1024);
                        udpSocket.setSoTimeout(3000);
                    }
                }

                notifyStatus(false, "Enviando handshake UDP...");
                sendAuth();

                byte[] recvBuffer = new byte[2048];
                DatagramPacket packet = new DatagramPacket(recvBuffer, recvBuffer.length);

                while (isRunning.get()) {
                    try {
                        udpSocket.receive(packet);
                        lastPacketReceivedTime = System.currentTimeMillis();
                        handleIncomingPacket(packet.getData(), packet.getOffset(), packet.getLength());
                    } catch (IOException e) {
                        // Timeout normal
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error en bucle UDP", e);
                if (isRunning.get()) {
                    notifyStatus(false, "Error UDP: " + e.getMessage());
                }
            } finally {
                closeSocket();
            }

            if (isRunning.get()) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        notifyStatus(false, "Desconectado");
    }

    private void handleIncomingPacket(byte[] data, int offset, int length) {
        if (length < 1) return;

        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        buf.order(ByteOrder.BIG_ENDIAN);

        byte type = buf.get();

        if (type == TYPE_PONG) {
            // Formato PONG: [1B TYPE][8B T_CLIENT_ORIG][8B T_SERVER]
            if (length >= 17) {
                long tClientOrig = buf.getLong();
                long tServer = buf.getLong();
                long tClientRecv = System.currentTimeMillis();

                long rtt = tClientRecv - tClientOrig;
                if (rtt >= 0 && rtt < 300) {
                    long estimatedServerNow = tServer + (rtt / 2);
                    long offsetDiff = estimatedServerNow - tClientRecv;

                    if (!isClockSynced) {
                        clockOffsetMs = offsetDiff;
                        isClockSynced = true;
                    } else {
                        clockOffsetMs = (long) (0.8 * clockOffsetMs + 0.2 * offsetDiff);
                    }
                }
            }
            notifyStatus(true, "Conectado UDP a " + serverIp + ":" + serverPort + " (Offset: " + clockOffsetMs + "ms)");
        } else if (type == TYPE_AUDIO) {
            // Formato AUDIO: [1B TYPE][4B SEQ][8B SERVER_TS][4B LEN][PAYLOAD]
            if (length < 17) return;

            int seq = buf.getInt();
            long serverTimestamp = buf.getLong();
            int payloadLen = buf.getInt();
            if (payloadLen <= 0 || payloadLen > length - 17) return;

            totalAudioPacketsReceived++;

            // 1. Control de secuencia (descarte fuera de orden)
            if (lastRecvSeq != -1) {
                int diff = seq - lastRecvSeq;
                if (diff <= 0 && diff > -100000) {
                    return;
                }
            }
            lastRecvSeq = seq;

            // 2. Control de latencia con tiempo sincronizado
            long localNow = System.currentTimeMillis();
            long currentServerTimeEstimate = localNow + clockOffsetMs;
            long packetLatencyMs = currentServerTimeEstimate - serverTimestamp;

            if (isClockSynced && packetLatencyMs > maxAllowedLatencyMs) {
                droppedPacketsLatency++;
                Log.w(TAG, "[LATENCY DROP] Paquete descartado seq=" + seq
                        + " retraso=" + packetLatencyMs + "ms > limite=" + maxAllowedLatencyMs + "ms"
                        + " (Total descartados: " + droppedPacketsLatency + "/" + totalAudioPacketsReceived + ")");
                return;
            }

            if (localNow - lastMetricLogTime > 3000) {
                lastMetricLogTime = localNow;
                Log.i(TAG, "[LATENCY OK] seq=" + seq + " retraso=" + packetLatencyMs
                        + "ms (Offset=" + clockOffsetMs + "ms, descartes=" + droppedPacketsLatency + ")");
            }

            byte[] pcm = new byte[payloadLen];
            buf.get(pcm);

            // Desacoplar recepción UDP: encolar para hilo de reproducción sin bloquear
            while (playbackQueue.size() >= MAX_AUDIO_QUEUE_SIZE) {
                playbackQueue.poll(); // Descartar el más antiguo si la cola se llena
            }
            playbackQueue.offer(pcm);
        }
    }

    private void playbackLoop() {
        initAudioTrack();

        while (isRunning.get()) {
            try {
                byte[] pcm = playbackQueue.poll(50, TimeUnit.MILLISECONDS);
                if (pcm != null && audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.write(pcm, 0, pcm.length);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error en reproducción de audio", e);
            }
        }

        stopAudioTrack();
    }

    private void sendPing() {
        long tClientNow = System.currentTimeMillis();
        synchronized (pingBuffer) {
            ByteBuffer buf = ByteBuffer.wrap(pingBuffer);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.put(TYPE_PING);
            buf.putLong(tClientNow);
            sendUdpRaw(pingBuffer, pingBuffer.length);
        }
    }

    private void sendAuth() {
        String authString = "user=" + user + "&pass=" + pass;
        byte[] authBytes = authString.getBytes(StandardCharsets.UTF_8);

        byte[] raw = new byte[17 + authBytes.length];
        ByteBuffer buf = ByteBuffer.wrap(raw);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(TYPE_AUTH);
        buf.putInt(0);
        buf.putLong(System.currentTimeMillis());
        buf.putInt(authBytes.length);
        buf.put(authBytes);

        sendUdpRaw(raw, raw.length);
    }

    public void sendAudioChunk(byte[] pcmData, int offset, int size) {
        if (!isRunning.get() || udpSocket == null) return;
        try {
            sendSeq = (sendSeq + 1) & 0x7FFFFFFF;
            long nowMs = System.currentTimeMillis();

            synchronized (sendAudioBuffer) {
                if (17 + size > sendAudioBuffer.length) {
                    return;
                }
                ByteBuffer buf = ByteBuffer.wrap(sendAudioBuffer);
                buf.order(ByteOrder.BIG_ENDIAN);
                buf.put(TYPE_AUDIO);
                buf.putInt(sendSeq);
                buf.putLong(nowMs);
                buf.putInt(size);
                buf.put(pcmData, offset, size);

                sendUdpRaw(sendAudioBuffer, 17 + size);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error enviando chunk UDP: " + e.getMessage());
        }
    }

    private void sendUdpRaw(byte[] raw, int length) {
        synchronized (socketLock) {
            if (udpSocket != null && !udpSocket.isClosed() && serverAddress != null) {
                try {
                    DatagramPacket packet = new DatagramPacket(raw, length, serverAddress, serverPort);
                    udpSocket.send(packet);
                } catch (IOException ignored) {}
            }
        }
    }

    private synchronized void startRecording() {
        if (recordThread != null && recordThread.isAlive()) return;

        recordThread = new Thread(() -> {
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBufferSize, 1920);

            try {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                );

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord no inicializado");
                    return;
                }

                audioRecord.startRecording();
                byte[] buffer = new byte[960]; // 10ms a 48kHz mono 16-bit

                while (isRunning.get() && isMicActive.get()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        sendAudioChunk(buffer, 0, read);
                    }
                }
            } catch (SecurityException se) {
                Log.e(TAG, "Permiso de micrófono denegado: " + se.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Error en captura de audio: " + e.getMessage());
            } finally {
                if (audioRecord != null) {
                    try {
                        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            audioRecord.stop();
                        }
                        audioRecord.release();
                    } catch (Exception ignored) {}
                    audioRecord = null;
                }
            }
        }, "MicRecordThread");

        recordThread.start();
    }

    private synchronized void stopRecording() {
        if (recordThread != null) {
            recordThread.interrupt();
            recordThread = null;
        }
    }

    private void initAudioTrack() {
        try {
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBufSize, 1920);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } else {
                audioTrack = new AudioTrack(
                        audioAttributes,
                        format,
                        bufferSize,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                );
            }

            audioTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "Error inicializando AudioTrack: " + e.getMessage());
        }
    }

    private void stopAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void closeSocket() {
        synchronized (socketLock) {
            if (udpSocket != null) {
                try {
                    udpSocket.close();
                } catch (Exception ignored) {}
                udpSocket = null;
            }
        }
    }
}
