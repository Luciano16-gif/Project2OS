package ve.edu.unimet.so.project2.journal.undo;

public interface JournalUndoData {

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }

    static String deriveParentPath(String nodePath) {
        int lastSlash = nodePath.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : nodePath.substring(0, lastSlash);
    }
}
