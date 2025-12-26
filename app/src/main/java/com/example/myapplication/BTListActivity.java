package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.adapter.RecyclerAdapter;
import com.example.myapplication.model.RecyclerModel;

import java.util.ArrayList;
import java.util.List;

public class BTListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private final List<RecyclerModel> recyclerModels = new ArrayList<>();
    private RecyclerAdapter recyclerAdapter;

    private BluetoothLeScanner bleScanner;
    private BluetoothAdapter bluetoothAdapter;
    private final List<BTLEDevice> deviceList = new ArrayList<>();

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private static final String TAG = "BTListActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_btlist);
        setTitle("Bluetooth devices");

        recyclerView = findViewById(R.id.bt_list_view);

        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Utils.toast(this, "Please enable Bluetooth and try again.");
            finish();
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        recyclerAdapter = new RecyclerAdapter(this, recyclerModels);
        recyclerView.setAdapter(recyclerAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        recyclerAdapter.setOnItemClickListener(uuid -> {
            Intent intent = new Intent(BTListActivity.this, MainActivity.class);
            intent.putExtra("selected_device", uuid);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        clearScanResults();

        // **NEW:** First, check if Location Services are enabled before checking permissions.
        if (!checkAndPromptForLocation()) {
            // If location is not enabled, the dialog is shown. Don't proceed to scan yet.
            return;
        }

        // If location is enabled, proceed with permission checks and scanning.
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
        } else {
            startScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
    }

    /**
     * **NEW METHOD**
     * Checks if Location Services are enabled, which is required for BLE scanning on many Android versions.
     * If not enabled, it prompts the user with a dialog to enable it.
     * @return true if location is enabled, false otherwise.
     */
    private boolean checkAndPromptForLocation() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isLocationEnabled = locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

        if (!isLocationEnabled) {
            // Location is not enabled, show a dialog to the user.
            new AlertDialog.Builder(this)
                    .setTitle("Location Services Required")
                    .setMessage("This app requires Location Services to be enabled to scan for Bluetooth devices. Please enable it.")
                    .setPositiveButton("Go to Settings", (dialog, which) -> {
                        // Intent to open the location settings.
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        // User cancelled. Inform them that scanning won't work.
                        Utils.toast(this, "Bluetooth scanning will not work without Location Services.");
                        dialog.dismiss();
                    })
                    .show();
            return false;
        }
        return true;
    }

    private void startScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOETOOTH_SCAN permission not granted. Cannot start scan.");
            return;
        }
        Log.d(TAG, "Permissions are granted. Starting BLE scan...");
        bleScanner.startScan(scanCallback);
    }

    private void stopScan() {
        if (bleScanner != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bleScanner.stopScan(scanCallback);
        }
    }

    private void clearScanResults() {
        deviceList.clear();
        recyclerModels.clear();
        recyclerAdapter.notifyDataSetChanged();
    }

    private boolean hasRequiredPermissions() {
        // For Android 12+, location permission is not strictly needed for the scan,
        // but Location Services must still be ON. For older versions, the permission is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestRequiredPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissions were granted, now we can start the scan.
                // We re-check location here in case the user comes back from the permission dialog.
                if (checkAndPromptForLocation()) {
                    startScan();
                }
            } else {
                Utils.toast(this, "Permissions are required to scan for Bluetooth devices.");
                finish();
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            BluetoothDevice device = result.getDevice();

            if (ActivityCompat.checkSelfPermission(
                    BTListActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) {
                // This permission is needed to get the device name.
                // If not granted, we might still be able to show the device with its address.
            }

            // Filter out devices with no name to keep the list clean.
            // You can comment this out during testing to see all advertising devices.
            if (device == null || device.getName() == null || device.getName().isEmpty()) {
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

            recyclerModels.add(
                    new RecyclerModel(
                            bleDevice.getName(),
                            bleDevice.getAddress()
                    )
            );

            int newPosition = recyclerModels.size() - 1;
            recyclerAdapter.notifyItemInserted(newPosition);
        }
    };

    // Other methods remain the same...
    public void returnMain(View v) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        startActivity(mainIntent);
    }
}
