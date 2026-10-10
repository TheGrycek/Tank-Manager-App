package com.example.tank_manager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.location.LocationManagerCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.tank_manager.adapter.RecyclerAdapter;
import com.example.tank_manager.model.RecyclerModel;

import java.util.ArrayList;
import java.util.List;

public class BTListActivity extends AppCompatActivity {

    private final List<RecyclerModel> recyclerModels = new ArrayList<>();
    private RecyclerAdapter recyclerAdapter;

    private BluetoothLeScanner bleScanner;
    private final List<BTLEDevice> deviceList = new ArrayList<>();
    private boolean scanning = false;
    // Ask for permissions only once per visit, otherwise a refusal would loop through onResume
    private boolean permissionsRequested = false;

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private static final String TAG = "BTListActivity";
    private static final ParcelUuid SERVICE = new ParcelUuid(Protocol.SERVICE_UUID);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_btlist);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        RecyclerView recyclerView = findViewById(R.id.bt_list_view);
        recyclerAdapter = new RecyclerAdapter(this, recyclerModels);
        recyclerView.setAdapter(recyclerAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Utils.toast(this, getString(R.string.enable_bluetooth));
            finish();
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();

        recyclerAdapter.setOnItemClickListener(uuid -> {
            // Hand the choice to the existing MainActivity instead of stacking a new one on top
            Intent intent = new Intent(BTListActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("selected_device", uuid);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        clearScanResults();

        if (!hasRequiredPermissions()) {
            if (!permissionsRequested) {
                permissionsRequested = true;
                ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS_CODE);
            }
        } else if (checkAndPromptForLocation()) {
            startScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    /**
     * Up to Android 11 a BLE scan returns results only with Location Services enabled.
     * If they are off, prompts the user with a dialog to enable them.
     * @return true if scanning can go ahead.
     */
    private boolean checkAndPromptForLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return true; // the scan is declared neverForLocation
        }
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)) {
            return true;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.location_required_title)
                .setMessage(R.string.location_required_message)
                .setPositiveButton(R.string.go_to_settings, (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton(R.string.cancel, (dialog, which) ->
                        Utils.toast(this, getString(R.string.location_required_denied)))
                .show();
        return false;
    }

    @SuppressLint("MissingPermission") // checked by hasRequiredPermissions(), which knows the SDK level
    private void startScan() {
        if (bleScanner == null || scanning || !hasRequiredPermissions()) {
            return;
        }
        Log.d(TAG, "Starting BLE scan...");
        bleScanner.startScan(scanCallback);
        scanning = true;
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (bleScanner != null && scanning && hasRequiredPermissions()) {
            bleScanner.stopScan(scanCallback);
        }
        scanning = false;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void clearScanResults() {
        deviceList.clear();
        recyclerModels.clear();
        recyclerAdapter.notifyDataSetChanged();
    }

    /** Android 12+ has dedicated Bluetooth permissions; older versions scan under the location permission. */
    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (hasRequiredPermissions()) {
                if (checkAndPromptForLocation()) {
                    startScan();
                }
            } else {
                Utils.toast(this, getString(R.string.scan_permissions_required));
                finish();
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();
            // Only tank controllers: they advertise the manager service
            ScanRecord record = result.getScanRecord();
            List<ParcelUuid> services = record == null ? null : record.getServiceUuids();
            if (device == null || services == null || !services.contains(SERVICE)) {
                return;
            }

            for (BTLEDevice d : deviceList) {
                if (d.getAddress().equals(device.getAddress())) {
                    return; // Device already in the list
                }
            }

            BTLEDevice bleDevice = new BTLEDevice(device);
            bleDevice.setRSSI(result.getRssi());
            deviceList.add(bleDevice);

            String name = bleDevice.getName();
            if (name == null || name.isEmpty()) {
                name = record.getDeviceName();
            }
            recyclerModels.add(
                    new RecyclerModel(
                            name == null || name.isEmpty() ? getString(R.string.app_name) : name,
                            bleDevice.getAddress()
                    )
            );

            int newPosition = recyclerModels.size() - 1;
            recyclerAdapter.notifyItemInserted(newPosition);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed: " + errorCode);
            scanning = false;
        }
    };

    public void returnMain(View v) {
        finish();
    }
}
