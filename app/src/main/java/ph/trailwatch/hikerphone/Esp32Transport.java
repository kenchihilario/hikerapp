package ph.trailwatch.hikerphone;

/**
 * Logical phone-to-board boundary. A concrete adapter needs the approved board
 * interface and firmware contract before it can be implemented. This interface
 * does not define serialization, bytes, line endings, BLE UUIDs, or USB framing.
 */
public interface Esp32Transport {
    interface Listener {
        void onConnected();
        void onDisconnected();
        void onError(String safeMessage);
        void onDownlink(HikerDownlink message);
        void onReplySlotClosed(boolean replyReceived);
    }

    boolean isConnected();
    void connect(PhoneLinkDesign design, Listener listener);
    void disconnect();
    boolean sendGpsObservation(GpsSample sample, HikerFlags flags);
    boolean sendEmergency(HikerFlags flags);
}
