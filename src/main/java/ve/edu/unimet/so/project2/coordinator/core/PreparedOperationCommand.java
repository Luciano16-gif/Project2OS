package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;
import ve.edu.unimet.so.project2.journal.undo.CreateDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
import ve.edu.unimet.so.project2.locking.LockType;
import ve.edu.unimet.so.project2.process.IoOperationType;

public final class PreparedOperationCommand {

    private final String requestId;
    private final String processId;
    private final String ownerUserId;
    private final IoOperationType operationType;
    private final FsNodeType targetNodeType;
    private final String targetPath;
    private final String targetNodeId;
    private final int targetBlock;
    private final int requestedSizeInBlocks;
    private final LockType requiredLockType;
    private final PreparedJournalData preparedJournalData;
    private final CoordinatorOperationHandler operationHandler;

    public PreparedOperationCommand(
            String requestId,
            String processId,
            String ownerUserId,
            IoOperationType operationType,
            FsNodeType targetNodeType,
            String targetPath,
            String targetNodeId,
            int targetBlock,
            int requestedSizeInBlocks,
            LockType requiredLockType,
            PreparedJournalData preparedJournalData,
            CoordinatorOperationHandler operationHandler) {
        this.requestId = requireNonBlank(requestId, "requestId");
        this.processId = requireNonBlank(processId, "processId");
        this.ownerUserId = requireNonBlank(ownerUserId, "ownerUserId");
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
        if (operationHandler == null) {
            throw new IllegalArgumentException("operationHandler cannot be null");
        }

        this.operationType = operationType;
        this.targetNodeType = targetNodeType;
        this.targetBlock = targetBlock;
        this.requestedSizeInBlocks = requestedSizeInBlocks;
        this.requiredLockType = requiredLockType;
        this.preparedJournalData = preparedJournalData;
        this.operationHandler = operationHandler;

        validateTargetBinding(operationType, this.targetNodeId);
        validateJournalRequirement(operationType, preparedJournalData);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getProcessId() {
        return processId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
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

    public LockType getRequiredLockType() {
        return requiredLockType;
    }

    public PreparedJournalData getPreparedJournalData() {
        return preparedJournalData;
    }

    public CoordinatorOperationHandler getOperationHandler() {
        return operationHandler;
    }

    public boolean requiresJournal() {
        return operationType != IoOperationType.READ;
    }

    private static void validateTargetBinding(IoOperationType operationType, String targetNodeId) {
        if (operationType == IoOperationType.CREATE) {
            return;
        }
        if (targetNodeId == null) {
            throw new IllegalArgumentException("non-CREATE operations must include targetNodeId");
        }
    }

    private static void validateJournalRequirement(
            IoOperationType operationType,
            PreparedJournalData preparedJournalData) {
        if (operationType == IoOperationType.READ) {
            if (preparedJournalData != null) {
                throw new IllegalArgumentException("READ operations must not include journal data");
            }
            return;
        }
        if (preparedJournalData == null) {
            throw new IllegalArgumentException("modifying operations require preparedJournalData");
        }
        validateUndoDataCompatibility(operationType, preparedJournalData.getUndoData());
    }

    private static void validateUndoDataCompatibility(
            IoOperationType operationType,
            JournalUndoData undoData) {
        switch (operationType) {
            case CREATE -> {
                if (!(undoData instanceof CreateFileUndoData) && !(undoData instanceof CreateDirectoryUndoData)) {
                    throw new IllegalArgumentException("CREATE operations require create undo data");
                }
            }
            case DELETE -> {
                if (!(undoData instanceof DeleteFileUndoData) && !(undoData instanceof DeleteDirectoryUndoData)) {
                    throw new IllegalArgumentException("DELETE operations require delete undo data");
                }
            }
            case UPDATE -> {
                if (!(undoData instanceof UpdateRenameUndoData)) {
                    throw new IllegalArgumentException("UPDATE operations require rename undo data");
                }
            }
            case READ -> throw new IllegalArgumentException("READ operations must not include journal data");
            default -> throw new IllegalArgumentException("unsupported operationType: " + operationType);
        }
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
