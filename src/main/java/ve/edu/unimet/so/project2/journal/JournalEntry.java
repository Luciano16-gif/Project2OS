package ve.edu.unimet.so.project2.journal;

import ve.edu.unimet.so.project2.journal.undo.CreateDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.CreateFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteDirectoryUndoData;
import ve.edu.unimet.so.project2.journal.undo.DeleteFileUndoData;
import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;
import ve.edu.unimet.so.project2.journal.undo.UpdateRenameUndoData;
import ve.edu.unimet.so.project2.process.IoOperationType;

public final class JournalEntry {

    private final String transactionId;
    private final IoOperationType operationType;
    private final String targetPath;
    private JournalStatus status;
    private final JournalUndoData undoData;
    private final String targetNodeId;
    private final String ownerUserId;
    private final String description;

    JournalEntry(
            String transactionId,
            IoOperationType operationType,
            String targetPath,
            JournalStatus status,
            JournalUndoData undoData,
            String targetNodeId,
            String ownerUserId,
            String description) {
        this.transactionId = requireNonBlank(transactionId, "transactionId");
        if (operationType == null) {
            throw new IllegalArgumentException("operationType cannot be null");
        }
        if (operationType == IoOperationType.READ) {
            throw new IllegalArgumentException("READ operations cannot be journaled");
        }
        this.targetPath = requireNonBlank(targetPath, "targetPath");
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (undoData == null) {
            throw new IllegalArgumentException("undoData cannot be null");
        }

        validateUndoDataCompatibility(operationType, undoData);

        this.operationType = operationType;
        this.status = status;
        this.undoData = undoData;
        this.targetNodeId = normalizeOptional(targetNodeId);
        this.ownerUserId = normalizeOptional(ownerUserId);
        this.description = normalizeOptional(description);
    }

    public String getTransactionId() {
        return transactionId;
    }

    public IoOperationType getOperationType() {
        return operationType;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public JournalStatus getStatus() {
        return status;
    }

    public JournalUndoData getUndoData() {
        return undoData;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPending() {
        return status == JournalStatus.PENDING;
    }

    public boolean isCommitted() {
        return status == JournalStatus.COMMITTED;
    }

    public boolean isUndone() {
        return status == JournalStatus.UNDONE;
    }

    void markCommitted() {
        transitionTo(JournalStatus.COMMITTED);
    }

    void markUndone() {
        transitionTo(JournalStatus.UNDONE);
    }

    private void transitionTo(JournalStatus newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("newStatus cannot be null");
        }
        if (status != JournalStatus.PENDING) {
            throw new IllegalStateException("only PENDING journal entries can change status");
        }
        if (newStatus != JournalStatus.COMMITTED && newStatus != JournalStatus.UNDONE) {
            throw new IllegalArgumentException("PENDING entries can only transition to COMMITTED or UNDONE");
        }
        status = newStatus;
    }

    private static void validateUndoDataCompatibility(IoOperationType operationType, JournalUndoData undoData) {
        switch (operationType) {
            case CREATE -> {
                if (!(undoData instanceof CreateFileUndoData) && !(undoData instanceof CreateDirectoryUndoData)) {
                    throw new IllegalArgumentException("CREATE journal entries require create undo data");
                }
            }
            case DELETE -> {
                if (!(undoData instanceof DeleteFileUndoData) && !(undoData instanceof DeleteDirectoryUndoData)) {
                    throw new IllegalArgumentException("DELETE journal entries require delete undo data");
                }
            }
            case UPDATE -> {
                if (!(undoData instanceof UpdateRenameUndoData)) {
                    throw new IllegalArgumentException("UPDATE journal entries require rename undo data");
                }
            }
            case READ -> throw new IllegalArgumentException("READ operations cannot be journaled");
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
