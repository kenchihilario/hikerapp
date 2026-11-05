package ph.trailwatch.hikerphone;

import android.location.Location;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;

/** A local GPS observation. This is not an uplink or a radio packet. */
public final class GpsSample {
    public final String nodeId;
    public final String capturedAt;
    public final double latitude;
    public final double longitude;
    public final Float accuracyMeters;
    public final Integer satellitesUsed;
    public final Integer phoneBatteryPercent;

    public GpsSample(String nodeId, Location location, Integer satellitesUsed, Integer batteryPercent) {
        this.nodeId = nodeId;
        this.capturedAt = Instant.now().toString();
        this.latitude = location.getLatitude();
        this.longitude = location.getLongitude();
        this.accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : null;
        this.satellitesUsed = satellitesUsed;
        this.phoneBatteryPercent = batteryPercent;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject row = new JSONObject();
        row.put("record_type", "local_gps_observation");
        row.put("node_id", nodeId);
        row.put("captured_at_utc", capturedAt);
        row.put("latitude", latitude);
        row.put("longitude", longitude);
        row.put("gps_accuracy_m", accuracyMeters == null ? JSONObject.NULL : accuracyMeters);
        row.put("satellites_used_in_fix", satellitesUsed == null ? JSONObject.NULL : satellitesUsed);
        row.put("phone_battery_percent", phoneBatteryPercent == null ? JSONObject.NULL : phoneBatteryPercent);
        return row;
    }
}
