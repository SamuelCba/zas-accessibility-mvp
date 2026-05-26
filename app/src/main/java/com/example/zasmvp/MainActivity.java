package com.example.zasmvp;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnSettings).setOnClickListener(v -> 
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            ZasAccessibilityService service = ZasAccessibilityService.getInstance();
            if (service != null) {
                service.startAutoFlow("5.00", "PAGO_AUTO");
            } else {
                Toast.makeText(this, "Activa el servicio de accesibilidad primero", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
