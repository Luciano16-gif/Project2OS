package ve.edu.unimet.so.project2.application;

public final class RenameIntent implements ApplicationOperationIntent {

    private final String targetPath;
    private final String newName;

    public RenameIntent(String targetPath, String newName) {
        this.targetPath = requireNonBlank(targetPath, "targetPath");
        this.newName = requireNonBlank(newName, "newName");
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getNewName() {
        return newName;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
