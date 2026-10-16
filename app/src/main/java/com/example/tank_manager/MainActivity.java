package com.example.tank_manager;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements TankService.Listener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS_CODE = 101;

    private static final int COLOR_ON = Color.rgb(0, 150, 0);
    private static final int COLOR_OFF = Color.RED;
    private static final int COLOR_UNKNOWN = Color.GRAY;
    private static final int COLOR_PENDING = Color.parseColor("#E87823");

    // The connection lives in TankService; null until the service is bound
    private TankService service;
    // Device picked from the list, waiting for the service and the permissions
    private boolean bound = false;
    private String pendingAddress;
    private boolean permissionRequestInFlight = false;
    private boolean notificationPermissionAsked = false;

    // --- What the screen shows of the current connection ---
    private boolean connected = false;
    // All notifications are enabled, so the GATT client is free for writes
    private boolean ready = false;
    // Valve state confirmed by the controller, null until the first value arrives
    private Boolean confirmedValve = null;
    // Valve state requested by the user and not yet acknowledged
    private Boolean pendingValve = null;
    // True while the valve switch is being set by the app, not by the user
    private boolean showingValve = false;
    private boolean settingsKnown = false;

    // --- UI Elements ---
    private TextView connectionText;
    private View connectionPill;
    private View connectionLight;
    private ObjectAnimator connectionPulse;
    private Switch valveSwitch;
    // Actual valve state (closed / opening / open / closing), pulsing while the valve moves
    private TextView valveStateTextView;
    // Text colour of the Value style, for values that are coloured while shown
    private ColorStateList valueDefaultColor;
    private ObjectAnimator valveStatePulse;
    private View heaterLight;
    private Boolean heaterOn = null;
    private TextView grayWaterLevelTextView;
    private TextView grayWaterTempTextView;
    private TextView cleanWaterLevelTextView;
    private TextView pitchTextView;
    private TextView rollTextView;
    private TextView headingTextView;
    private TiltView pitchView;
    private TiltView rollView;
    private TiltView headingView;

    // --- Settings tab ---
    private View statusGroup;
    private View settingsLayout;
    private Switch buzzerSwitch;
    private Switch autoHeatingSwitch;
    private Switch fillModeSwitch;
    private SeekBar grayThresholdSeek;
    private SeekBar heatingThresholdSeek;
    private TextView grayThresholdLabel;
    private TextView heatingThresholdLabel;
    // True while the controls are being set from a value received from the controller
    private boolean applyingSettings = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Utils.toast(getApplicationContext(), getString(R.string.ble_not_supported));
            finish();
            return;
        }

        connectionText = findViewById(R.id.connection_txt);
        connectionPill = findViewById(R.id.connection_pill);
        connectionLight = findViewById(R.id.connection_light);
        connectionPulse = ObjectAnimator.ofFloat(connectionLight, View.ALPHA, 1f, 0.2f);
        connectionPulse.setDuration(600);
        connectionPulse.setRepeatMode(ValueAnimator.REVERSE);
        connectionPulse.setRepeatCount(ValueAnimator.INFINITE);
        valveSwitch = findViewById(R.id.open_valve_switch);
        valveStateTextView = findViewById(R.id.valve_state_txt);
        valueDefaultColor = valveStateTextView.getTextColors();
        valveStatePulse = ObjectAnimator.ofFloat(valveStateTextView, View.ALPHA, 1f, 0.3f);
        valveStatePulse.setDuration(600);
        valveStatePulse.setRepeatMode(ValueAnimator.REVERSE);
        valveStatePulse.setRepeatCount(ValueAnimator.INFINITE);
        heaterLight = findViewById(R.id.heate_on_light);
        grayWaterLevelTextView = findViewById(R.id.gray_water_lvl_txt);
        grayWaterTempTextView = findViewById(R.id.gray_water_lvl_temp);
        cleanWaterLevelTextView = findViewById(R.id.clean_water_lvl_txt);
        pitchTextView = findViewById(R.id.pitch_num);
        rollTextView = findViewById(R.id.roll_num);
        headingTextView = findViewById(R.id.yaw_num);
        pitchView = findViewById(R.id.pitch_view);
        rollView = findViewById(R.id.roll_view);
        headingView = findViewById(R.id.heading_view);

        // A listener instead of onClick: the switch can also be changed by dragging its thumb
        valveSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!showingValve) {
                requestValve(checked);
            }
        });

        setupSettingsTab();
        showDisconnected(R.string.disconnected, COLOR_OFF);

        bound = bindService(new Intent(this, TankService.class), serviceConnection, BIND_AUTO_CREATE);
        takeSelectedDevice(getIntent());
    }

    private void setupSettingsTab() {
        statusGroup = findViewById(R.id.status_group);
        settingsLayout = findViewById(R.id.settings_layout);
        buzzerSwitch = findViewById(R.id.buzzer_switch);
        autoHeatingSwitch = findViewById(R.id.auto_heating_switch);
        fillModeSwitch = findViewById(R.id.fill_mode_switch);
        grayThresholdSeek = findViewById(R.id.gray_threshold_seek);
        heatingThresholdSeek = findViewById(R.id.heating_threshold_seek);
        grayThresholdLabel = findViewById(R.id.gray_threshold_label);
        heatingThresholdLabel = findViewById(R.id.heating_threshold_label);

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                boolean settings = tab.getPosition() == 1;
                statusGroup.setVisibility(settings ? View.GONE : View.VISIBLE);
                settingsLayout.setVisibility(settings ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
        });

        buzzerSwitch.setOnCheckedChangeListener((button, checked) -> sendSettings());
        autoHeatingSwitch.setOnCheckedChangeListener((button, checked) -> sendSettings());
        fillModeSwitch.setOnCheckedChangeListener((button, checked) -> sendSettings());
        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSettingsLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Send once, when the finger is lifted
                sendSettings();
            }
        };
        grayThresholdSeek.setOnSeekBarChangeListener(seekListener);
        heatingThresholdSeek.setOnSeekBarChangeListener(seekListener);
        updateSettingsLabels();
    }

    private Protocol.Settings shownSettings() {
        return new Protocol.Settings(
                buzzerSwitch.isChecked(),
                Protocol.Settings.GRAY_THRESHOLD_MIN
                        + grayThresholdSeek.getProgress() * Protocol.Settings.GRAY_THRESHOLD_STEP,
                autoHeatingSwitch.isChecked(),
                Protocol.Settings.HEATING_THRESHOLD_MIN + heatingThresholdSeek.getProgress(),
                fillModeSwitch.isChecked());
    }

    private void updateSettingsLabels() {
        Protocol.Settings settings = shownSettings();
        grayThresholdLabel.setText(getString(R.string.settings_gray_threshold, settings.grayThreshold));
        heatingThresholdLabel.setText(getString(R.string.settings_heating_threshold, settings.heatingThreshold));
    }

    /** Sends all settings; the controller answers with the values it accepted. */
    private void sendSettings() {
        if (applyingSettings) {
            return;
        }
        // A write that cannot be sent is corrected by the next periodic value from the controller
        write(Protocol.SETTINGS_UUID, shownSettings().encode());
    }

    /** Shows the settings reported by the controller without sending them back. */
    private void applySettings(Protocol.Settings settings) {
        applyingSettings = true;
        buzzerSwitch.setChecked(settings.buzzerOn);
        grayThresholdSeek.setProgress((settings.grayThreshold - Protocol.Settings.GRAY_THRESHOLD_MIN)
                / Protocol.Settings.GRAY_THRESHOLD_STEP);
        autoHeatingSwitch.setChecked(settings.autoHeating);
        heatingThresholdSeek.setProgress(settings.heatingThreshold - Protocol.Settings.HEATING_THRESHOLD_MIN);
        fillModeSwitch.setChecked(settings.fillMode);
        updateSettingsLabels();
        applyingSettings = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeSelectedDevice(intent);
    }

    /** A device picked from the list starts a new session; the extra is consumed, so it is used once. */
    private void takeSelectedDevice(Intent intent) {
        String selected = intent.getStringExtra("selected_device");
        if (selected == null) {
            return;
        }
        intent.removeExtra("selected_device");
        pendingAddress = selected;

        List<String> missing = new ArrayList<>();
        if (!hasConnectPermission()) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        // The notification of the connection service; the link works without it too
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionAsked
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionAsked = true;
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) {
            startPendingConnection();
        } else {
            permissionRequestInFlight = true;
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            permissionRequestInFlight = false;
            startPendingConnection();
        }
    }

    private void startPendingConnection() {
        if (pendingAddress == null || service == null || permissionRequestInFlight) {
            return;
        }
        String address = pendingAddress;
        pendingAddress = null;
        if (hasConnectPermission()) {
            service.connect(address);
        } else {
            showDisconnected(R.string.permission_denied, COLOR_OFF);
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TankService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
            // Catch up with a session that was already running
            onStateChanged();
            for (Map.Entry<UUID, byte[]> entry : service.getValues().entrySet()) {
                showValue(entry.getKey(), entry.getValue());
            }
            startPendingConnection();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            showDisconnected(R.string.disconnected, COLOR_OFF);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopValveStatePulse();
        connectionPulse.cancel();
        // The session, if any, goes on in the service
        if (service != null) {
            service.setListener(null);
        }
        if (bound) {
            unbindService(serviceConnection);
        }
    }

    // --- TankService.Listener ---

    @Override
    public void onStateChanged() {
        switch (service.getState()) {
            case CONNECTED:
                ready = service.isReady();
                showConnected();
                break;
            case CONNECTING:
                showDisconnected(R.string.connecting, COLOR_PENDING);
                break;
            case RECONNECTING:
                showDisconnected(R.string.reconnecting, COLOR_PENDING);
                break;
            case BLUETOOTH_OFF:
                showDisconnected(R.string.bluetooth_off, COLOR_OFF);
                break;
            default:
                showDisconnected(R.string.disconnected, COLOR_OFF);
        }
    }

    @Override
    public void onValue(UUID characteristic, byte[] value) {
        showValue(characteristic, value);
    }

    @Override
    public void onValveWriteResult(boolean ok) {
        if (pendingValve == null) {
            return;
        }
        if (ok) {
            confirmedValve = pendingValve;
            pendingValve = null;
            showValve(confirmedValve);
        } else {
            valveCommandFailed();
        }
    }

    private boolean write(UUID characteristic, byte[] value) {
        return service != null && service.write(characteristic, value);
    }

    public void openBTList(View v) {
        Log.d(TAG, "Opening Bluetooth device list...");
        Intent btListIntent = new Intent(this, BTListActivity.class);
        startActivity(btListIntent);
    }

    public void breakBTConnection(View v) {
        Log.d(TAG, "Breaking Bluetooth connection.");
        pendingAddress = null;
        if (service != null) {
            service.disconnect();
        }
    }

    private void requestValve(boolean open) {
        Log.d(TAG, "Sending valve command: " + (open ? 1 : 0));
        if (write(Protocol.VALVE_UUID, new byte[]{(byte) (open ? 1 : 0)})) {
            // Shown as pending until the controller acknowledges the write
            pendingValve = open;
            valveSwitch.setTrackTintList(ColorStateList.valueOf(COLOR_PENDING));
        } else {
            valveCommandFailed();
        }
    }

    private void valveCommandFailed() {
        pendingValve = null;
        showValve(Boolean.TRUE.equals(confirmedValve));
        Utils.toast(this, getString(R.string.command_failed));
    }

    private void showValve(boolean open) {
        showingValve = true;
        valveSwitch.setChecked(open);
        showingValve = false;
        valveSwitch.setTrackTintList(ColorStateList.valueOf(open ? COLOR_ON : COLOR_OFF));
    }

    /** Pitch (front up > 0), roll (right side down > 0) and heading (clockwise from north). */
    private void showAttitude(float pitch, float roll, float heading) {
        pitchTextView.setText(getString(R.string.angle_value, pitch));
        pitchTextView.setTextColor(TiltView.levelColor(pitch));
        pitchView.setAngle(pitch);
        pitchView.setContentDescription(getString(R.string.pitch_description, pitch));

        rollTextView.setText(getString(R.string.angle_value, roll));
        rollTextView.setTextColor(TiltView.levelColor(roll));
        rollView.setAngle(roll);
        rollView.setContentDescription(getString(R.string.roll_description, roll));

        headingTextView.setText(getString(R.string.heading_value, heading, Protocol.compassPoint(heading)));
        headingView.setAngle(heading);
        headingView.setContentDescription(getString(R.string.heading_description, heading));
    }

    private void showValveState(int state) {
        int text;
        int color;
        boolean moving = false;
        switch (state) {
            case Protocol.VALVE_OPEN:
                text = R.string.valve_open;
                color = COLOR_ON;
                break;
            case Protocol.VALVE_CLOSING:
                text = R.string.valve_closing;
                color = COLOR_PENDING;
                moving = true;
                break;
            case Protocol.VALVE_OPENING:
                text = R.string.valve_opening;
                color = COLOR_PENDING;
                moving = true;
                break;
            case Protocol.VALVE_CLOSED:
                text = R.string.valve_closed;
                color = COLOR_OFF;
                break;
            default:
                return;
        }
        valveStateTextView.setText(text);
        valveStateTextView.setTextColor(color);
        if (!moving) {
            stopValveStatePulse();
        } else if (!valveStatePulse.isStarted()) {
            valveStatePulse.start();
        }
    }

    private void stopValveStatePulse() {
        valveStatePulse.cancel();
        valveStateTextView.setAlpha(1f);
    }

    /** Heater light: green on, red off, grey without a value or a connection. */
    private void showHeater(Boolean on) {
        heaterOn = on;
        int color = !connected || on == null ? COLOR_UNKNOWN : on ? COLOR_ON : COLOR_OFF;
        heaterLight.setBackgroundTintList(ColorStateList.valueOf(color));
        heaterLight.setContentDescription(getString(R.string.heater) + ": "
                + getString(on == null ? R.string.no_value : on ? R.string.heater_on : R.string.heater_off));
    }

    /** Controls are usable only when there is a value to show and the GATT client is free. */
    private void refreshControls() {
        valveSwitch.setEnabled(connected && ready && confirmedValve != null);
        showHeater(heaterOn);
        boolean settingsEnabled = connected && ready && settingsKnown;
        buzzerSwitch.setEnabled(settingsEnabled);
        autoHeatingSwitch.setEnabled(settingsEnabled);
        fillModeSwitch.setEnabled(settingsEnabled);
        grayThresholdSeek.setEnabled(settingsEnabled);
        heatingThresholdSeek.setEnabled(settingsEnabled);
    }

    /** Connection pill: border and light in the state colour, a light fill, the light pulses while connecting. */
    private void showConnectionState(int text, int color) {
        connectionText.setText(text);
        GradientDrawable pill = (GradientDrawable) connectionPill.getBackground().mutate();
        pill.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), color);
        pill.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)));
        connectionLight.setBackgroundTintList(ColorStateList.valueOf(color));
        if (color == COLOR_PENDING) {
            if (!connectionPulse.isStarted()) connectionPulse.start();
        } else {
            connectionPulse.cancel();
            connectionLight.setAlpha(1f);
        }
    }

    private void showConnected() {
        connected = true;
        showConnectionState(R.string.connected, COLOR_ON);
        refreshControls();
    }

    /** Resets everything that came from the controller; the values on screen would be stale. */
    private void showDisconnected(int statusText, int statusColor) {
        connected = false;
        ready = false;
        confirmedValve = null;
        pendingValve = null;
        settingsKnown = false;
        showConnectionState(statusText, statusColor);
        showValve(false);
        showHeater(null);
        stopValveStatePulse();
        for (TextView view : new TextView[]{valveStateTextView, pitchTextView, rollTextView}) {
            view.setTextColor(valueDefaultColor);
        }
        for (TiltView view : new TiltView[]{pitchView, rollView, headingView}) {
            view.setAngle(null);
            view.setContentDescription(null);
        }
        for (TextView view : new TextView[]{grayWaterLevelTextView, grayWaterTempTextView,
                cleanWaterLevelTextView, pitchTextView, rollTextView, headingTextView, valveStateTextView}) {
            view.setText(R.string.no_value);
        }
        refreshControls();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /** Shows a value received from the controller; a malformed payload is ignored. */
    private void showValue(UUID charUuid, byte[] value) {
        if (!connected) {
            return;
        }
        Integer number = Protocol.uint8(value);
        if (Protocol.GRAY_LEVEL_UUID.equals(charUuid)) {
            if (number != null) grayWaterLevelTextView.setText(getString(R.string.level_value, number));
        } else if (Protocol.CLEAN_LEVEL_UUID.equals(charUuid)) {
            if (number != null) cleanWaterLevelTextView.setText(getString(R.string.level_value, number));
        } else if (Protocol.GRAY_TEMP_UUID.equals(charUuid)) {
            Float temperature = Protocol.temperature(value);
            grayWaterTempTextView.setText(temperature == null
                    ? getString(R.string.temperature_unknown)
                    : getString(R.string.temperature_value, temperature));
        } else if (Protocol.MPU_UUID.equals(charUuid)) {
            float[] angles = Protocol.orientation(value);
            if (angles != null) {
                showAttitude(angles[0], angles[1], angles[2]);
            }
        } else if (Protocol.VALVE_UUID.equals(charUuid)) {
            // While a command is waiting for its acknowledgement, a periodic value may still be the old one
            if (number != null && pendingValve == null) {
                confirmedValve = number == 1;
                showValve(confirmedValve);
                refreshControls();
            }
        } else if (Protocol.VALVE_STATE_UUID.equals(charUuid)) {
            if (number != null) showValveState(number);
        } else if (Protocol.HEATER_UUID.equals(charUuid)) {
            if (number != null) showHeater(number == 1);
        } else if (Protocol.SETTINGS_UUID.equals(charUuid)) {
            Protocol.Settings settings = Protocol.Settings.decode(value);
            // Do not move a slider the user is holding
            if (settings != null && !grayThresholdSeek.isPressed() && !heatingThresholdSeek.isPressed()) {
                applySettings(settings);
                settingsKnown = true;
                refreshControls();
            }
        }
    }
}
