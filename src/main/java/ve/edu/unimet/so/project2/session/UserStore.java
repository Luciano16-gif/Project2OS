package ve.edu.unimet.so.project2.session;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public final class UserStore {

    private final SimpleList<User> users;

    public UserStore() {
        this.users = new SimpleList<>();
    }

    public void addUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user cannot be null");
        }
        if (findById(user.getUserId()) != null) {
            throw new IllegalArgumentException("duplicate userId: " + user.getUserId());
        }
        users.add(user);
    }

    public User findById(String userId) {
        requireNonBlank(userId, "userId");
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (user.getUserId().equals(userId)) {
                return user;
            }
        }
        return null;
    }

    public User requireById(String userId) {
        User user = findById(userId);
        if (user == null) {
            throw new IllegalArgumentException("user not found: " + userId);
        }
        return user;
    }

    public User[] getUsersSnapshot() {
        User[] snapshot = new User[users.size()];
        for (int i = 0; i < users.size(); i++) {
            snapshot[i] = users.get(i);
        }
        return snapshot;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
