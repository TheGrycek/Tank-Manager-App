package com.example.tank_manager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Owns the BLE link to the tank controller, so it survives the screens: while a device is
 * selected the service runs in the foreground (with a notification) and reconnects on its own.
 * The activity binds to it to show the state and to send commands.
 */
public class TankService extends Service {

    public enum State { DISCONNECTED, CONNECTING, RECONNECTING, CONNECTED, BLUETOOTH_OFF }

    /** Callbacks for the bound screen; always called on the main thread. */
    public interface Listener {
        /** The connection state or the readiness for writes has changed. */
        void onStateChanged();

        void onValue(UUID characteristic, byte[] value);

        void onValveWriteResult(boolean ok);
    }

    public class LocalBinder extends Binder {
        TankService getService() {
            return TankService.this;
        }
    }

    private static final String TAG = "TankService";
    private static final String CHANNEL_ID = "connection";
    private static final int NOTIFICATION_ID = 1;
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final String ACTION_DISCONNECT = "com.example.tank_manager.DISCONNECT";

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    // The single GATT client of the app; null whenever we are not connected or connecting
    private volatile BluetoothGatt bluetoothGatt;
    // Queue for enabling notifications sequentially
    private final Queue<BluetoothGattCharacteristic> notificationQueue = new LinkedList<>();

