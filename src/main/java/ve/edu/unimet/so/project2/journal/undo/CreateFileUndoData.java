package ve.edu.unimet.so.project2.journal.undo;

public final class CreateFileUndoData implements JournalUndoData {

    private final String createdFileId;
    private final String parentDirectoryId;
    private final String parentDirectoryPath;
    private final int[] allocatedBlockIndexes;

    public CreateFileUndoData(
            String createdFileId,
            String parentDirectoryId,
            String parentDirectoryPath,
            int[] allocatedBlockIndexes) {
        this.createdFileId = requireNonBlank(createdFileId, "createdFileId");
        this.parentDirectoryId = requireNonBlank(parentDirectoryId, "parentDirectoryId");
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        this.allocatedBlockIndexes = copyAndValidateBlockIndexes(allocatedBlockIndexes);
    }

    public String getCreatedFileId() {
        return createdFileId;
    }

    public String getParentDirectoryId() {
        return parentDirectoryId;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    public int getAllocatedBlockCount() {
        return allocatedBlockIndexes.length;
    }

    public int getAllocatedBlockIndexAt(int index) {
        return allocatedBlockIndexes[index];
    }

    public int[] getAllocatedBlockIndexesSnapshot() {
        return allocatedBlockIndexes.clone();
    }

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

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
