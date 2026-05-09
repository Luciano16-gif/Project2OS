package ve.edu.unimet.so.project2.session;

public final class SessionContext {

    private User currentUser;

    public SessionContext(User currentUser) {
        this.currentUser = requireUser(currentUser, "currentUser");
    }

    public User getCurrentUser() { return currentUser; }
    public String getCurrentUserId() { return currentUser.getUserId(); }
    public Role getCurrentRole() { return currentUser.getRole(); }

    public void switchTo(User user) {
        currentUser = requireUser(user, "user");
    }

    private static User requireUser(User user, String fieldName) {
        if (user == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        return user;
    }
}
