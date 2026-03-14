package ve.edu.unimet.so.project2.application;

public final class SwitchSessionIntent implements ApplicationOperationIntent {

    private final String targetUserId;

    public SwitchSessionIntent(String targetUserId) {
        this.targetUserId = requireNonBlank(targetUserId, "targetUserId");
    }

    public String getTargetUserId() {
        return targetUserId;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
