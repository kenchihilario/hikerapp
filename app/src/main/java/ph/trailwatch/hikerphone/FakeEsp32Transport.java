package ph.trailwatch.hikerphone;

import android.os.Handler;
import android.os.Looper;

/** SIMULATED transport only: not a real board and never for LIVE mode. */
public final class FakeEsp32Transport implements Esp32Transport {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean connected;
    private final Runnable delayedConnect = () -> {
        connected = true;
        if (listener != null) listener.onConnected();
    };
    private int gpsObservationCount;
    private int emergencyCount;

    public int getGpsObservationCount() { return gpsObservationCount; }
    public int getEmergencyCount() { return emergencyCount; }
    @Override public boolean isConnected() { return connected; }

    @Override public void connect(PhoneLinkDesign design, Listener listener) {
        this.listener = listener;
        mainHandler.removeCallbacks(delayedConnect);
        mainHandler.postDelayed(delayedConnect, 300L);
    }

    @Override public void disconnect() {
        mainHandler.removeCallbacks(delayedConnect);
        boolean wasConnected = connected;
        connected = false;
        if (wasConnected && listener != null) mainHandler.post(() -> listener.onDisconnected());
    }

    @Override public boolean sendGpsObservation(GpsSample sample, HikerFlags flags) {
        if (!connected) return false;
        gpsObservationCount++;
        return true;
    }

    @Override public boolean sendEmergency(HikerFlags flags) {
        if (!connected) return false;
        emergencyCount++;
        return true;
    }

    public void fakeDownlink(HikerDownlink message) {
        if (connected && listener != null) listener.onDownlink(message);
    }

    public void closeReplySlot(boolean received) {
        if (connected && listener != null) listener.onReplySlotClosed(received);
    }
}
