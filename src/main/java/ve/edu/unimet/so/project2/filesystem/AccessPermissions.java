package ve.edu.unimet.so.project2.filesystem;

public final class AccessPermissions {

    private final boolean publicReadable;

    public AccessPermissions(boolean publicReadable) {
        this.publicReadable = publicReadable;
    }

    public boolean isPublicReadable() {
        return publicReadable;
    }

    public static AccessPermissions privateAccess() {
        return new AccessPermissions(false);
    }

    public static AccessPermissions publicReadAccess() {
        return new AccessPermissions(true);
    }
}
