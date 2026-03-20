package ve.edu.unimet.so.project2.application;

public final class DeleteIntent implements ApplicationOperationIntent {

    private final String targetPath;

    public DeleteIntent(String targetPath) {
        this.targetPath = requireNonBlank(targetPath, "targetPath");
    }

    public String getTargetPath() {
        return targetPath;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
