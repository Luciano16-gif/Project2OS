package ve.edu.unimet.so.project2.application;

public record CreateFileIntent(
        String parentDirectoryPath,
        String fileName,
        int sizeInBlocks,
        boolean publicReadable,
        boolean systemFile) implements ApplicationOperationIntent {

    public CreateFileIntent {
        parentDirectoryPath = ApplicationOperationIntent.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        fileName = ApplicationOperationIntent.requireNonBlank(fileName, "fileName");
        if (sizeInBlocks <= 0) {
            throw new IllegalArgumentException("sizeInBlocks must be greater than zero");
        }
    }

    public String getParentDirectoryPath() { return parentDirectoryPath; }
    public String getFileName() { return fileName; }
    public int getSizeInBlocks() { return sizeInBlocks; }
    public boolean isPublicReadable() { return publicReadable; }
    public boolean isSystemFile() { return systemFile; }
}
