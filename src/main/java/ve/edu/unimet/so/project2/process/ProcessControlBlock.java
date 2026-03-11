package ve.edu.unimet.so.project2.process;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.locking.LockType;

public class ProcessControlBlock {

    public static final long UNSET_TICK = -1L;

    private final String processId;
    private final String ownerUserId;
    private ProcessState state;
    private final IoRequest request;
    private final FsNodeType targetNodeType;
    private final String targetNodeId;
    private final String targetPath;
    private final int targetBlock;
    private final LockType requiredLockType;
    private WaitReason waitReason;
    private ResultStatus resultStatus;
    private final long creationTick;
    private long readyTick;
    private long startTick;
    private long endTick;
    private String blockedByProcessId;
    private String errorMessage;

    public ProcessControlBlock(
            String processId,
            String ownerUserId,
            IoRequest request,
            FsNodeType targetNodeType,
            String targetNodeId,
            String targetPath,
            int targetBlock,
            LockType requiredLockType,
            long creationTick) {
        this.processId = requireNonBlank(processId, "processId");
        this.ownerUserId = requireNonBlank(ownerUserId, "ownerUserId");
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        if (!this.processId.equals(request.getProcessId())) {
            throw new IllegalArgumentException("request processId must match pcb processId");
        }
        if (!this.ownerUserId.equals(request.getIssuerUserId())) {
            throw new IllegalArgumentException("request issuerUserId must match pcb ownerUserId");
        }
        if (targetNodeType == null) {
            throw new IllegalArgumentException("targetNodeType cannot be null");
        }
        this.request = request;
        this.targetNodeType = targetNodeType;
        this.targetNodeId = normalizeOptional(targetNodeId);
        this.targetPath = requireNonBlank(targetPath, "targetPath");
        if (targetBlock < 0) {
            throw new IllegalArgumentException("targetBlock cannot be negative");
        }
        if (creationTick < 0) {
            throw new IllegalArgumentException("creationTick cannot be negative");
        }
        if (!this.targetPath.equals(request.getTargetPath())) {
            throw new IllegalArgumentException("request targetPath must match pcb targetPath");
        }
        if (targetBlock != request.getTargetBlock()) {
            throw new IllegalArgumentException("request targetBlock must match pcb targetBlock");
        }
        if (!sameOptionalValue(this.targetNodeId, request.getTargetNodeId())) {
            throw new IllegalArgumentException("request targetNodeId must match pcb targetNodeId");
        }
        if (this.targetNodeType != request.getTargetNodeType()) {
            throw new IllegalArgumentException("request targetNodeType must match pcb targetNodeType");
        }
        validateRequiredLockType(request.getOperationType(), this.targetNodeType, requiredLockType);

        this.targetBlock = targetBlock;
        this.requiredLockType = requiredLockType;
        this.creationTick = creationTick;
        this.state = ProcessState.NEW;
        this.waitReason = WaitReason.NONE;
        this.resultStatus = null;
        this.readyTick = UNSET_TICK;
        this.startTick = UNSET_TICK;
        this.endTick = UNSET_TICK;
        this.blockedByProcessId = null;
        this.errorMessage = null;
    }

