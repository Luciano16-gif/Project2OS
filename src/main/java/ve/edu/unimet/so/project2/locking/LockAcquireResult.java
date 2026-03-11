package ve.edu.unimet.so.project2.locking;

public final class LockAcquireResult {

    private final LockAcquireDecision decision;
    private final String fileId;
    private final String processId;
    private final LockType requestedLockType;
    private final String blockingProcessId;

    public LockAcquireResult(
            LockAcquireDecision decision,
            String fileId,
            String processId,
            LockType requestedLockType,
            String blockingProcessId) {
        if (decision == null) {
            throw new IllegalArgumentException("decision cannot be null");
        }
        if (requestedLockType == null) {
            throw new IllegalArgumentException("requestedLockType cannot be null");
        }
        this.decision = decision;
        this.fileId = requireNonBlank(fileId, "fileId");
        this.processId = requireNonBlank(processId, "processId");
        this.requestedLockType = requestedLockType;
        this.blockingProcessId = normalizeOptional(blockingProcessId);
    }

    public LockAcquireDecision getDecision() {
        return decision;
    }

    public String getFileId() {
        return fileId;
    }

    public String getProcessId() {
        return processId;
    }

    public LockType getRequestedLockType() {
        return requestedLockType;
    }

    public String getBlockingProcessId() {
        return blockingProcessId;
    }

    public boolean isGranted() {
        return decision == LockAcquireDecision.GRANTED;
    }

    public boolean isBlocked() {
        return decision == LockAcquireDecision.BLOCKED;
    }

    public boolean isAlreadyHeld() {
        return decision == LockAcquireDecision.ALREADY_HELD;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
