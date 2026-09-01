package com.wifimapper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String SPEED_TEST_URL = "https://speed.hetzner.de/10MB.bin";
    private static final long DOWNLOAD_SIZE_BYTES = 10 * 1024 * 1024; // 10MB

    private WifiManager wifiManager;
    private TextView signalStrengthText, signalPercentageText, speedText, wifiNameText, locationText, resultLogText;
    private ProgressBar signalBar, speedBar;
    private Button scanWifiBtn, testSpeedBtn, saveLocationBtn, addMeasurementBtn;
    
    private String currentLocation = "Not set";
    private List<Measurement> measurements = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        // Initialize UI components
        signalStrengthText = findViewById(R.id.signal_strength_text);
        signalPercentageText = findViewById(R.id.signal_percentage_text);
        speedText = findViewById(R.id.speed_text);
        wifiNameText = findViewById(R.id.wifi_name_text);
        locationText = findViewById(R.id.location_text);
        resultLogText = findViewById(R.id.result_log_text);
        signalBar = findViewById(R.id.signal_bar);
        speedBar = findViewById(R.id.speed_bar);
        scanWifiBtn = findViewById(R.id.scan_wifi_btn);
        testSpeedBtn = findViewById(R.id.test_speed_btn);
        saveLocationBtn = findViewById(R.id.save_location_btn);
        addMeasurementBtn = findViewById(R.id.add_measurement_btn);

        // Set click listeners
        scanWifiBtn.setOnClickListener(v -> checkPermissionsAndScan());
        testSpeedBtn.setOnClickListener(v -> checkPermissionsAndTestSpeed());
        saveLocationBtn.setOnClickListener(v -> showLocationDialog());
        addMeasurementBtn.setOnClickListener(v -> addMeasurement());

        // Initial permissions check
        checkPermissionsAndScan();
    }

    private void checkPermissionsAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE
                },
                PERMISSION_REQUEST_CODE);
        } else {
            scanWifi();
        }
    }

    private void checkPermissionsAndTestSpeed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE
                },
                PERMISSION_REQUEST_CODE);
        } else {
            testSpeed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                scanWifi();
            } else {
                Toast.makeText(this, "Permissions required for WiFi scanning", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void scanWifi() {
        runOnUiThread(() -> {
            scanWifiBtn.setEnabled(false);
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();
        });

        executor.execute(() -> {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                mainHandler.post(() -> {
                    signalStrengthText.setText("Not connected to WiFi");
                    signalPercentageText.setText("-");
                    wifiNameText.setText("-");
                    signalBar.setProgress(0);
                    scanWifiBtn.setEnabled(true);
                });
                return;
            }

            int rssi = wifiInfo.getRssi();
            String ssid = wifiInfo.getSSID();
            int signalLevel = WifiManager.calculateSignalLevel(rssi, 5);
            float percentage = wifiManager.calculateSignalLevel(rssi, 101) * 1f;

            mainHandler.post(() -> {
                wifiNameText.setText("Connected to: " + ssid);
                signalStrengthText.setText(String.format("Signal: %d dBm (Level %d/4)", rssi, signalLevel));
                signalPercentageText.setText(String.format("Strength: %.0f%%", percentage));
                signalBar.setProgress((int) percentage);
                scanWifiBtn.setEnabled(true);

                appendToLog(String.format("\n📍 %s | Signal: %d dBm (%.0f%%)", 
                    currentLocation.equals("Not set") ? "Unknown location" : currentLocation,
                    rssi, percentage));
            });
        });
    }

    private void testSpeed() {
        runOnUiThread(() -> {
            testSpeedBtn.setEnabled(false);
            speedText.setText("Testing speed...");
            speedBar.setProgress(0);
        });

        executor.execute(() -> {
            try {
                URL url = new URL(SPEED_TEST_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "WiFiRoomMapper/1.0");
                connection.connect();

                long startTime = System.currentTimeMillis();
                InputStream is = connection.getInputStream();
                byte[] buffer = new byte[8192];
                long bytesRead = 0;
                int read;

                while ((read = is.read(buffer)) != -1) {
                    bytesRead += read;
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    if (elapsedTime > 0) {
                        double speedMbps = (bytesRead * 8.0) / (elapsedTime / 1000.0) / 1000000.0;
                        double progress = (bytesRead * 100.0) / DOWNLOAD_SIZE_BYTES;
                        
                        final double currentSpeed = speedMbps;
                        final double currentProgress = Math.min(progress, 100.0);
                        
                        mainHandler.post(() -> {
                            speedText.setText(String.format("Speed: %.2f Mbps", currentSpeed));
                            speedBar.setProgress((int) currentProgress);
                        });
                    }
                    
                    if (bytesRead >= DOWNLOAD_SIZE_BYTES) break;
                }

                is.close();
                connection.disconnect();

                long totalElapsed = System.currentTimeMillis() - startTime;
                double finalSpeed = (bytesRead * 8.0) / (totalElapsed / 1000.0) / 1000000.0;
                
                mainHandler.post(() -> {
                    speedText.setText(String.format("Download: %.2f Mbps", finalSpeed));
                    speedBar.setProgress(100);
                    testSpeedBtn.setEnabled(true);
                    appendToLog(String.format("  ⚡ Speed: %.2f Mbps", finalSpeed));
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    speedText.setText("Speed test failed: " + e.getMessage());
                    testSpeedBtn.setEnabled(true);
                    appendToLog("  ❌ Speed test failed");
                });
            }
        });
    }

    private void showLocationDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Set Location");
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("e.g., Corner near window, Center of room...");
        input.setText(currentLocation.equals("Not set") ? "" : currentLocation);
        builder.setView(input);
        builder.setPositiveButton("Save", (dialog, which) -> {
            currentLocation = input.getText().toString().trim();
            if (currentLocation.isEmpty()) {
                currentLocation = "Not set";
            }
            locationText.setText("Location: " + currentLocation);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void addMeasurement() {
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
            Toast.makeText(this, "No WiFi connection to measure", Toast.LENGTH_SHORT).show();
            return;
        }

        int rssi = wifiInfo.getRssi();
        float percentage = wifiManager.calculateSignalLevel(rssi, 101) * 1f;
        
        Measurement m = new Measurement(currentLocation, rssi, percentage, "Pending");
        measurements.add(m);
        appendToLog(String.format("\n✅ Measurement added at: %s (Signal: %.0f%%)", currentLocation, percentage));
        
        // Auto-run speed test for this measurement
        testSpeed();
    }

    private void appendToLog(String message) {
        resultLogText.append(message + "\n");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private static class Measurement {
        String location;
        int rssi;
        float percentage;
        String speed;

        Measurement(String location, int rssi, float percentage, String speed) {
            this.location = location;
            this.rssi = rssi;
            this.percentage = percentage;
            this.speed = speed;
        }
    }
}