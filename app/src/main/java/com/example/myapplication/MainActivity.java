package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 101;
    // Standard BLE descriptor for notifications
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private String deviceAddress;

    // --- UI Elements ---
    private TextView connectionText;
    private Switch valveSwitch;
    private Switch heaterSwitch;
    private TextView grayWaterLevelTextView;
    private TextView grayWaterTempTextView;
    private TextView cleanWaterLevelTextView;
    private TextView pitchTextView;
    private TextView rollTextView;
    private TextView yawTextView;

    // Queue for enabling notifications sequentially
    private final Queue<BluetoothGattCharacteristic> notificationQueue = new LinkedList<>();

    // --- Define the UUIDs for your device's services and characteristics ---
    private static final UUID MANAGER_SERVICE_UUID = UUID.fromString(
            "19b10000-e8f2-537e-4f6c-d104768a1214");
    private static final UUID CLEAN_LEVEL_SENSOR_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID GRAY_LEVEL_SENSOR_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");
    private static final UUID GRAY_TEMP_SENSOR_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000003");
    private static final UUID MPU_SENSOR_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000004");
    private static final UUID VALVE_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000005");
    private static final UUID VALVE_LOCKED_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000006");
    private static final UUID HEATER_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000007");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        setTitle("Home");

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Utils.toast(getApplicationContext(), "BLE not supported");
            finish();
            return;
        }

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();
        deviceAddress = getIntent().getStringExtra("selected_device");

        // --- Find all UI elements by their ID ---
        connectionText = findViewById(R.id.connection_txt);
        valveSwitch = findViewById(R.id.open_valve_switch);
        heaterSwitch = findViewById(R.id.heate_on_light); // Added Heater switch
        grayWaterLevelTextView = findViewById(R.id.gray_water_lvl_txt);
        grayWaterTempTextView = findViewById(R.id.gray_water_lvl_temp);
        cleanWaterLevelTextView = findViewById(R.id.clean_water_lvl_txt);
        pitchTextView = findViewById(R.id.pitch_num);
        rollTextView = findViewById(R.id.roll_num);
        yawTextView = findViewById(R.id.yaw_num);

        // --- Lock the heater switch ---
        heaterSwitch.setClickable(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (deviceAddress != null) {
            Log.d(TAG, "Attempting to connect to device: " + deviceAddress);
            updateConnectionState(false, "CONNECTING...");
            connectToDevice();
        } else {
            updateConnectionState(false, getString(R.string.disconnected));
        }
    }

    private void connectToDevice() {
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        if (device == null) {
            Log.w(TAG, "Device not found. Unable to connect.");
            updateConnectionState(false, "Device not found");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted.");
            updateConnectionState(false, "Permission Denied");
            return;
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        breakBTConnection(null);
    }

    public void openBTList(View v) {
        Log.d(TAG, "Opening Bluetooth device list...");
        Intent btListIntent = new Intent(this, BTListActivity.class);
        startActivity(btListIntent);
    }

    @SuppressLint("MissingPermission")
    public void breakBTConnection(View v) {
        if (bluetoothGatt != null) {
            Log.d(TAG, "Breaking Bluetooth connection.");
            if (hasConnectPermission()) {
                bluetoothGatt.disconnect();
            }
        }
        updateConnectionState(false, getString(R.string.disconnected));
        deviceAddress = null;
    }

    public void openValve(View v) {
        // This check is now crucial. The UI should already be disabled, but this is a safeguard.
        if (!valveSwitch.isEnabled()) {
            Log.w(TAG, "Valve switch is locked by the physical button. Cannot send command.");
            // Revert the switch state if user somehow managed to click it while disabled
            valveSwitch.setChecked(!valveSwitch.isChecked());
            return;
        }
        boolean isChecked = valveSwitch.isChecked();
        if (isChecked) {
            valveSwitch.setTrackTintList(ColorStateList.valueOf(Color.rgb(0, 150, 0)));
            Log.d(TAG, "Switch ON: Sending 'open valve' command (1)");
            writeCharacteristic(MANAGER_SERVICE_UUID, VALVE_UUID, new byte[]{1}); // Send 1 for ON
        } else {
            valveSwitch.setTrackTintList(ColorStateList.valueOf(Color.RED));
            Log.d(TAG, "Switch OFF: Sending 'close valve' command (0)");
            writeCharacteristic(MANAGER_SERVICE_UUID, VALVE_UUID, new byte[]{0}); // Send 0 for OFF
        }
    }

    private void updateConnectionState(final boolean isConnected, final String statusMessage) {
        runOnUiThread(() -> {
            connectionText.setText(statusMessage);
            if (isConnected) {
                connectionText.setTextColor(Color.rgb(0, 150, 0));
                valveSwitch.setEnabled(true);
                heaterSwitch.setEnabled(true); // Enable for visual state change
            } else {
                if (statusMessage.equals("CONNECTING...")) {
                    connectionText.setTextColor(Color.parseColor("#E87823"));
                } else {
                    connectionText.setTextColor(Color.RED);
                }
                valveSwitch.setEnabled(false);
                valveSwitch.setChecked(false);
                valveSwitch.setTrackTintList(ColorStateList.valueOf(Color.RED));

                heaterSwitch.setEnabled(false);
                heaterSwitch.setChecked(false);
            }
        });
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Callback for GATT events. This is the heart of BLE interaction.
     */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                updateConnectionState(true, getString(R.string.connected));
                if (hasConnectPermission()) {
                    Log.i(TAG, "Attempting to start service discovery.");
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                updateConnectionState(false, getString(R.string.disconnected));
                if (bluetoothGatt != null) {
                    if (hasConnectPermission()) bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered. Enabling notifications.");
                BluetoothGattService service = gatt.getService(MANAGER_SERVICE_UUID);
                if (service != null) {
                    // *** ADD ALL NOTIFIABLE CHARACTERISTICS TO A QUEUE ***
                    notificationQueue.clear();
                    notificationQueue.add(service.getCharacteristic(GRAY_LEVEL_SENSOR_UUID));
                    notificationQueue.add(service.getCharacteristic(GRAY_TEMP_SENSOR_UUID));
                    notificationQueue.add(service.getCharacteristic(CLEAN_LEVEL_SENSOR_UUID));
                    notificationQueue.add(service.getCharacteristic(MPU_SENSOR_UUID));
                    notificationQueue.add(service.getCharacteristic(VALVE_UUID));
                    notificationQueue.add(service.getCharacteristic(HEATER_UUID));
                    notificationQueue.add(service.getCharacteristic(VALVE_LOCKED_UUID)); // Add the new one
                    // Start the process
                    enableNextNotification(gatt);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notification enabled for: " + descriptor.getCharacteristic().getUuid().toString());
                // Process the next characteristic in the queue
                enableNextNotification(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            UUID charUuid = characteristic.getUuid();
            Log.d(TAG, "onCharacteristicChanged for: " + charUuid);

            if (GRAY_LEVEL_SENSOR_UUID.equals(charUuid)) {
                int level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                runOnUiThread(() -> grayWaterLevelTextView.setText(level + " %"));
            } else if (GRAY_TEMP_SENSOR_UUID.equals(charUuid)) {
                float temp = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_SINT16, 0) / 100.0f;
                runOnUiThread(() -> grayWaterTempTextView.setText(String.format("%.1f °C", temp)));
            } else if (CLEAN_LEVEL_SENSOR_UUID.equals(charUuid)) {
                int level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                runOnUiThread(() -> cleanWaterLevelTextView.setText(level + " %"));
            } else if (MPU_SENSOR_UUID.equals(charUuid)) {
                byte[] data = characteristic.getValue();
                if (data != null && data.length >= 12) {
                    ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                    float pitch = buffer.getFloat();
                    float roll = buffer.getFloat();
                    float yaw = buffer.getFloat();
                    runOnUiThread(() -> {
                        pitchTextView.setText(String.format("%.1f°", pitch));
                        rollTextView.setText(String.format("%.1f°", roll));
                        yawTextView.setText(String.format("%.1f°", yaw));
                    });
                }
            } else if (VALVE_UUID.equals(charUuid)) {
                int state = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                runOnUiThread(() -> valveSwitch.setChecked(state == 1));
            } else if (HEATER_UUID.equals(charUuid)) {
                int state = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                runOnUiThread(() -> heaterSwitch.setChecked(state == 1));
            } else if (VALVE_LOCKED_UUID.equals(charUuid)) {
                // *** THE NEW LOCKING LOGIC ***
                int lockState = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                boolean isLocked = (lockState == 1);
                Log.i(TAG, "Valve lock state changed: " + (isLocked ? "LOCKED" : "UNLOCKED"));
                runOnUiThread(() -> {
                    valveSwitch.setEnabled(!isLocked); // Disable switch if locked
                    if (isLocked) {
                        // Optional: change color to show it's locked
                        valveSwitch.setTrackTintList(ColorStateList.valueOf(Color.GRAY));
                    } else {
                        // Restore normal color based on its state
                        if (valveSwitch.isChecked()) {
                            valveSwitch.setTrackTintList(ColorStateList.valueOf(Color.rgb(0, 150, 0)));
                        } else {
                            valveSwitch.setTrackTintList(ColorStateList.valueOf(Color.RED));
                        }
                    }
                });
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            // This can be left empty as we are now using notifications for all data
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Successfully wrote to characteristic: " + characteristic.getUuid());
            } else {
                Log.e(TAG, "Failed to write characteristic, status: " + status);
            }
        }
    };

    /**
     * Sequentially enables notifications for all characteristics in the queue.
     */
    @SuppressLint("MissingPermission")
    private void enableNextNotification(BluetoothGatt gatt) {
        if (notificationQueue.isEmpty()) {
            Log.i(TAG, "All notifications enabled.");
            return;
        }

        BluetoothGattCharacteristic characteristic = notificationQueue.poll();
        if (characteristic == null) {
            enableNextNotification(gatt); // Skip null characteristics
            return;
        }

        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        } else {
            Log.w(TAG, "Could not get CCCD for " + characteristic.getUuid());
            enableNextNotification(gatt); // Try next one
        }
    }


    /** Helper method to read a characteristic. */
    private void readCharacteristic(BluetoothGatt gatt, UUID serviceUuid, UUID characteristicUuid) {
        if (gatt == null) { Log.e(TAG, "Gatt is null, cannot read characteristic."); return; }
        BluetoothGattService service = gatt.getService(serviceUuid);
        if (service == null) { Log.e(TAG, "Service not found: " + serviceUuid); return; }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);
        if (characteristic == null) { Log.e(TAG, "Characteristic not found: " + characteristicUuid); return; }

        if (hasConnectPermission() && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            if (!gatt.readCharacteristic(characteristic)) {
                Log.e(TAG, "Failed to initiate read for characteristic: " + characteristicUuid);
            }
        }
    }

    /** Helper method to write to a characteristic. */
    private void writeCharacteristic(UUID serviceUuid, UUID characteristicUuid, byte[] value) {
        if (bluetoothGatt == null) { Log.e(TAG, "Gatt is null, cannot write."); return; }
        BluetoothGattService service = bluetoothGatt.getService(serviceUuid);
        if (service == null) { Log.e(TAG, "Service not found for write: " + serviceUuid); return; }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);
        if (characteristic == null) { Log.e(TAG, "Characteristic not found for write: " + characteristicUuid); return; }

        if (hasConnectPermission()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            characteristic.setValue(value);
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); // This constant is safe to use.
            if (!bluetoothGatt.writeCharacteristic(characteristic)) {
                Log.e(TAG, "Failed to initiate write for characteristic: " + characteristicUuid);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Bluetooth permission granted.");
            } else {
                Log.d(TAG, "Bluetooth permission denied.");
            }
        }
    }
}
