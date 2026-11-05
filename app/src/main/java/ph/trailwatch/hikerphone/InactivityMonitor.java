package ph.trailwatch.hikerphone;

import android.location.Location;

/** Inactivity thresholds follow the proposal; the movement threshold must be set by the operator. */
public final class InactivityMonitor {
    public enum Transition { NONE, MOVED, PROMPT, EMERGENCY }

    public static final long STILLNESS_TIMEOUT_MS = 30L * 60L * 1000L;
    public static final long RESPONSE_TIMEOUT_MS = 15L * 1000L;

    private final float movementThresholdMeters;
    private Location lastMovementLocation;
    private long lastMovementElapsedMs;
    private long promptStartedElapsedMs;
    private boolean prompted;
    private boolean emergencyRaised;

    public InactivityMonitor(float movementThresholdMeters) {
        if (!Float.isFinite(movementThresholdMeters) || movementThresholdMeters <= 0f) {
            throw new IllegalArgumentException("Movement threshold must be a positive number of meters");
        }
        this.movementThresholdMeters = movementThresholdMeters;
    }

    public Transition onLocation(Location location, long nowElapsedMs) {
        if (location == null || !location.hasAccuracy()) return Transition.NONE;
        if (lastMovementLocation == null) {
            lastMovementLocation = new Location(location);
            lastMovementElapsedMs = nowElapsedMs;
            return Transition.NONE;
        }
        if (prompted || emergencyRaised) return Transition.NONE;
        if (lastMovementLocation.distanceTo(location) >= movementThresholdMeters) {
            lastMovementLocation = new Location(location);
            lastMovementElapsedMs = nowElapsedMs;
            return Transition.MOVED;
        }
        return check(nowElapsedMs);
    }

    public Transition check(long nowElapsedMs) {
        if (lastMovementLocation == null || emergencyRaised) return Transition.NONE;
        if (!prompted && nowElapsedMs - lastMovementElapsedMs >= STILLNESS_TIMEOUT_MS) {
            prompted = true;
            promptStartedElapsedMs = nowElapsedMs;
            return Transition.PROMPT;
        }
        if (prompted && nowElapsedMs - promptStartedElapsedMs >= RESPONSE_TIMEOUT_MS) {
            emergencyRaised = true;
            return Transition.EMERGENCY;
        }
        return Transition.NONE;
    }

    public boolean respondOkay(long nowElapsedMs) {
        if (!prompted || emergencyRaised) return false;
        prompted = false;
        lastMovementElapsedMs = nowElapsedMs;
        promptStartedElapsedMs = 0;
        return true;
    }

    public boolean isPrompted() { return prompted && !emergencyRaised; }
    public boolean isEmergencyRaised() { return emergencyRaised; }
    public float getMovementThresholdMeters() { return movementThresholdMeters; }
}
