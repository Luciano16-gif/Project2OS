package ve.edu.unimet.so.project2.journal.undo;

public record DeleteFileUndoData(
        JournalNodeSnapshot deletedFileSnapshot,
        String parentDirectoryId,
        String parentDirectoryPath,
        JournalBlockSnapshot[] blockChainSnapshots) implements JournalUndoData {

    public DeleteFileUndoData {
        if (deletedFileSnapshot == null) {
            throw new IllegalArgumentException("deletedFileSnapshot cannot be null");
        }
        if (!deletedFileSnapshot.isFile()) {
            throw new IllegalArgumentException("deletedFileSnapshot must represent a file");
        }
        parentDirectoryId = JournalUndoData.requireNonBlank(parentDirectoryId, "parentDirectoryId");
        parentDirectoryPath = JournalUndoData.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        validateParentConsistency(deletedFileSnapshot, parentDirectoryId, parentDirectoryPath);
        blockChainSnapshots = copyAndValidateBlockSnapshots(blockChainSnapshots, deletedFileSnapshot);
    }

    public JournalNodeSnapshot getDeletedFileSnapshot() { return deletedFileSnapshot; }
    public String getParentDirectoryId() { return parentDirectoryId; }
    public String getParentDirectoryPath() { return parentDirectoryPath; }
    public int getBlockChainLength() { return blockChainSnapshots.length; }
    public JournalBlockSnapshot getBlockSnapshotAt(int index) { return blockChainSnapshots[index]; }
    public JournalBlockSnapshot[] getBlockChainSnapshotsSnapshot() { return blockChainSnapshots.clone(); }

    private static JournalBlockSnapshot[] copyAndValidateBlockSnapshots(
            JournalBlockSnapshot[] source,
            JournalNodeSnapshot deletedFileSnapshot) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("blockChainSnapshots cannot be null or empty");
        }
        JournalBlockSnapshot[] copy = source.clone();
        JournalUndoValidation.validateDeletedFileChain(deletedFileSnapshot, copy);
        return copy;
    }

    private static void validateParentConsistency(
            JournalNodeSnapshot deletedFileSnapshot,
            String parentDirectoryId,
            String parentDirectoryPath) {
        if (!parentDirectoryId.equals(deletedFileSnapshot.getParentNodeId())) {
            throw new IllegalArgumentException("parentDirectoryId must match deletedFileSnapshot parentNodeId");
        }
        if (!parentDirectoryPath.equals(JournalUndoData.deriveParentPath(deletedFileSnapshot.getNodePath()))) {
            throw new IllegalArgumentException("parentDirectoryPath must match the parent path derived from deletedFileSnapshot");
        }
    }
}
