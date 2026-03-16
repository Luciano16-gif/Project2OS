package ve.edu.unimet.so.project2.coordinator.log;

public final class SystemEventLogEntry {

    private final long sequenceNumber;
    private final long tick;
    private final String category;
    private final String message;

    public SystemEventLogEntry(long sequenceNumber, long tick, String category, String message) {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("tick cannot be negative");
        }
        this.sequenceNumber = sequenceNumber;
        this.tick = tick;
        this.category = requireNonBlank(category, "category");
        this.message = requireNonBlank(message, "message");
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTick() {
        return tick;
    }

    public String getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
