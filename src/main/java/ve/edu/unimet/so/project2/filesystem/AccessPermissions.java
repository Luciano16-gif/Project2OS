package ve.edu.unimet.so.project2.filesystem;

public record AccessPermissions(boolean publicReadable) {

    public boolean isPublicReadable() { return publicReadable; }
    public static AccessPermissions privateAccess() { return new AccessPermissions(false); }
    public static AccessPermissions publicReadAccess() { return new AccessPermissions(true); }
}
