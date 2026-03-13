package ve.edu.unimet.so.project2.journal.undo;

public final class DeleteFileUndoData implements JournalUndoData {

    private final JournalNodeSnapshot deletedFileSnapshot;
    private final String parentDirectoryId;
    private final String parentDirectoryPath;
    private final JournalBlockSnapshot[] blockChainSnapshots;

    public DeleteFileUndoData(
            JournalNodeSnapshot deletedFileSnapshot,
            String parentDirectoryId,
            String parentDirectoryPath,
            JournalBlockSnapshot[] blockChainSnapshots) {
        if (deletedFileSnapshot == null) {
            throw new IllegalArgumentException("deletedFileSnapshot cannot be null");
        }
        if (!deletedFileSnapshot.isFile()) {
            throw new IllegalArgumentException("deletedFileSnapshot must represent a file");
        }
        this.parentDirectoryId = requireNonBlank(parentDirectoryId, "parentDirectoryId");
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        validateParentConsistency(deletedFileSnapshot, this.parentDirectoryId, this.parentDirectoryPath);
        this.blockChainSnapshots = copyAndValidateBlockSnapshots(blockChainSnapshots, deletedFileSnapshot);
        this.deletedFileSnapshot = deletedFileSnapshot;
    }

    public JournalNodeSnapshot getDeletedFileSnapshot() {
        return deletedFileSnapshot;
    }

    public String getParentDirectoryId() {
        return parentDirectoryId;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    public int getBlockChainLength() {
        return blockChainSnapshots.length;
    }

    public JournalBlockSnapshot getBlockSnapshotAt(int index) {
        return blockChainSnapshots[index];
    }

    public JournalBlockSnapshot[] getBlockChainSnapshotsSnapshot() {
        return blockChainSnapshots.clone();
    }

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

        String expectedParentPath = deriveParentPath(deletedFileSnapshot.getNodePath());
        if (!parentDirectoryPath.equals(expectedParentPath)) {
            throw new IllegalArgumentException("parentDirectoryPath must match the parent path derived from deletedFileSnapshot");
        }
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
