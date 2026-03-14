package ve.edu.unimet.so.project2.application;

public final class CreateFileIntent implements ApplicationOperationIntent {

    private final String parentDirectoryPath;
    private final String fileName;
    private final int sizeInBlocks;
    private final boolean publicReadable;
    private final boolean systemFile;

    public CreateFileIntent(
            String parentDirectoryPath,
            String fileName,
            int sizeInBlocks,
            boolean publicReadable,
            boolean systemFile) {
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        this.fileName = requireNonBlank(fileName, "fileName");
        if (sizeInBlocks <= 0) {
            throw new IllegalArgumentException("sizeInBlocks must be greater than zero");
        }
        this.sizeInBlocks = sizeInBlocks;
        this.publicReadable = publicReadable;
        this.systemFile = systemFile;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    public String getFileName() {
        return fileName;
    }

    public int getSizeInBlocks() {
        return sizeInBlocks;
    }

    public boolean isPublicReadable() {
        return publicReadable;
    }

    public boolean isSystemFile() {
        return systemFile;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
