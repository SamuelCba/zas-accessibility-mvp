package com.eveta.zasqr;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1;

    private EditText      etToken, etWorkerId;
    private TextView      tvStatus;
    private HistoryAdapter adapter;

    private final BroadcastReceiver historyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            refreshHistory();
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus   = findViewById(R.id.tvStatus);
        etToken    = findViewById(R.id.etToken);
        etWorkerId = findViewById(R.id.etWorkerId);

        etToken.setText(BackendClient.token(this));
        etWorkerId.setText(BackendClient.workerId(this));

        RecyclerView rv = findViewById(R.id.rvHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(HistoryStorage.getAll(this));
        rv.setAdapter(adapter);

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btnSave).setOnClickListener(v -> saveConfig());

        findViewById(R.id.tvClear).setOnClickListener(v -> {
            HistoryStorage.clear(this);
            refreshHistory();
        });

        requestMediaPermission();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ZasAccessibilityService.ACTION_HISTORY_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(historyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(historyReceiver, filter);
        }
        refreshHistory();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(historyReceiver); } catch (Exception ignored) {}
    }

    private void saveConfig() {
        String token    = etToken.getText().toString().trim();
        String workerId = etWorkerId.getText().toString().trim();

        if (token.isEmpty()) {
            Toast.makeText(this, "El token no puede estar vacío", Toast.LENGTH_SHORT).show();
            return;
        }

        BackendClient.prefs(this).edit()
                .putString(BackendClient.KEY_TOKEN,  token)
                .putString(BackendClient.KEY_WORKER, workerId.isEmpty() ? "bnb" : workerId)
                .apply();

        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show();
        updateStatus();
    }

    private void refreshHistory() {
        adapter.update(HistoryStorage.getAll(this));
    }

    private void updateStatus() {
        ZasAccessibilityService svc = ZasAccessibilityService.getInstance();
        boolean tokenSet = !BackendClient.token(this).isEmpty();
        if (svc == null) {
            tvStatus.setText("Servicio desactivado — abre Accesibilidad y actívalo");
        } else if (!tokenSet) {
            tvStatus.setText("Servicio activo — configura el token para empezar polling");
        } else {
            tvStatus.setText("Activo — polling backend cada 3s");
        }
    }

    private void requestMediaPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_PERM);
        }
    }
}
