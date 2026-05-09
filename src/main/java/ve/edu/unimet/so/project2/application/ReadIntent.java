package ve.edu.unimet.so.project2.application;

public record ReadIntent(String targetPath) implements ApplicationOperationIntent {

    public ReadIntent {
        targetPath = ApplicationOperationIntent.requireNonBlank(targetPath, "targetPath");
    }

    public String getTargetPath() { return targetPath; }
}
