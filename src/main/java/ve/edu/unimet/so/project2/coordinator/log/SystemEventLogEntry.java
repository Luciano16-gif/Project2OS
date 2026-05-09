package ve.edu.unimet.so.project2.coordinator.log;

public record SystemEventLogEntry(
        long sequenceNumber,
        long tick,
        String category,
        String message) {

    public SystemEventLogEntry {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        if (tick < 0) {
            throw new IllegalArgumentException("tick cannot be negative");
        }
        category = requireNonBlank(category, "category");
        message = requireNonBlank(message, "message");
    }

    public long getSequenceNumber() { return sequenceNumber; }
    public long getTick() { return tick; }
    public String getCategory() { return category; }
    public String getMessage() { return message; }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
