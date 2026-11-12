package ph.trailwatch.hikerphone;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

public final class LocationTrackingService extends Service {
    public interface Listener {
        void onSample(GpsSample sample, long recordCount);
        void onStatus(String message, boolean active);
        void onSafetyEvent(String eventType, String message, boolean urgent, HikerFlags flags);
        void onInactivityPrompt(long secondsRemaining);
    }

    private static final String TRACKING_CHANNEL_ID = "gps_tracking";
    private static final String ALERT_CHANNEL_ID = "safety_alerts";
    private static final int TRACKING_NOTIFICATION_ID = 41;
    private static final int SAFETY_NOTIFICATION_ID = 42;
    private static final String EXTRA_NODE_ID = "node_id";
    private final Binder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;
    private int satellitesUsed = -1;
    private String nodeId;
    private Listener listener;
    private LocalSampleStore store;
    private HikerSafetyState safetyState;
    private InactivityMonitor inactivityMonitor;
    private Esp32Transport transport; // Installed only by a future firmware-matched adapter.
    private boolean active;
    private boolean batteryReceiverRegistered;
    private long recordCount;

    public final class LocalBinder extends Binder {
        public LocationTrackingService getService() { return LocationTrackingService.this; }
    }

    private final Runnable inactivityTicker = new Runnable() {
        @Override public void run() {
            if (!active || inactivityMonitor == null) return;
            handleInactivityTransition(inactivityMonitor.check(SystemClock.elapsedRealtime()));
            if (active && inactivityMonitor != null) mainHandler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (intent == null) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
            if (percent >= 0) safetyState.onBatteryPercent(percent);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        store = new LocalSampleStore(this);
        recordCount = store.getRecordCount();
        safetyState = new HikerSafetyState();
        SharedPreferences preferences = getSharedPreferences("hiker", MODE_PRIVATE);
        safetyState.restoreFlags(
                preferences.getBoolean("flag_link_lost", false),
                preferences.getBoolean("flag_low_battery", false),
                preferences.getBoolean("flag_emergency", false),
                preferences.getBoolean("flag_turn_back_received", false),
                preferences.getBoolean("test_mode", true));
        safetyState.setListener(this::publishSafetyEvent);
        createNotificationChannels();
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent initialBattery;
        if (Build.VERSION.SDK_INT >= 33) {
            initialBattery = registerReceiver(batteryReceiver, batteryFilter, RECEIVER_NOT_EXPORTED);
        } else {
            initialBattery = registerReceiver(batteryReceiver, batteryFilter);
        }
        batteryReceiverRegistered = true;
        if (initialBattery != null) batteryReceiver.onReceive(this, initialBattery);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences preferences = getSharedPreferences("hiker", MODE_PRIVATE);
        String requestedNode = intent == null ? null : intent.getStringExtra(EXTRA_NODE_ID);
        if (requestedNode == null && preferences.getBoolean("tracking_active", false)) {
            requestedNode = preferences.getString("node_id", null);
        }
        if (requestedNode == null || requestedNode.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        nodeId = requestedNode;
        if (active) return START_STICKY;
        startForeground(TRACKING_NOTIFICATION_ID, buildTrackingNotification("Waiting for GPS fix"));
        beginUpdates();
        return active ? START_STICKY : START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) return;
        listener.onStatus(active ? "GPS tracking active" : "GPS tracking stopped", active);
        String savedMessage = getSharedPreferences("hiker", MODE_PRIVATE)
                .getString("last_event_message", safetySummary());
        listener.onSafetyEvent("STATE", savedMessage, false, safetyState.flags());
        if (inactivityMonitor != null && inactivityMonitor.isPrompted()) {
            listener.onInactivityPrompt(secondsToPromptExpiry());
        }
    }

    public boolean isActive() { return active; }
    public long getRecordCount() { return recordCount; }
    public boolean isTransportConnected() { return transport != null && transport.isConnected(); }
    public boolean isTransportSimulated() { return transport instanceof FakeEsp32Transport; }
    public FakeEsp32Transport getSimulatedTransport() { return transport instanceof FakeEsp32Transport ? (FakeEsp32Transport) transport : null; }

    /** Injection point for the future approved BLE or USB-OTG adapter. */
    public void installTransport(Esp32Transport transport, PhoneLinkDesign design) {
        if (this.transport != null) this.transport.disconnect();
        this.transport = transport;
        if (transport == null) return;
        final boolean simulated = transport instanceof FakeEsp32Transport;
        transport.connect(design, new Esp32Transport.Listener() {
            @Override public void onConnected() { mainHandler.post(() -> publishSafetyEvent("BOARD_CONNECTED", simulated ? "SIMULATED link · no real ESP32" : "ESP32 connected", false)); }
            @Override public void onDisconnected() { mainHandler.post(() -> publishSafetyEvent("BOARD_DISCONNECTED", simulated ? "SIMULATED link disconnected · no real ESP32" : "ESP32 disconnected", false)); }
            @Override public void onError(String message) { mainHandler.post(() -> publishSafetyEvent("BOARD_ERROR", simulated ? "SIMULATED link · no real ESP32 · " + message : message, true)); }
            @Override public void onDownlink(HikerDownlink message) { acceptDownlink(message); }
            @Override public void onReplySlotClosed(boolean replyReceived) { closeReplySlot(replyReceived); }
        });
    }

    /** Called by the hold-to-send SOS control. Without a board this remains local and is not delivered. */
    public void requestManualSOS() {
        safetyState.requestEmergency("manual_sos");
        persistSafetyFlags();
    }

    /** Future adapter reports the end of each reply slot; this class defines no reply-window timing. */
    public void closeReplySlot(boolean replyReceived) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> closeReplySlot(replyReceived));
            return;
        }
        safetyState.onReplySlotClosed(replyReceived);
        persistSafetyFlags();
    }

    /** Future adapter delivers already-decoded logical messages here. */
    public void acceptDownlink(HikerDownlink message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> acceptDownlink(message));
            return;
        }
        if (message == null || nodeId == null || !nodeId.equals(message.nodeId)) return;
        safetyState.onDownlink(message);
        persistSafetyFlags();
    }

    public boolean respondToInactivityPrompt() {
        if (inactivityMonitor == null || !inactivityMonitor.respondOkay(SystemClock.elapsedRealtime())) return false;
        appendEvent("INACTIVITY_CONFIRMED", "Hiker answered: I'm OK", false);
        publishSafetyEvent("INACTIVITY_CONFIRMED", "You checked in · inactivity timer restarted", false);
        return true;
    }

    public HikerFlags getCurrentFlags() { return safetyState.flags(); }

    public void setTestMode(boolean enabled) {
        getSharedPreferences("hiker", MODE_PRIVATE).edit().putBoolean("test_mode", enabled).apply();
        safetyState.setTestMode(enabled);
        persistSafetyFlags();
        if (!enabled && transport instanceof FakeEsp32Transport) installTransport(null, PhoneLinkDesign.BLE);
    }

    public void stopTracking() {
        if (transport instanceof FakeEsp32Transport) installTransport(null, PhoneLinkDesign.BLE);
        removeLocationCallbacks();
        mainHandler.removeCallbacks(inactivityTicker);
        active = false;
        getSharedPreferences("hiker", MODE_PRIVATE).edit().putBoolean("tracking_active", false).apply();
        if (listener != null) listener.onStatus("GPS tracking stopped", false);
        stopForegroundCompat();
        stopSelf();
    }

    private void beginUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            failTracking("Location permission is required");
            return;
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            failTracking("Turn on Location / GPS, then start again");
            return;
        }
        SharedPreferences preferences = getSharedPreferences("hiker", MODE_PRIVATE);
        float threshold = preferences.getFloat("movement_threshold_meters", 0f);
        inactivityMonitor = threshold > 0f ? new InactivityMonitor(threshold) : null;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                gnssCallback = new GnssStatus.Callback() {
                    @Override public void onSatelliteStatusChanged(GnssStatus status) {
                        int used = 0;
                        for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
                        satellitesUsed = used;
                    }
                };
                locationManager.registerGnssStatusCallback(gnssCallback, new Handler(Looper.getMainLooper()));
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper());
            active = true;
            preferences.edit().putBoolean("tracking_active", true).putString("node_id", nodeId).apply();
            if (inactivityMonitor != null) mainHandler.postDelayed(inactivityTicker, 1000L);
            String status = inactivityMonitor == null
                    ? "GPS tracking active · inactivity check needs a movement threshold"
                    : "GPS tracking active · inactivity check armed";
            if (listener != null) listener.onStatus(status, true);
        } catch (SecurityException | IllegalArgumentException e) {
            failTracking("Could not start GPS: " + e.getMessage());
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            Integer sats = satellitesUsed >= 0 ? satellitesUsed : null;
            Integer battery = readBatteryPercent();
            GpsSample sample = new GpsSample(nodeId, location, sats, battery, safetyState.flags());
            try {
                store.append(sample);
                recordCount++;
            } catch (Exception e) {
                publishSafetyEvent("LOCAL_SAVE_ERROR", "Could not save GPS record", true);
                return;
            }
            if (transport != null && transport.isConnected()) {
                try {
                    if (!transport.sendGpsObservation(sample, safetyState.flags())) {
                        publishSafetyEvent("BOARD_SEND_REJECTED", "ESP32 adapter did not accept the GPS observation", true);
                    }
                } catch (RuntimeException e) {
                    publishSafetyEvent("BOARD_ERROR", "Could not submit GPS observation to the ESP32 adapter", true);
                }
            }
            if (listener != null) listener.onSample(sample, recordCount);
            getSystemService(NotificationManager.class).notify(
                    TRACKING_NOTIFICATION_ID,
                    buildTrackingNotification("GPS fix saved · " + recordCount + " local records"));
            if (inactivityMonitor != null) handleInactivityTransition(
                    inactivityMonitor.onLocation(location, SystemClock.elapsedRealtime()));
        }
        @Override public void onProviderEnabled(String provider) { reportStatus("GPS provider ready", true); }
        @Override public void onProviderDisabled(String provider) { failTracking("GPS was turned off"); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    private void handleInactivityTransition(InactivityMonitor.Transition transition) {
        if (transition == InactivityMonitor.Transition.PROMPT) {
            appendEvent("INACTIVITY_PROMPT", "ARE YOU OK? Response needed within 15 seconds", true);
            AlertFeedback.alert(this, "INACTIVITY_PROMPT");
            if (listener != null) listener.onSafetyEvent("INACTIVITY_PROMPT",
                    "ARE YOU OK? Response needed within 15 seconds", true, safetyState.flags());
            if (listener != null) listener.onInactivityPrompt(InactivityMonitor.RESPONSE_TIMEOUT_MS / 1000L);
            showSafetyNotification("ARE YOU OK? Open Trailwatch and answer now · 15 seconds", true);
        } else if (transition == InactivityMonitor.Transition.EMERGENCY) {
            safetyState.requestEmergency("inactivity timeout");
            persistSafetyFlags();
        }
    }

    private Integer readBatteryPercent() {
        BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (battery == null || Build.VERSION.SDK_INT < 21) return null;
        int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (level < 0 || level > 100) return null;
        safetyState.onBatteryPercent(level);
        persistSafetyFlags();
        return level;
    }

    private void publishSafetyEvent(String type, String message, boolean urgent) {
        appendEvent(type, message, urgent);
        persistSafetyFlags();
        HikerFlags flags = safetyState.flags();
        boolean emergencyNotConnected = "SOS_PENDING".equals(type) && !isTransportConnected();
        boolean emergencyOnSimulated = "SOS_PENDING".equals(type) && isTransportConnected() && isTransportSimulated();
        String visibleMessage = emergencyNotConnected ? "SOS NOT SENT · no ESP32 connection" : emergencyOnSimulated ? "SOS passed to SIMULATED link · NOT sent to a real board" : message;
        getSharedPreferences("hiker", MODE_PRIVATE).edit()
                .putString("last_event_type", type)
                .putString("last_event_message", visibleMessage)
                .apply();
        if (listener != null) listener.onSafetyEvent(type, visibleMessage, urgent, flags);
        if (urgent || "LOW_BATTERY".equals(type)) {
            AlertFeedback.alert(this, type);
            showSafetyNotification(visibleMessage, urgent);
        }
        if ("SOS_PENDING".equals(type) && transport != null && transport.isConnected()) {
            try { transport.sendEmergency(flags); }
            catch (RuntimeException e) { publishSafetyEvent("BOARD_ERROR", "Could not submit emergency flag", true); }
        }
    }

    private void appendEvent(String type, String message, boolean urgent) {
        try {
            JSONObject event = new JSONObject();
            event.put("record_type", "safety_event");
            event.put("node_id", nodeId == null ? JSONObject.NULL : nodeId);
            event.put("captured_at_utc", java.time.Instant.now().toString());
            event.put("event_type", type);
            event.put("message", message);
            event.put("urgent", urgent);
            event.put("source", safetyState.flags().testMode ? "TEST" : "REAL");
            store.appendRecord(event);
        } catch (Exception ignored) { }
    }

    private void persistSafetyFlags() {
        HikerFlags flags = safetyState.flags();
        getSharedPreferences("hiker", MODE_PRIVATE).edit()
                .putBoolean("flag_link_lost", flags.linkLost)
                .putBoolean("flag_low_battery", flags.lowBattery)
                .putBoolean("flag_emergency", flags.emergency)
                .putBoolean("flag_turn_back_received", flags.turnBackReceived)
                .putBoolean("test_mode", flags.testMode)
                .apply();
    }

    private String safetySummary() {
        if (safetyState.isEmergencyPending()) return "SOS pending · delivery not confirmed";
        if (safetyState.isLinkLost()) return "LINK LOST";
        if (safetyState.isLowBattery()) return "Phone battery below 20%";
        if (safetyState.isTurnBackReceived()) return "STORM - TURN BACK received";
        return "No active phone warning";
    }

    private long secondsToPromptExpiry() {
        return inactivityMonitor == null ? 0L : inactivityMonitor.secondsRemaining(SystemClock.elapsedRealtime());
    }

    private void reportStatus(String message, boolean running) {
        if (listener != null) listener.onStatus(message, running);
    }

    private void failTracking(String message) {
        removeLocationCallbacks();
        mainHandler.removeCallbacks(inactivityTicker);
        active = false;
        getSharedPreferences("hiker", MODE_PRIVATE).edit().putBoolean("tracking_active", false).apply();
        if (listener != null) listener.onStatus(message, false);
        stopForegroundCompat();
        stopSelf();
    }

    private void removeLocationCallbacks() {
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) { }
            if (Build.VERSION.SDK_INT >= 24 && gnssCallback != null) {
                try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (RuntimeException ignored) { }
                gnssCallback = null;
            }
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else { //noinspection deprecation
            stopForeground(true);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(
                    TRACKING_CHANNEL_ID, "Hiker GPS tracking", NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(
                    ALERT_CHANNEL_ID, "Trailwatch safety alerts", NotificationManager.IMPORTANCE_HIGH));
        }
    }

    private PendingIntent activityPendingIntent() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 1, open, flags);
    }

    private Notification buildTrackingNotification(String text) {
        return new Notification.Builder(this, TRACKING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Trailwatch GPS is active")
                .setContentText(text)
                .setContentIntent(activityPendingIntent())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void showSafetyNotification(String text, boolean urgent) {
        Notification.Builder builder = new Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(urgent ? "Trailwatch safety alert" : "Trailwatch warning")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(activityPendingIntent())
                .setAutoCancel(false)
                .setCategory(urgent ? Notification.CATEGORY_ALARM : Notification.CATEGORY_STATUS);
        builder.setSound(null).setVibrate(new long[]{0L});
        getSystemService(NotificationManager.class).notify(SAFETY_NOTIFICATION_ID, builder.build());
    }

    @Override public void onDestroy() {
        if (active) stopTracking();
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver); } catch (IllegalArgumentException ignored) { }
        }
        if (transport != null) transport.disconnect();
        super.onDestroy();
    }
}
