package com.example.rspeech;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.StringReader;
import java.util.Properties;

public class MainActivity extends AppCompatActivity implements AudioNetworkManager.Listener {

    private static final String PREFS_NAME = "RSpeechPrefs";
    private static final String KEY_CONFIG_1 = "config_text_1";
    private static final String KEY_CONFIG_2 = "config_text_2";
    private static final String KEY_ACTIVE_TAB = "active_tab";
    private static final int PERMISSION_REQ_RECORD_AUDIO = 101;

    private static final String DEFAULT_CONFIG_1 =
            "server.ip=192.168.0.3\n" +
            "server.port=14144\n" +
            "user=pepe\n" +
            "pass=23rc2rc\n" +
            "audio.rate=48000\n" +
            "audio.max_latency_ms=200\n";

    private static final String DEFAULT_CONFIG_2 =
            "server.ip=192.168.0.2\n" +
            "server.port=14144\n" +
            "user=pepe\n" +
            "pass=23rc2rc\n" +
            "audio.rate=48000\n" +
            "audio.max_latency_ms=200\n";

    private View layoutConfig;
    private View layoutTalk;
    private com.google.android.material.tabs.TabLayout tabLayoutConfig;
    private EditText editConfig1;
    private EditText editConfig2;
    private TextView tvStatus;
    private TextView tvMicStatus;
    private SwitchCompat switchMic;
    private Button btnPushToTalk;

    private AudioNetworkManager audioManager;
    private boolean isPushPressed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioManager = new AudioNetworkManager(this, this);

        initViews();
        loadSavedConfig();
        checkPermissions();
    }

    private void initViews() {
        layoutConfig = findViewById(R.id.layout_config);
        layoutTalk = findViewById(R.id.layout_talk);
        tabLayoutConfig = findViewById(R.id.tab_layout_config);
        editConfig1 = findViewById(R.id.edit_config_1);
        editConfig2 = findViewById(R.id.edit_config_2);
        tvStatus = findViewById(R.id.tv_status);
        tvMicStatus = findViewById(R.id.tv_mic_status);
        switchMic = findViewById(R.id.switch_mic);
        btnPushToTalk = findViewById(R.id.btn_push_to_talk);

        Button btnSaveConfig = findViewById(R.id.btn_save_config);
        Button btnGotoConfig = findViewById(R.id.btn_goto_config);

        btnSaveConfig.setOnClickListener(v -> applyConfigAndProceed());
        btnGotoConfig.setOnClickListener(v -> showConfigScreen());

        tabLayoutConfig.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    editConfig1.setVisibility(View.VISIBLE);
                    editConfig2.setVisibility(View.GONE);
                } else {
                    editConfig1.setVisibility(View.GONE);
                    editConfig2.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });

        // 1. INTERRUPTOR (Switch)
        switchMic.setOnCheckedChangeListener((buttonView, isChecked) -> updateMicState());

        // 2. PULSADOR (Push-to-Talk)
        setupPushToTalk();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPushToTalk() {
        btnPushToTalk.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isPushPressed = true;
                    btnPushToTalk.setBackgroundColor(Color.parseColor("#388E3C"));
                    updateMicState();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isPushPressed = false;
                    btnPushToTalk.setBackgroundColor(Color.parseColor("#FF3700B3"));
                    updateMicState();
                    return true;
            }
            return false;
        });
    }

    private void updateMicState() {
        boolean shouldBeActive = switchMic.isChecked() || isPushPressed;
        audioManager.setMicActive(shouldBeActive);
    }

    private void loadSavedConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved1 = prefs.getString(KEY_CONFIG_1, DEFAULT_CONFIG_1);
        String saved2 = prefs.getString(KEY_CONFIG_2, DEFAULT_CONFIG_2);
        int activeTab = prefs.getInt(KEY_ACTIVE_TAB, 0);

        // Actualizar rate a 48000 si venía con 16000 y latencia a 200 si venía con 100
        saved1 = saved1.replace("audio.rate=16000", "audio.rate=48000")
                .replace("audio.max_latency_ms=100", "audio.max_latency_ms=200");
        saved2 = saved2.replace("audio.rate=16000", "audio.rate=48000")
                .replace("audio.max_latency_ms=100", "audio.max_latency_ms=200");

        editConfig1.setText(saved1);
        editConfig2.setText(saved2);

        if (activeTab >= 0 && activeTab < tabLayoutConfig.getTabCount()) {
            com.google.android.material.tabs.TabLayout.Tab tab = tabLayoutConfig.getTabAt(activeTab);
            if (tab != null) {
                tab.select();
            }
        }
    }

    private void applyConfigAndProceed() {
        int selectedTab = tabLayoutConfig.getSelectedTabPosition();
        String config1 = editConfig1.getText().toString();
        String config2 = editConfig2.getText().toString();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_CONFIG_1, config1)
                .putString(KEY_CONFIG_2, config2)
                .putInt(KEY_ACTIVE_TAB, selectedTab)
                .apply();

        String activeConfigText = (selectedTab == 1) ? config2 : config1;
        String defaultIp = (selectedTab == 1) ? "192.168.0.2" : "192.168.0.3";

        Properties props = new Properties();
        try {
            props.load(new StringReader(activeConfigText));
        } catch (Exception e) {
            Toast.makeText(this, "Error leyendo configuración", Toast.LENGTH_SHORT).show();
            return;
        }

        String ip = props.getProperty("server.ip", defaultIp).trim();
        int port = 14144;
        try {
            port = Integer.parseInt(props.getProperty("server.port", "14144").trim());
        } catch (NumberFormatException ignored) {}

        String user = props.getProperty("user", "pepe").trim();
        String pass = props.getProperty("pass", "23rc2rc").trim();
        int sampleRate = 48000;
        try {
            sampleRate = Integer.parseInt(props.getProperty("audio.rate", "48000").trim());
        } catch (NumberFormatException ignored) {}

        long maxLatencyMs = 100;
        try {
            maxLatencyMs = Long.parseLong(props.getProperty("audio.max_latency_ms", "100").trim());
        } catch (NumberFormatException ignored) {}

        audioManager.updateConfig(ip, port, user, pass, sampleRate, maxLatencyMs);

        layoutConfig.setVisibility(View.GONE);
        layoutTalk.setVisibility(View.VISIBLE);

        audioManager.start();
    }

    private void showConfigScreen() {
        audioManager.stop();
        switchMic.setChecked(false);
        isPushPressed = false;

        layoutTalk.setVisibility(View.GONE);
        layoutConfig.setVisibility(View.VISIBLE);
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQ_RECORD_AUDIO
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso de micrófono concedido", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Se requiere permiso de micrófono para transmitir audio", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onConnectionStatus(boolean connected, String message) {
        tvStatus.setText("Estado: " + message);
        tvStatus.setTextColor(connected ? Color.parseColor("#4CAF50") : Color.parseColor("#FFA726"));
    }

    @Override
    public void onMicStateChanged(boolean active) {
        if (active) {
            tvMicStatus.setText("🎤 MIC: TRANSMITIENDO");
            tvMicStatus.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            tvMicStatus.setText("🎤 MIC: APAGADO");
            tvMicStatus.setTextColor(Color.parseColor("#F44336"));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioManager != null) {
            audioManager.stop();
        }
    }
}
