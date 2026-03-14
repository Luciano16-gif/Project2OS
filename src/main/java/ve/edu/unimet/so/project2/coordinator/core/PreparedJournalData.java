package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;

public final class PreparedJournalData {

    private final JournalUndoData undoData;
    private final String targetNodeId;
    private final String ownerUserId;
    private final String description;

    public PreparedJournalData(
            JournalUndoData undoData,
            String targetNodeId,
            String ownerUserId,
            String description) {
        if (undoData == null) {
            throw new IllegalArgumentException("undoData cannot be null");
        }
        this.undoData = undoData;
        this.targetNodeId = normalizeOptional(targetNodeId);
        this.ownerUserId = normalizeOptional(ownerUserId);
        this.description = normalizeOptional(description);
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
