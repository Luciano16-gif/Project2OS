package ve.edu.unimet.so.project2.coordinator.core;

import ve.edu.unimet.so.project2.journal.undo.JournalUndoData;

public record PreparedJournalData(
        JournalUndoData undoData,
        String targetNodeId,
        String ownerUserId,
        String description) {

    public PreparedJournalData {
        if (undoData == null) {
            throw new IllegalArgumentException("undoData cannot be null");
        }
        targetNodeId = normalizeOptional(targetNodeId);
        ownerUserId = normalizeOptional(ownerUserId);
        description = normalizeOptional(description);
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
