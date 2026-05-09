package ve.edu.unimet.so.project2.journal.undo;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;

public record DeleteDirectoryUndoData(
        String deletedDirectoryId,
        String parentDirectoryId,
        String parentDirectoryPath,
        JournalNodeSnapshot[] subtreeNodeSnapshots,
        JournalBlockSnapshot[] associatedBlockSnapshots) implements JournalUndoData {

    public DeleteDirectoryUndoData {
        deletedDirectoryId = JournalUndoData.requireNonBlank(deletedDirectoryId, "deletedDirectoryId");
        parentDirectoryId = JournalUndoData.requireNonBlank(parentDirectoryId, "parentDirectoryId");
        parentDirectoryPath = JournalUndoData.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        subtreeNodeSnapshots = copyAndValidateNodeSnapshots(subtreeNodeSnapshots, deletedDirectoryId);
        validateRootParentConsistency(deletedDirectoryId, parentDirectoryId, parentDirectoryPath, subtreeNodeSnapshots);
        associatedBlockSnapshots = copyAndValidateBlockSnapshots(
                associatedBlockSnapshots,
                deletedDirectoryId,
                subtreeNodeSnapshots);
    }

    public String getDeletedDirectoryId() { return deletedDirectoryId; }
    public String getParentDirectoryId() { return parentDirectoryId; }
    public String getParentDirectoryPath() { return parentDirectoryPath; }
    public int getSubtreeNodeCount() { return subtreeNodeSnapshots.length; }
    public JournalNodeSnapshot getSubtreeNodeSnapshotAt(int index) { return subtreeNodeSnapshots[index]; }
    public JournalNodeSnapshot[] getSubtreeNodeSnapshotsSnapshot() { return subtreeNodeSnapshots.clone(); }
    public int getAssociatedBlockCount() { return associatedBlockSnapshots.length; }
    public JournalBlockSnapshot getAssociatedBlockSnapshotAt(int index) { return associatedBlockSnapshots[index]; }
    public JournalBlockSnapshot[] getAssociatedBlockSnapshotsSnapshot() { return associatedBlockSnapshots.clone(); }

    private static JournalNodeSnapshot[] copyAndValidateNodeSnapshots(
            JournalNodeSnapshot[] source,
            String deletedDirectoryId) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("subtreeNodeSnapshots cannot be null or empty");
        }
        JournalNodeSnapshot[] copy = source.clone();
        for (JournalNodeSnapshot snapshot : copy) {
            if (snapshot == null) {
                throw new IllegalArgumentException("subtreeNodeSnapshots cannot contain null entries");
            }
        }
        if (findDeletedRootSnapshot(deletedDirectoryId, copy) == null) {
            throw new IllegalArgumentException("subtree snapshots must include the deleted directory root");
        }
        return copy;
    }

    private static void validateRootParentConsistency(
            String deletedDirectoryId,
            String parentDirectoryId,
            String parentDirectoryPath,
            JournalNodeSnapshot[] subtreeNodeSnapshots) {
        JournalNodeSnapshot deletedRoot = findDeletedRootSnapshot(deletedDirectoryId, subtreeNodeSnapshots);
        if (!parentDirectoryId.equals(deletedRoot.getParentNodeId())) {
            throw new IllegalArgumentException("parentDirectoryId must match the deleted root parentNodeId");
        }
        if (!parentDirectoryPath.equals(JournalUndoData.deriveParentPath(deletedRoot.getNodePath()))) {
            throw new IllegalArgumentException("parentDirectoryPath must match the parent path derived from the deleted root snapshot");
        }
    }

    private static JournalBlockSnapshot[] copyAndValidateBlockSnapshots(
            JournalBlockSnapshot[] source,
            String deletedDirectoryId,
            JournalNodeSnapshot[] subtreeNodeSnapshots) {
        if (source == null) {
            throw new IllegalArgumentException("associatedBlockSnapshots cannot be null");
        }
        JournalBlockSnapshot[] copy = source.clone();
        JournalUndoValidation.validateDeletedDirectorySubtree(deletedDirectoryId, subtreeNodeSnapshots, copy);
        return copy;
    }

    private static JournalNodeSnapshot findDeletedRootSnapshot(
            String deletedDirectoryId,
            JournalNodeSnapshot[] subtreeNodeSnapshots) {
        for (JournalNodeSnapshot snapshot : subtreeNodeSnapshots) {
            if (snapshot.getNodeId().equals(deletedDirectoryId) && snapshot.getNodeType() == FsNodeType.DIRECTORY) {
                return snapshot;
            }
        }
        return null;
    }
}
