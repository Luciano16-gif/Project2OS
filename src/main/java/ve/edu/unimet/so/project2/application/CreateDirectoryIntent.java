package ve.edu.unimet.so.project2.application;

public final class CreateDirectoryIntent implements ApplicationOperationIntent {

    private final String parentDirectoryPath;
    private final String directoryName;
    private final boolean publicReadable;

    public CreateDirectoryIntent(String parentDirectoryPath, String directoryName, boolean publicReadable) {
        this.parentDirectoryPath = requireNonBlank(parentDirectoryPath, "parentDirectoryPath");
        this.directoryName = requireNonBlank(directoryName, "directoryName");
        this.publicReadable = publicReadable;
    }

    public String getParentDirectoryPath() {
        return parentDirectoryPath;
    }

    public String getDirectoryName() {
        return directoryName;
    }

    public boolean isPublicReadable() {
        return publicReadable;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