    public String getProcessId() {
        return processId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public ProcessState getState() {
        return state;
    }

    public IoRequest getRequest() {
        return request;
    }

    public FsNodeType getTargetNodeType() {
        return targetNodeType;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public int getTargetBlock() {
        return targetBlock;
    }

    public LockType getRequiredLockType() {
        return requiredLockType;
    }

    public WaitReason getWaitReason() {
        return waitReason;
    }

    public ResultStatus getResultStatus() {
        return resultStatus;
    }

    public long getCreationTick() {
        return creationTick;
    }

    public long getReadyTick() {
        return readyTick;
    }

    public long getStartTick() {
        return startTick;
    }

    public long getEndTick() {
        return endTick;
    }

    public String getBlockedByProcessId() {
        return blockedByProcessId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean hasStarted() {
        return startTick != UNSET_TICK;
    }

    public boolean hasFinished() {
        return endTick != UNSET_TICK;
    }

    public void markReady(long tick) {
        requireNonNegative(tick, "tick");
        if (state != ProcessState.NEW && state != ProcessState.BLOCKED) {
            throw new IllegalStateException("only NEW or BLOCKED processes can move to READY");
        }
        if (tick < creationTick) {
            throw new IllegalArgumentException("ready tick cannot be earlier than creationTick");
        }
        if (hasStarted() && tick < startTick) {
            throw new IllegalArgumentException("ready tick cannot be earlier than the first startTick");
        }

        this.state = ProcessState.READY;
        this.waitReason = WaitReason.WAITING_SCHEDULER;
        this.readyTick = tick;
        this.blockedByProcessId = null;
    }

    public void markRunning(long tick) {
        requireNonNegative(tick, "tick");
        if (state != ProcessState.READY) {
            throw new IllegalStateException("only READY processes can move to RUNNING");
        }
        if (readyTick == UNSET_TICK) {
            throw new IllegalStateException("process must have a readyTick before running");
        }
        if (tick < readyTick) {
            throw new IllegalArgumentException("start tick cannot be earlier than readyTick");
        }

        this.state = ProcessState.RUNNING;
        this.waitReason = WaitReason.NONE;
        if (this.startTick == UNSET_TICK) {
            this.startTick = tick;
        }
        this.blockedByProcessId = null;
    }

    public void markBlocked(WaitReason waitReason, String blockedByProcessId) {
        if (waitReason == null || waitReason == WaitReason.NONE) {
            throw new IllegalArgumentException("blocked processes require a concrete waitReason");
        }
        if (waitReason == WaitReason.WAITING_SCHEDULER) {
            throw new IllegalArgumentException("blocked processes cannot use WAITING_SCHEDULER");
        }
        if (state == ProcessState.TERMINATED) {
            throw new IllegalStateException("terminated processes cannot be blocked");
        }

        this.state = ProcessState.BLOCKED;
        this.waitReason = waitReason;
        this.blockedByProcessId = normalizeOptional(blockedByProcessId);
    }

    public void markTerminated(ResultStatus resultStatus, long tick, String errorMessage) {
        if (resultStatus == null) {
            throw new IllegalArgumentException("resultStatus cannot be null");
        }
        requireNonNegative(tick, "tick");
        if (state == ProcessState.TERMINATED) {
            throw new IllegalStateException("process is already terminated");
        }
        if (tick < creationTick) {
            throw new IllegalArgumentException("end tick cannot be earlier than creationTick");
        }
        if (readyTick != UNSET_TICK && tick < readyTick) {
            throw new IllegalArgumentException("end tick cannot be earlier than readyTick");
        }
        if (hasStarted() && tick < startTick) {
            throw new IllegalArgumentException("end tick cannot be earlier than startTick");
        }

        this.state = ProcessState.TERMINATED;
        this.resultStatus = resultStatus;
        this.waitReason = WaitReason.NONE;
        this.endTick = tick;
        this.blockedByProcessId = null;
        this.errorMessage = normalizeOptional(errorMessage);
    }

    public void clearErrorMessage() {
        this.errorMessage = null;
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

    private static void requireNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative");
        }
    }

    private static boolean sameOptionalValue(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private static void validateRequiredLockType(
            IoOperationType operationType,
            FsNodeType targetNodeType,
            LockType requiredLockType) {
        if (operationType == null) {
            throw new IllegalArgumentException("operationType cannot be null");
        }
        if (targetNodeType == null) {
            throw new IllegalArgumentException("targetNodeType cannot be null");
        }

        if (operationType == IoOperationType.CREATE) {
            if (requiredLockType != null) {
                throw new IllegalArgumentException("CREATE processes must not declare a file lock type");
            }
            return;
        }

        if (operationType == IoOperationType.READ) {
            if (targetNodeType != FsNodeType.FILE) {
                throw new IllegalArgumentException("READ processes must target regular files");
            }
            if (requiredLockType != LockType.SHARED) {
                throw new IllegalArgumentException("READ processes must declare SHARED lock type");
            }
            return;
        }

        if (operationType == IoOperationType.UPDATE || operationType == IoOperationType.DELETE) {
            if (targetNodeType == FsNodeType.DIRECTORY) {
                if (requiredLockType != null) {
                    throw new IllegalArgumentException("directory UPDATE and DELETE processes must not declare file locks");
                }
                return;
            }
            if (requiredLockType != LockType.EXCLUSIVE) {
                throw new IllegalArgumentException("file UPDATE and DELETE processes must declare EXCLUSIVE lock type");
            }
            return;
        }

        throw new IllegalArgumentException("unsupported operationType: " + operationType);
    }
}
