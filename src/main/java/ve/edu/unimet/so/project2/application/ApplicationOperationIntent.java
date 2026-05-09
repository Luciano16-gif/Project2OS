package ve.edu.unimet.so.project2.application;

public interface ApplicationOperationIntent {

    static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
