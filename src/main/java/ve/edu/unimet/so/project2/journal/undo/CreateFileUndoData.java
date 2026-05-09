package ve.edu.unimet.so.project2.journal.undo;

public record CreateFileUndoData(
        String createdFileId,
        String parentDirectoryId,
        String parentDirectoryPath,
        int[] allocatedBlockIndexes) implements JournalUndoData {

    public CreateFileUndoData {
        createdFileId = JournalUndoData.requireNonBlank(createdFileId, "createdFileId");
        parentDirectoryId = JournalUndoData.requireNonBlank(parentDirectoryId, "parentDirectoryId");
        parentDirectoryPath = JournalUndoData.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        allocatedBlockIndexes = copyAndValidateBlockIndexes(allocatedBlockIndexes);
    }

    public String getCreatedFileId() { return createdFileId; }
    public String getParentDirectoryId() { return parentDirectoryId; }
    public String getParentDirectoryPath() { return parentDirectoryPath; }
    public int getAllocatedBlockCount() { return allocatedBlockIndexes.length; }
    public int getAllocatedBlockIndexAt(int index) { return allocatedBlockIndexes[index]; }
    public int[] getAllocatedBlockIndexesSnapshot() { return allocatedBlockIndexes.clone(); }

    private static int[] copyAndValidateBlockIndexes(int[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("allocatedBlockIndexes cannot be null or empty");
        }
        int[] copy = source.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException("allocated block indexes cannot be negative");
            }
            for (int j = i + 1; j < copy.length; j++) {
                if (copy[i] == copy[j]) {
                    throw new IllegalArgumentException("allocatedBlockIndexes cannot repeat block ids");
                }
            }
        }
        return copy;
    }
}
