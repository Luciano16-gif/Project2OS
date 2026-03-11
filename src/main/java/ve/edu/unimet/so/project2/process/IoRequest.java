package ve.edu.unimet.so.project2.process;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;

public final class IoRequest {

    private final String requestId;
    private final String processId;
    private final IoOperationType operationType;
    private final FsNodeType targetNodeType;
    private final String targetPath;
    private final String targetNodeId;
    private final int targetBlock;
    private final int requestedSizeInBlocks;
    private final String issuerUserId;
    private final long arrivalOrder;

    public IoRequest(
            String requestId,
            String processId,
            IoOperationType operationType,
            FsNodeType targetNodeType,
            String targetPath,
            String targetNodeId,
            int targetBlock,
            int requestedSizeInBlocks,
            String issuerUserId,
            long arrivalOrder) {
        this.requestId = requireNonBlank(requestId, "requestId");
        this.processId = requireNonBlank(processId, "processId");
        if (operationType == null) {
            throw new IllegalArgumentException("operationType cannot be null");
        }
        if (targetNodeType == null) {
            throw new IllegalArgumentException("targetNodeType cannot be null");
        }
        this.targetPath = requireNonBlank(targetPath, "targetPath");
        this.targetNodeId = normalizeOptional(targetNodeId);
        if (targetBlock < 0) {
            throw new IllegalArgumentException("targetBlock cannot be negative");
        }
        if (requestedSizeInBlocks < 0) {
            throw new IllegalArgumentException("requestedSizeInBlocks cannot be negative");
        }
        this.issuerUserId = requireNonBlank(issuerUserId, "issuerUserId");
        if (arrivalOrder < 0) {
            throw new IllegalArgumentException("arrivalOrder cannot be negative");
        }

        validateRequestedSize(operationType, targetNodeType, requestedSizeInBlocks);
        validateTargetBinding(operationType, this.targetNodeId);

        this.operationType = operationType;
        this.targetNodeType = targetNodeType;
        this.targetBlock = targetBlock;
        this.requestedSizeInBlocks = requestedSizeInBlocks;
        this.arrivalOrder = arrivalOrder;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getProcessId() {
        return processId;
    }

    public IoOperationType getOperationType() {
        return operationType;
    }

    public FsNodeType getTargetNodeType() {
        return targetNodeType;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public int getTargetBlock() {
        return targetBlock;
    }

    public int getRequestedSizeInBlocks() {
        return requestedSizeInBlocks;
    }

    public String getIssuerUserId() {
        return issuerUserId;
    }

    public long getArrivalOrder() {
        return arrivalOrder;
    }

    public boolean targetsExistingNode() {
        return targetNodeId != null;
    }

    public boolean targetsDirectory() {
        return targetNodeType == FsNodeType.DIRECTORY;
    }

    public boolean targetsFile() {
        return targetNodeType == FsNodeType.FILE;
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

    private static void validateRequestedSize(
            IoOperationType operationType,
            FsNodeType targetNodeType,
            int requestedSizeInBlocks) {
        if (operationType != IoOperationType.CREATE) {
            return;
        }

        if (targetNodeType == FsNodeType.FILE && requestedSizeInBlocks <= 0) {
            throw new IllegalArgumentException("CREATE file requests must request at least one block");
        }

        if (targetNodeType == FsNodeType.DIRECTORY && requestedSizeInBlocks != 0) {
            throw new IllegalArgumentException("CREATE directory requests must use zero requested blocks");
        }
    }

    private static void validateTargetBinding(IoOperationType operationType, String targetNodeId) {
        if (operationType == IoOperationType.CREATE) {
            return;
        }

        if (targetNodeId == null) {
            throw new IllegalArgumentException("non-CREATE requests must reference an existing targetNodeId");
        }
    }
}
