package ph.trailwatch.hikerphone;

/** Logical uplink flags only. The ESP32 firmware owns packing and framing. */
public final class HikerFlags {
    public final boolean linkLost;
    public final boolean lowBattery;
    public final boolean emergency;
    public final boolean turnBackReceived;
    public final boolean testMode;

    public HikerFlags(boolean linkLost, boolean lowBattery, boolean emergency,
                      boolean turnBackReceived, boolean testMode) {
        this.linkLost = linkLost;
        this.lowBattery = lowBattery;
        this.emergency = emergency;
        this.turnBackReceived = turnBackReceived;
        this.testMode = testMode;
    }
}
