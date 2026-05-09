package ve.edu.unimet.so.project2.application;

public record DeleteIntent(String targetPath) implements ApplicationOperationIntent {

    public DeleteIntent {
        targetPath = ApplicationOperationIntent.requireNonBlank(targetPath, "targetPath");
    }

    public String getTargetPath() { return targetPath; }
}
