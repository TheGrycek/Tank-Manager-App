package com.example.myapplication;

import android.bluetooth.BluetoothDevice;
import android.annotation.SuppressLint;

public class BTLEDevice {
    private final BluetoothDevice bluetoothDevice;
    private int rssi;

    public BTLEDevice(BluetoothDevice bluetoothDevice) {
        this.bluetoothDevice = bluetoothDevice;
    }

    public String getAddress() {
        return bluetoothDevice.getAddress();
    }

    /**
     * Retrieves the name of the Bluetooth device.
     * NOTE: The BLUETOOTH_CONNECT permission must be granted before calling this method
     * on Android 12 (API 31) and higher. This check should be performed in the
     * Activity or Fragment that uses this class.
     */
    @SuppressLint("MissingPermission") // We assume permission is checked by the caller (e.g., the Activity)
    public String getName() {
        // The permission check was removed from here because the calling Activity
        // is responsible for ensuring permissions are granted before this is called.
        // This simplifies the model class and removes its dependency on a Context.
        return bluetoothDevice.getName();
    }

    public void setRSSI(int rssi) {
        this.rssi = rssi;
    }

    public int getRSSI() {
        return rssi;
    }
}