    // --- Everything below is touched only on the main thread ---
    private Listener listener;
    // The selected controller; null means there is no session and the service may stop
    private String deviceAddress;
    // The link to deviceAddress has worked at least once, so a lost link is worth retrying
    private boolean sessionEstablished = false;
    private State state = State.DISCONNECTED;
    // All notifications are enabled, so the GATT client is free for writes
    private boolean ready = false;
    // Last value of every characteristic, for a screen that binds later
    private final Map<UUID, byte[]> values = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothAdapter = getSystemService(BluetoothManager.class).getAdapter();
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(ACTION_DISCONNECT);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Started only by connect(); there is nothing to restore if the process is killed
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        handler.removeCallbacks(reconnect);
        closeGatt();
    }

    // ------------------------------------------------------------------ API for the screen

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public boolean isReady() {
        return ready;
    }

    /** Last received values, to fill a screen that has just bound. */
    public Map<UUID, byte[]> getValues() {
        return new HashMap<>(values);
    }

    /** Starts a session with the given controller, replacing any current one. Call from a visible screen. */
    public void connect(String address) {
        handler.removeCallbacks(reconnect);
        closeGatt();
        values.clear();
        ready = false;
        sessionEstablished = false;
        deviceAddress = address;
        // Keep running after the screen unbinds, and in the background
        startService(new Intent(this, TankService.class));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(State.CONNECTING),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(State.CONNECTING));
            }
        } catch (RuntimeException e) {
            // Without the foreground state the link still works while the app is open
            Log.w(TAG, "Could not enter the foreground", e);
        }
        connectNow();
    }

    /** Ends the session: drops the link, removes the notification and lets the service stop. */
    public void disconnect() {
        handler.removeCallbacks(reconnect);
        closeGatt();
        deviceAddress = null;
        sessionEstablished = false;
        ready = false;
        values.clear();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        setState(State.DISCONNECTED);
    }

    /** Writes to a characteristic of the controller; returns false when the write could not be started. */
    @SuppressLint("MissingPermission")
    public boolean write(UUID characteristicUuid, byte[] value) {
        BluetoothGatt gatt = bluetoothGatt;
        if (gatt == null || !ready || !hasConnectPermission()) { Log.e(TAG, "Not ready, cannot write."); return false; }
        BluetoothGattService service = gatt.getService(Protocol.SERVICE_UUID);
        if (service == null) { Log.e(TAG, "Service not found for write"); return false; }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(characteristicUuid);
        if (characteristic == null) { Log.e(TAG, "Characteristic not found for write: " + characteristicUuid); return false; }

        characteristic.setValue(value);
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!gatt.writeCharacteristic(characteristic)) {
            Log.e(TAG, "Failed to initiate write for characteristic: " + characteristicUuid);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ connection

    private final Runnable reconnect = this::connectNow;

    @SuppressLint("MissingPermission") // checked by hasConnectPermission(), which knows the SDK level
    private void connectNow() {
        if (deviceAddress == null || bluetoothGatt != null) {
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            // The session stays; the receiver connects when Bluetooth is switched on
            setState(State.BLUETOOTH_OFF);
            return;
        }
        if (!hasConnectPermission()) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted.");
            disconnect();
            return;
        }
        Log.d(TAG, "Attempting to connect to device: " + deviceAddress);
        setState(sessionEstablished ? State.RECONNECTING : State.CONNECTING);
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        // The first attempt is direct (fast, times out after about 30 s). A lost link is restored
        // with autoConnect: the system waits for the controller as long as needed at a low duty cycle.
        bluetoothGatt = device.connectGatt(this, sessionEstablished, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (bluetoothGatt == null) {
            disconnect();
        }
    }

    /** Drops the link and releases the GATT client, so the controller can advertise again. */
    @SuppressLint("MissingPermission")
    private void closeGatt() {
        BluetoothGatt gatt = bluetoothGatt;
        bluetoothGatt = null;
        notificationQueue.clear();
        if (gatt != null && hasConnectPermission()) {
            gatt.disconnect();
            gatt.close();
        }
    }

    /** The link is gone without the user asking for it. */
    private void linkLost() {
        if (bluetoothGatt != null || deviceAddress == null) {
            return; // the user has already connected again or disconnected
        }
        ready = false;
        values.clear();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            setState(State.BLUETOOTH_OFF);
        } else if (sessionEstablished) {
            // Out of range or controller restart: keep trying
            setState(State.RECONNECTING);
            handler.removeCallbacks(reconnect);
            handler.postDelayed(reconnect, RECONNECT_DELAY_MS);
        } else {
            // The very first attempt failed; the user picks the device again
            disconnect();
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_DISCONNECT.equals(intent.getAction())) {
                disconnect();
                return;
            }
            int adapterState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (deviceAddress == null) {
                return;
            }
            if (adapterState == BluetoothAdapter.STATE_ON) {
                connectNow();
            } else if (adapterState == BluetoothAdapter.STATE_OFF) {
                // GATT clients do not survive switching Bluetooth off
                handler.removeCallbacks(reconnect);
                closeGatt();
                linkLost();
            }
        }
    };

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void setState(State newState) {
        state = newState;
        if (deviceAddress != null) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(newState));
        }
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    private Notification buildNotification(State shown) {
        int text;
        switch (shown) {
            case CONNECTED: text = R.string.connected; break;
            case CONNECTING: text = R.string.connecting; break;
            case RECONNECTING: text = R.string.reconnecting; break;
            case BLUETOOTH_OFF: text = R.string.bluetooth_off; break;
            default: text = R.string.disconnected;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_DISCONNECT).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.disconnect), stop)
                .build();
    }

    // ------------------------------------------------------------------ GATT

    /**
     * Callback for GATT events (binder thread). State changes are posted to the main thread.
     */
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (gatt != bluetoothGatt) {
                // Callback of a client we already gave up on
                if (hasConnectPermission()) gatt.close();
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Connected to GATT server.");
                handler.post(() -> {
                    if (gatt == bluetoothGatt) setState(State.CONNECTED);
                });
                if (hasConnectPermission()) {
                    Log.i(TAG, "Attempting to start service discovery.");
                    gatt.discoverServices();
                }
            } else {
                // Disconnected, or the connection attempt failed (status != GATT_SUCCESS).
                // Always release the client: a leaked one would block the next connection.
                Log.i(TAG, "Disconnected from GATT server, status: " + status);
                bluetoothGatt = null;
                notificationQueue.clear();
                if (hasConnectPermission()) gatt.close();
                handler.post(TankService.this::linkLost);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered. Enabling notifications.");
                BluetoothGattService service = gatt.getService(Protocol.SERVICE_UUID);
                if (service != null) {
                    handler.post(() -> {
                        if (gatt == bluetoothGatt) sessionEstablished = true;
                    });
                    notificationQueue.clear();
                    notificationQueue.add(service.getCharacteristic(Protocol.GRAY_LEVEL_UUID));
                    notificationQueue.add(service.getCharacteristic(Protocol.GRAY_TEMP_UUID));
                    notificationQueue.add(service.getCharacteristic(Protocol.CLEAN_LEVEL_UUID));
                    notificationQueue.add(service.getCharacteristic(Protocol.MPU_UUID));
                    notificationQueue.add(service.getCharacteristic(Protocol.VALVE_UUID));
                    notificationQueue.add(service.getCharacteristic(Protocol.HEATER_UUID));
                    notificationQueue.add(service.getCharacteristic(Protocol.SETTINGS_UUID));
                    // Start the process
                    enableNextNotification(gatt);
                } else {
                    Log.w(TAG, "The tank manager service is missing on this device.");
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
            } else {
                Log.w(TAG, "Enabling notification failed (" + status + ") for: " + descriptor.getCharacteristic().getUuid());
            }
            // Process the next characteristic in the queue either way, one failure must not stop the rest
            enableNextNotification(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            UUID charUuid = characteristic.getUuid();
            // Copy now: the characteristic object is reused for the next notification
            byte[] raw = characteristic.getValue();
            byte[] value = raw == null ? null : raw.clone();
            handler.post(() -> {
                if (gatt != bluetoothGatt) {
                    return;
                }
                values.put(charUuid, value);
                if (listener != null) {
                    listener.onValue(charUuid, value);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            boolean ok = status == BluetoothGatt.GATT_SUCCESS;
            if (ok) {
                Log.i(TAG, "Successfully wrote to characteristic: " + characteristic.getUuid());
            } else {
                Log.e(TAG, "Failed to write characteristic, status: " + status);
            }
            if (Protocol.VALVE_UUID.equals(characteristic.getUuid())) {
                handler.post(() -> {
                    if (gatt == bluetoothGatt && listener != null) {
                        listener.onValveWriteResult(ok);
                    }
                });
            }
        }
    };

    /**
     * Sequentially enables notifications for all characteristics in the queue.
     */
    @SuppressLint("MissingPermission")
    private void enableNextNotification(BluetoothGatt gatt) {
        BluetoothGattCharacteristic characteristic = null;
        BluetoothGattDescriptor descriptor = null;
        while (descriptor == null && !notificationQueue.isEmpty()) {
            characteristic = notificationQueue.poll();
            descriptor = characteristic == null ? null
                    : characteristic.getDescriptor(Protocol.CCCD_UUID);
            if (descriptor == null) {
                Log.w(TAG, "Skipping a characteristic without CCCD");
            }
        }
        if (descriptor == null) {
            Log.i(TAG, "All notifications enabled.");
            handler.post(() -> {
                if (gatt == bluetoothGatt) {
                    ready = true;
                    if (listener != null) listener.onStateChanged();
                }
            });
            return;
        }

        gatt.setCharacteristicNotification(characteristic, true);
        boolean notify = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        descriptor.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        if (!gatt.writeDescriptor(descriptor)) {
            Log.w(TAG, "Could not write CCCD for " + characteristic.getUuid());
            enableNextNotification(gatt); // Try next one
        }
    }
}
