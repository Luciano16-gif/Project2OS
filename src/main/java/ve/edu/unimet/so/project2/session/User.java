package ve.edu.unimet.so.project2.session;

public final class User {

    private final String userId;
    private final String username;
    private final Role role;

    public User(String userId, String username, Role role) {
        this.userId = requireNonBlank(userId, "userId");
        this.username = requireNonBlank(username, "username");
        if (role == null) {
            throw new IllegalArgumentException("role cannot be null");
        }
        this.role = role;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public Role getRole() {
        return role;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
