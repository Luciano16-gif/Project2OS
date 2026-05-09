package ve.edu.unimet.so.project2.application;

public record RenameIntent(String targetPath, String newName) implements ApplicationOperationIntent {

    public RenameIntent {
        targetPath = ApplicationOperationIntent.requireNonBlank(targetPath, "targetPath");
        newName = ApplicationOperationIntent.requireNonBlank(newName, "newName");
    }

    public String getTargetPath() { return targetPath; }
    public String getNewName() { return newName; }
}
