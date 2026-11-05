package ph.trailwatch.hikerphone;

/** Phone-side safety state driven by logical transport events. */
public final class HikerSafetyState {
    public interface Listener {
        void onSafetyEvent(String eventType, String message, boolean urgent);
    }

    private static final int MISSED_REPLY_LIMIT = 3;
    private Listener listener;
    private int consecutiveMissedReplySlots;
    private boolean linkLost;
    private boolean lowBattery;
    private boolean emergencyPending;
    private boolean turnBackReceived;
    private boolean testMode = true;
    private int batteryPercent = -1;

    public void setListener(Listener listener) { this.listener = listener; }

    public void restoreFlags(boolean linkLost, boolean lowBattery, boolean emergencyPending,
                             boolean turnBackReceived, boolean testMode) {
        this.linkLost = linkLost;
        this.lowBattery = lowBattery;
        this.emergencyPending = emergencyPending;
        this.turnBackReceived = turnBackReceived;
        this.testMode = testMode;
    }

    public void setTestMode(boolean enabled) {
        if (testMode == enabled) return;
        testMode = enabled;
        emit(enabled ? "TEST_MODE_ON" : "TEST_MODE_OFF",
                enabled ? "Test mode is ON · outgoing records marked TEST" : "Test mode is OFF · outgoing records may count as research data",
                false);
    }

    public void onReplySlotClosed(boolean replyReceived) {
        if (replyReceived) {
            consecutiveMissedReplySlots = 0;
            if (linkLost) {
                linkLost = false;
                emit("LINK_RESTORED", "Base Camp reply received · link restored", false);
            }
            return;
        }
        consecutiveMissedReplySlots++;
        if (consecutiveMissedReplySlots >= MISSED_REPLY_LIMIT && !linkLost) {
            linkLost = true;
            emit("LINK_LOST", "LINK LOST · three uplinks received no reply", true);
        }
    }

    public void onBatteryPercent(int percent) {
        batteryPercent = percent >= 0 && percent <= 100 ? percent : -1;
        boolean isLow = batteryPercent >= 0 && batteryPercent < 20;
        boolean wasLow = lowBattery;
        lowBattery = isLow;
        if (isLow && !wasLow) emit("LOW_BATTERY", "Phone battery is below 20%", false);
        else if (wasLow && !isLow && batteryPercent >= 20) emit("BATTERY_RECOVERED", "Phone battery is back to 20% or higher", false);
    }

    public void requestEmergency(String source) {
        emergencyPending = true;
        emit("SOS_PENDING", source.equals("manual_sos")
                ? "SOS requested · ESP32 delivery is not confirmed"
                : "Inactivity emergency set · ESP32 delivery is not confirmed", true);
    }

    public void onDownlink(HikerDownlink message) {
        if (message == null || message.type == null) return;
        switch (message.type) {
            case ACKNOWLEDGEMENT:
                onReplySlotClosed(true);
                break;
            case ALERT:
                emit("OFF_TRAIL", "OFF TRAIL - TURN BACK", true);
                break;
            case SOS_RECEIVED:
                emergencyPending = false;
                emit("SOS_RECEIVED", "SOS RECEIVED", true);
                break;
            case STORM_TURN_BACK:
                turnBackReceived = true;
                emit("STORM_TURN_BACK", "STORM - TURN BACK", true);
                break;
        }
    }

    public HikerFlags flags() {
        return new HikerFlags(linkLost, lowBattery, emergencyPending, turnBackReceived, testMode);
    }

    public boolean isLinkLost() { return linkLost; }
    public boolean isLowBattery() { return lowBattery; }
    public boolean isEmergencyPending() { return emergencyPending; }
    public boolean isTurnBackReceived() { return turnBackReceived; }
    public int getBatteryPercent() { return batteryPercent; }

    private void emit(String type, String message, boolean urgent) {
        if (listener != null) listener.onSafetyEvent(type, message, urgent);
    }
}
