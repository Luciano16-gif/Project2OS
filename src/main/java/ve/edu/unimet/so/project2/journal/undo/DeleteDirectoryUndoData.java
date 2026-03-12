package ve.edu.unimet.so.project2.journal.undo;

import ve.edu.unimet.so.project2.filesystem.FsNodeType;

public final class DeleteDirectoryUndoData implements JournalUndoData {

    private final String deletedDirectoryId;
    private final String parentDirectoryId;
    private final String parentDirectoryPath;
    private final JournalNodeSnapshot[] subtreeNodeSnapshots;
    private final JournalBlockSnapshot[] associatedBlockSnapshots;

    public DeleteDirectoryUndoData(
            String deletedDirectoryId,
            String parentDirectoryId,
            String parentDirectoryPath,
            JournalNodeSnapshot[] subtreeNodeSnapshots,
            JournalBlockSnapshot[] associatedBlockSnapshots) {
        this.deletedDirectoryId = requireNonBlank(deletedDirectoryId, "deletedDirectoryId");
        this.parentDirectoryId = requireNonBlank(parentDirectoryId, "parentDirectoryId");
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        this.subtreeNodeSnapshots = copyAndValidateNodeSnapshots(subtreeNodeSnapshots, this.deletedDirectoryId);
        validateRootParentConsistency(this.deletedDirectoryId, this.parentDirectoryId, this.parentDirectoryPath, this.subtreeNodeSnapshots);
        this.associatedBlockSnapshots = copyAndValidateBlockSnapshots(
                associatedBlockSnapshots,
                this.deletedDirectoryId,
                this.subtreeNodeSnapshots);
    }

    public String getDeletedDirectoryId() {
        return deletedDirectoryId;
    }

    public String getParentDirectoryId() {
        return parentDirectoryId;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    public int getSubtreeNodeCount() {
        return subtreeNodeSnapshots.length;
    }

    public JournalNodeSnapshot getSubtreeNodeSnapshotAt(int index) {
        return subtreeNodeSnapshots[index];
    }

    public JournalNodeSnapshot[] getSubtreeNodeSnapshotsSnapshot() {
        return subtreeNodeSnapshots.clone();
    }

    public int getAssociatedBlockCount() {
        return associatedBlockSnapshots.length;
    }

    public JournalBlockSnapshot getAssociatedBlockSnapshotAt(int index) {
        return associatedBlockSnapshots[index];
    }

    public JournalBlockSnapshot[] getAssociatedBlockSnapshotsSnapshot() {
        return associatedBlockSnapshots.clone();
    }

    private static JournalNodeSnapshot[] copyAndValidateNodeSnapshots(
            JournalNodeSnapshot[] source,
            String deletedDirectoryId) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("subtreeNodeSnapshots cannot be null or empty");
        }
        JournalNodeSnapshot[] copy = source.clone();
        boolean containsDeletedDirectory = false;
        for (JournalNodeSnapshot snapshot : copy) {
            if (snapshot == null) {
                throw new IllegalArgumentException("subtreeNodeSnapshots cannot contain null entries");
            }
            if (snapshot.getNodeId().equals(deletedDirectoryId)
                    && snapshot.getNodeType() == FsNodeType.DIRECTORY) {
                containsDeletedDirectory = true;
            }
        }
        if (!containsDeletedDirectory) {
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

        String expectedParentPath = deriveParentPath(deletedRoot.getNodePath());
        if (!parentDirectoryPath.equals(expectedParentPath)) {
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
            if (snapshot != null
                    && snapshot.getNodeId().equals(deletedDirectoryId)
                    && snapshot.isDirectory()) {
                return snapshot;
            }
        }
        throw new IllegalArgumentException("subtree snapshots must include the deleted directory root");
    }

    private static String deriveParentPath(String nodePath) {
        int lastSlash = nodePath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return nodePath.substring(0, lastSlash);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
