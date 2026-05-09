package ve.edu.unimet.so.project2.application;

public record CreateDirectoryIntent(
        String parentDirectoryPath,
        String directoryName,
        boolean publicReadable) implements ApplicationOperationIntent {

    public CreateDirectoryIntent {
        parentDirectoryPath = ApplicationOperationIntent.requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        directoryName = ApplicationOperationIntent.requireNonBlank(directoryName, "directoryName");
    }

    public String getParentDirectoryPath() { return parentDirectoryPath; }
    public String getDirectoryName() { return directoryName; }
    public boolean isPublicReadable() { return publicReadable; }
}
