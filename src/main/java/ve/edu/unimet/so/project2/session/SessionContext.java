package ve.edu.unimet.so.project2.session;

public final class SessionContext {

    private User currentUser;

    public SessionContext(User currentUser) {
        if (currentUser == null) {
            throw new IllegalArgumentException("currentUser cannot be null");
        }
        this.currentUser = currentUser;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public String getCurrentUserId() {
        return currentUser.getUserId();
    }

    public Role getCurrentRole() {
        return currentUser.getRole();
    }

    public void switchTo(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user cannot be null");
        }
        this.currentUser = user;
    }
}
