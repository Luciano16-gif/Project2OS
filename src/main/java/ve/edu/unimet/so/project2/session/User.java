package ve.edu.unimet.so.project2.session;

public record User(String userId, String username, Role role) {

    public User {
        userId = requireNonBlank(userId, "userId");
        username = requireNonBlank(username, "username");
        if (role == null) {
            throw new IllegalArgumentException("role cannot be null");
        }
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
