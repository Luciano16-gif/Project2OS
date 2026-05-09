package ve.edu.unimet.so.project2.application;

public record SwitchSessionIntent(String targetUserId) implements ApplicationOperationIntent {

    public SwitchSessionIntent {
        targetUserId = ApplicationOperationIntent.requireNonBlank(targetUserId, "targetUserId");
    }

    public String getTargetUserId() { return targetUserId; }
}
