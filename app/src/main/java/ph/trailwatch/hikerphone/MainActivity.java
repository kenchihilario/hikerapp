package ph.trailwatch.hikerphone;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 10;
    private static final int EXPORT_REQUEST = 11;
    private static final int BG = Color.rgb(16, 23, 19);
    private static final int CARD = Color.rgb(27, 39, 33);
    private static final int TEXT = Color.rgb(238, 244, 239);
    private static final int MUTED = Color.rgb(165, 184, 172);
    private static final int GREEN = Color.rgb(120, 201, 149);
    private static final int AMBER = Color.rgb(241, 190, 98);
    private EditText nodeField;
    private TextView stateView, gpsView, countView, hardwareView;
    private Button trackButton;
    private LocationTrackingService service;
    private boolean bound;
    private boolean requestedStart;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("hiker", MODE_PRIVATE);
        setContentView(buildScreen());
        renderStoredState();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(page);

        TextView brand = text("TRAILWATCH  /  HIKER", 13, GREEN, true);
        page.addView(brand);
        TextView title = text("Your trail companion", 28, TEXT, true);
        LinearLayout.LayoutParams titleLp = params(-1, -2); titleLp.topMargin = dp(8); page.addView(title, titleLp);
        TextView subtitle = text("GPS practice mode · works offline", 15, MUTED, false);
        LinearLayout.LayoutParams subLp = params(-1, -2); subLp.topMargin = dp(6); page.addView(subtitle, subLp);

        LinearLayout banner = card();
        TextView bannerText = text("GPS ONLY — no ESP32 connected. No radio messages are sent.", 14, AMBER, true);
        banner.addView(bannerText);
        LinearLayout.LayoutParams bannerLp = params(-1, -2); bannerLp.topMargin = dp(22); page.addView(banner, bannerLp);

        LinearLayout nodeCard = card();
        nodeCard.addView(label("HIKER NODE ID"));
        nodeField = new EditText(this);
        nodeField.setSingleLine(true); nodeField.setTextColor(TEXT); nodeField.setHintTextColor(MUTED);
        nodeField.setHint("Enter your assigned ID, e.g. H1"); nodeField.setText(prefs.getString("node_id", ""));
        nodeField.setTextSize(17); nodeCard.addView(nodeField, params(-1, -2));
        LinearLayout.LayoutParams nodeLp = params(-1, -2); nodeLp.topMargin = dp(14); page.addView(nodeCard, nodeLp);

        LinearLayout statusCard = card();
        statusCard.addView(label("TRACKING STATUS"));
        stateView = text("Not tracking", 20, TEXT, true); statusCard.addView(stateView);
        gpsView = text("Waiting for GPS", 15, MUTED, false); LinearLayout.LayoutParams gpsLp = params(-1, -2); gpsLp.topMargin = dp(12); statusCard.addView(gpsView, gpsLp);
        countView = text("0 local records", 13, MUTED, false); LinearLayout.LayoutParams countLp = params(-1, -2); countLp.topMargin = dp(12); statusCard.addView(countView, countLp);
        LinearLayout.LayoutParams statusLp = params(-1, -2); statusLp.topMargin = dp(14); page.addView(statusCard, statusLp);

        trackButton = new Button(this); trackButton.setAllCaps(false); trackButton.setTextSize(17);
        trackButton.setText("Start GPS practice"); trackButton.setOnClickListener(v -> onTrackPressed());
        LinearLayout.LayoutParams buttonLp = params(-1, dp(58)); buttonLp.topMargin = dp(16); page.addView(trackButton, buttonLp);

        Button export = new Button(this); export.setAllCaps(false); export.setText("Export my GPS log");
        export.setOnClickListener(v -> exportLog()); LinearLayout.LayoutParams exportLp = params(-1, dp(52)); exportLp.topMargin = dp(8); page.addView(export, exportLp);

        LinearLayout hardwareCard = card();
        hardwareCard.addView(label("ESP32 CONNECTION"));
        hardwareView = text("Not available yet", 17, TEXT, true); hardwareCard.addView(hardwareView);
        TextView hardwareNote = text("The connection stays disabled until the project firmware defines Bluetooth or USB behavior.", 14, MUTED, false);
        LinearLayout.LayoutParams noteLp = params(-1, -2); noteLp.topMargin = dp(8); hardwareCard.addView(hardwareNote, noteLp);
        LinearLayout.LayoutParams hwLp = params(-1, -2); hwLp.topMargin = dp(14); page.addView(hardwareCard, hwLp);

        TextView privacy = text("GPS records stay in this app's private storage unless you export them. Keep the app open or leave its tracking notification active during a practice walk.", 13, MUTED, false);
        LinearLayout.LayoutParams privacyLp = params(-1, -2); privacyLp.topMargin = dp(18); page.addView(privacy, privacyLp);
        return scroll;
    }

    private void onTrackPressed() {
        if (prefs.getBoolean("tracking_active", false)) {
            if (service != null) service.stopTracking();
            else stopService(new Intent(this, LocationTrackingService.class));
            prefs.edit().putBoolean("tracking_active", false).apply();
            renderStoredState();
            return;
        }
        String nodeId = nodeField.getText().toString().trim();
        if (!nodeId.matches("[A-Za-z0-9_-]{1,32}")) {
            nodeField.setError("Use 1–32 letters, numbers, _ or -");
            return;
        }
        prefs.edit().putString("node_id", nodeId).apply();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestedStart = true;
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, LOCATION_PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            }
            return;
        }
        startTrackingService(nodeId);
    }

    private void startTrackingService(String nodeId) {
        Intent intent = new Intent(this, LocationTrackingService.class).putExtra("node_id", nodeId);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        bindToTrackingService();
        stateView.setText("Starting GPS…");
    }

    private void bindToTrackingService() {
        if (bound) return;
        bound = bindService(new Intent(this, LocationTrackingService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((LocationTrackingService.LocalBinder) binder).getService();
            service.setListener(new LocationTrackingService.Listener() {
                @Override public void onSample(GpsSample sample, long records) { showSample(sample, records); }
                @Override public void onStatus(String message, boolean active) {
                    stateView.setText(message);
                    trackButton.setText(active ? "Stop GPS practice" : "Start GPS practice");
                    nodeField.setEnabled(!active);
                    if (active) prefs.edit().putBoolean("tracking_active", true).apply();
                }
            });
            countView.setText(service.getRecordCount() + " local records");
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    private void showSample(GpsSample s, long records) {
        String accuracy = s.accuracyMeters == null ? "unknown" : String.format(Locale.US, "%.1f m", s.accuracyMeters);
        String sats = s.satellitesUsed == null ? "waiting" : s.satellitesUsed.toString();
        String battery = s.phoneBatteryPercent == null ? "unknown" : s.phoneBatteryPercent + "%";
        gpsView.setText(String.format(Locale.US,
                "%.6f, %.6f\nAccuracy %s  ·  Satellites used %s  ·  Phone battery %s\n%s UTC",
                s.latitude, s.longitude, accuracy, sats, battery, s.capturedAt));
        countView.setText(records + " local records saved");
        stateView.setText("GPS fix saved on this phone");
    }

    private void renderStoredState() {
        boolean active = prefs.getBoolean("tracking_active", false);
        trackButton.setText(active ? "Stop GPS practice" : "Start GPS practice");
        nodeField.setEnabled(!active);
        stateView.setText(active ? "GPS tracking active" : "Not tracking");
        countView.setText(new LocalSampleStore(this).getRecordCount() + " local records");
    }

    private void exportLog() {
        LocalSampleStore store = new LocalSampleStore(this);
        if (store.getRecordCount() == 0) { Toast.makeText(this, "No GPS records to export yet", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-ndjson");
        intent.putExtra(Intent.EXTRA_TITLE, LocalSampleStore.FILE_NAME);
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        try (FileInputStream in = new FileInputStream(new LocalSampleStore(this).getFile());
             OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new IllegalStateException("Could not open export destination");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            Toast.makeText(this, "GPS log exported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != LOCATION_PERMISSION_REQUEST) return;
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (granted && requestedStart) startTrackingService(prefs.getString("node_id", ""));
        else Toast.makeText(this, "Precise location permission is needed for GPS practice", Toast.LENGTH_LONG).show();
        requestedStart = false;
    }

    @Override protected void onStart() {
        super.onStart();
        if (prefs.getBoolean("tracking_active", false)) {
            String nodeId = prefs.getString("node_id", "");
            if (nodeId.matches("[A-Za-z0-9_-]{1,32}")) startTrackingService(nodeId);
            else prefs.edit().putBoolean("tracking_active", false).apply();
        }
    }
    @Override protected void onStop() { if (bound) { if (service != null) service.setListener(null); unbindService(connection); bound = false; } super.onStop(); }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16)); view.setBackgroundColor(CARD); return view;
    }
    private TextView label(String value) {
        TextView view = text(value, 12, GREEN, true); view.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = params(-1, -2); lp.bottomMargin = dp(8); view.setLayoutParams(lp); return view;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
