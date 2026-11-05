package ph.trailwatch.hikerphone;

/** Logical message supplied by a future ESP32 adapter; contains no wire encoding. */
public final class HikerDownlink {
    public enum Type { ACKNOWLEDGEMENT, ALERT, SOS_RECEIVED, STORM_TURN_BACK }

    public final String nodeId;
    public final String baseId;
    public final Long sequenceNumberAnswered;
    public final Type type;

    public HikerDownlink(String nodeId, String baseId, Long sequenceNumberAnswered, Type type) {
        this.nodeId = nodeId;
        this.baseId = baseId;
        this.sequenceNumberAnswered = sequenceNumberAnswered;
        this.type = type;
    }
}
