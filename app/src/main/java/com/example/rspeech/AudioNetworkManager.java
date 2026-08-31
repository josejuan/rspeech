package com.example.rspeech;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.PlaybackParams;
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

    private static final int MAX_AUDIO_QUEUE_SIZE = 20; // ~200ms de tampón elástico a 10ms por paquete

    // --- Control adaptativo de velocidad de reproducción (jitter buffer feedback) ---
    private static final float MIN_SPEED = 0.97f;
    private static final float MAX_SPEED = 1.03f;
    private static final float SPEED_DEADBAND = 0.001f;
    private static final float SPEED_STEP_PER_PACKET = 0.006f; // ~0.6% de velocidad por paquete de desvío
    private static final int FRAME_BYTES = 2;                  // 16-bit mono
    private static final int AUDIO_PACKET_BYTES = 960;         // 10ms @ 48kHz mono 16-bit
    private static final int TARGET_LATENCY_MS = 40;           // tampón objetivo (~4 paquetes)
    private static final int PREROLL_PACKETS = 3;              // paquetes a acumular antes de reanudar
    private static final int PREROLL_BELOW_BYTES = AUDIO_PACKET_BYTES * 2; // umbral de underrun agudo
    private static final long SPEED_UPDATE_INTERVAL_MS = 80;

    public interface Listener {
        void onConnectionStatus(boolean connected, String message);
        void onMicStateChanged(boolean active);
    }

    private static class AudioFrame {
        final byte[] pcm;
        final long serverTs;
        AudioFrame(byte[] pcm, long serverTs) {
            this.pcm = pcm;
            this.serverTs = serverTs;
        }
    }

    private final Context context;
    private Listener listener;
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

    private final BlockingQueue<AudioFrame> playbackQueue = new ArrayBlockingQueue<>(MAX_AUDIO_QUEUE_SIZE);

    private volatile long lastPacketReceivedTime = 0;
    private int sendSeq = 0;
    private int lastRecvSeq = -1;

    // Estado del control adaptativo de reproducción
    private volatile boolean priming = false;
    private long writtenBytes = 0;
    private long underruns = 0;
    private long lastSpeedUpdateMs = 0;
    private float currentSpeed = 1.0f;
    private float appliedSpeed = 1.0f;
    private boolean useTimeStretch = true;

    // Sincronización de reloj con el servidor
    private volatile long clockOffsetMs = 0;
    private volatile boolean isClockSynced = false;

    // Métricas
    private long totalAudioPacketsReceived = 0;
    private long droppedPacketsLatency = 0;
    private long droppedSeq = 0;
    private long droppedQueueOverflow = 0;
    private long lastMetricLogTime = 0;
    private long lastDebugMetricsTime = 0;

    // Buffers pre-asignados para evitar GC Churn
    private final byte[] sendAudioBuffer = new byte[2048];
    private final byte[] pingBuffer = new byte[9];

    public AudioNetworkManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initWakeAndWifiLocks();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
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
            mainHandler.post(() -> { if (listener != null) listener.onMicStateChanged(active); });
            if (active) {
                startRecording();
            } else {
                stopRecording();
            }
        }
    }

    private void notifyStatus(boolean connected, String msg) {
        mainHandler.post(() -> { if (listener != null) listener.onConnectionStatus(connected, msg); });
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

            logDebugMetrics();

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
                    droppedSeq++;
                    return;
                }
            }
            lastRecvSeq = seq;

            // 2. Control de latencia: NO descartamos por reloj (el offset NTP es poco fiable y
            //    descartaba ~15% del flujo). El colchón del buffer limita la antigüedad de forma
            //    natural (evicción del más antiguo cuando se llena). Solo lo medimos por si acaso.
            long localNow = System.currentTimeMillis();
            long currentServerTimeEstimate = localNow + clockOffsetMs;
            long packetLatencyMs = currentServerTimeEstimate - serverTimestamp;

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
                droppedQueueOverflow++;
            }
            playbackQueue.offer(new AudioFrame(pcm, serverTimestamp));
        }
    }

    private void playbackLoop() {
        initAudioTrack();
        if (audioTrack == null) {
            Log.e(TAG, "AudioTrack no inicializado, se cancela la reproducción");
            return;
        }

        int nominalTarget = sampleRate * FRAME_BYTES * TARGET_LATENCY_MS / 1000;
        int trackBufBytes = audioTrack.getBufferSizeInFrames() * FRAME_BYTES;
        int targetBytes = Math.max(1920, Math.min(nominalTarget, trackBufBytes - 1920));

        writtenBytes = 0;
        long playedBytes;
        long available = 0;

        while (isRunning.get()) {
            try {
                if (audioTrack == null) break;

                // Pre-roll: tras un underrun agudo, acumular margen antes de reanudar
                if (priming) {
                    audioTrack.pause();
                    if (Math.abs(currentSpeed - 1.0f) > 1e-6f) {
                        currentSpeed = 1.0f;
                        applyPlaybackSpeed(1.0f);
                    }
                    int queued = playbackQueue.size();
                    if (queued >= PREROLL_PACKETS) {
                        priming = false;
                        audioTrack.play();
                        Log.i(TAG, "[UNDERRUN] Reanudado con margen (" + queued + " pkts)");
                    } else {
                        Thread.sleep(20);
                        continue;
                    }
                }

                AudioFrame frame = playbackQueue.poll(25, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    audioTrack.write(frame.pcm, 0, frame.pcm.length);
                    writtenBytes += frame.pcm.length;
                    recordPlayedChunk(writtenBytes, frame.serverTs);
                }

                playedBytes = (long) audioTrack.getPlaybackHeadPosition() * FRAME_BYTES;
                available = Math.max(0, writtenBytes - playedBytes);

                // Pre-roll SOLO ante hambruna real: sin datos que escribir y sin colchón restante.
                // Así el ajuste de velocidad mantiene el tampón estable y el primado no corta.
                if (frame == null && available < PREROLL_BELOW_BYTES && !priming) {
                    priming = true;
                    logUnderrun(available);
                }

                if (!priming) {
                    updatePlaybackSpeed(targetBytes, available);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error en reproducción de audio", e);
            }
        }

        stopAudioTrack();
    }

    /**
     * Mantiene el tampón elástico alrededor de targetBytes ajustando la velocidad de
     * reproducción. Si hay demasiados datos acumulados (vamos retrasados) acelera para
     * volver "al reloj"; si faltan, decelera para dejar que se acumule el colchón.
     */
    private void updatePlaybackSpeed(int targetBytes, long available) {
        long now = System.currentTimeMillis();
        if (now - lastSpeedUpdateMs < SPEED_UPDATE_INTERVAL_MS) return;

        long err = targetBytes - available; // >0 => faltan (consumir lento); <0 => sobra (consumir rápido)
        int errPackets = (int) clamp((double) err / AUDIO_PACKET_BYTES, -3, 3);
        float desired = 1.0f - errPackets * SPEED_STEP_PER_PACKET;

        if (Math.abs(desired - 1.0f) < SPEED_DEADBAND) desired = 1.0f;
        desired = (float) clamp(desired, MIN_SPEED, MAX_SPEED);

        float newSpeed = currentSpeed + 0.3f * (desired - currentSpeed);
        if (Math.abs(newSpeed - currentSpeed) > 0.0004f) {
            currentSpeed = newSpeed;
            applyPlaybackSpeed(newSpeed);
            lastSpeedUpdateMs = now;
            Log.d(TAG, "[SPEED] " + String.format(java.util.Locale.US, "%.4f", newSpeed)
                    + " available=" + available + "B target=" + targetBytes + "B");
        }
    }

    private void applyPlaybackSpeed(float speed) {
        if (audioTrack == null) return;
        if (Math.abs(speed - appliedSpeed) < 1e-4f) return;
        // El time-stretch (PlaybackParams) no funciona en PERFORMANCE_MODE_LOW_LATENCY:
        // degradamos a setPlaybackRate (cambia el tono levemente, pero funciona siempre).
        if (useTimeStretch) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PlaybackParams params = new PlaybackParams();
                    params.setSpeed(speed);
                    audioTrack.setPlaybackParams(params);
                    appliedSpeed = speed;
                    return;
                }
            } catch (IllegalArgumentException e) {
                useTimeStretch = false;
                Log.w(TAG, "Time-stretch no soportado con este AudioTrack; degradando a setPlaybackRate");
            } catch (UnsupportedOperationException e) {
                useTimeStretch = false;
                Log.w(TAG, "Time-stretch no soportado con este AudioTrack; degradando a setPlaybackRate");
            }
        }
        try {
            audioTrack.setPlaybackRate(Math.round(sampleRate * speed));
            appliedSpeed = speed;
        } catch (Exception e) {
            Log.e(TAG, "No se pudo ajustar la velocidad de reproducción: " + e.getMessage());
        }
    }

    // Registro de fragmentos reproducidos (por su byte final y timestamp de servidor)
    // para medir la latencia REAL de extremo a extremo en el playhead.
    private static final int PLAYED_RING = 512;
    private final long[] playedEndBytes = new long[PLAYED_RING];
    private final long[] playedServerTs = new long[PLAYED_RING];
    private int playedRingIdx = 0;

    private void recordPlayedChunk(long endByte, long serverTs) {
        playedEndBytes[playedRingIdx] = endByte;
        playedServerTs[playedRingIdx] = serverTs;
        playedRingIdx = (playedRingIdx + 1) % PLAYED_RING;
    }

    private long currentPlayLatencyMs(long playHeadBytes, long offsetMs) {
        long best = -1;
        for (int k = 0; k < PLAYED_RING; k++) {
            int idx = (playedRingIdx - 1 - k + 2 * PLAYED_RING) % PLAYED_RING;
            if (playedEndBytes[idx] > playHeadBytes) {
                best = playedServerTs[idx];
                break;
            }
        }
        if (best < 0) return -1;
        return (System.currentTimeMillis() + offsetMs) - best;
    }

    private void logDebugMetrics() {
        long now = System.currentTimeMillis();
        if (now - lastDebugMetricsTime < 5000) return;
        lastDebugMetricsTime = now;

        int queueDepth = playbackQueue.size();
        String bufInfo = "-";
        try {
            if (audioTrack != null && writtenBytes > 0) {
                long played = (long) audioTrack.getPlaybackHeadPosition() * FRAME_BYTES;
                long available = Math.max(0, writtenBytes - played);
                int nominalTarget = sampleRate * FRAME_BYTES * TARGET_LATENCY_MS / 1000;
                bufInfo = available + "B (target " + nominalTarget + "B, dev " + (available - nominalTarget) + "B)";
            }
        } catch (Exception ignored) {}

        Log.i(TAG, "[METRICS] recv=" + totalAudioPacketsReceived
                + " latDrop=" + droppedPacketsLatency
                + " seqDrop=" + droppedSeq
                + " queueDrop=" + droppedQueueOverflow
                + " underrun=" + underruns
                + " queue=" + queueDepth + "/" + MAX_AUDIO_QUEUE_SIZE
                + " speed=" + String.format(java.util.Locale.US, "%.4f", currentSpeed)
                + " sync=" + (isClockSynced ? clockOffsetMs + "ms" : "no")
                + " buf=" + bufInfo);

        // Latencia real en el playhead (incluye red + cola + buffer AudioTrack)
        if (audioTrack != null && writtenBytes > 0) {
            long headBytes = (long) audioTrack.getPlaybackHeadPosition() * FRAME_BYTES;
            long playLat = currentPlayLatencyMs(headBytes, clockOffsetMs);
            if (playLat >= 0) {
                Log.i(TAG, "[E2E-LAT] " + playLat + "ms (head=" + headBytes + "B)");
            }
        }
    }

    private void logUnderrun(long available) {
        underruns++;
        Log.w(TAG, "[UNDERRUN] #" + underruns + " disponible=" + available + "B (cebando)");
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
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
            int bufferSize = 1920; // forzar buffer pequeño (20ms) para bajar el suelo de latencia
            Log.i(TAG, "[AUDIOTRACK] minBufSize=" + minBufSize + "B forzando bufferSize=" + bufferSize + "B rate=" + sampleRate);

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
