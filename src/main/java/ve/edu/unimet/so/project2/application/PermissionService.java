package ve.edu.unimet.so.project2.application;

import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.session.Role;
import ve.edu.unimet.so.project2.session.User;

public final class PermissionService {

    public void ensureCanCreate(User actor, DirectoryNode parentDirectory, boolean systemFile) {
        ensureActor(actor);
        if (parentDirectory == null) {
            throw new IllegalArgumentException("parentDirectory cannot be null");
        }
    }

    public void ensureCanRead(User actor, FsNode targetNode) {
        ensureActor(actor);
        if (targetNode == null) {
            throw new IllegalArgumentException("targetNode cannot be null");
        }
    }

    public void ensureCanModify(User actor, FsNode targetNode) {
        ensureActor(actor);
        if (targetNode == null) {
            throw new IllegalArgumentException("targetNode cannot be null");
        }
        if (actor.getRole() == Role.ADMIN) {
            return;
        }
        if (targetNode instanceof FileNode file
                && !actor.getUserId().equals(file.getOwnerUserId())) {
            throw new IllegalArgumentException("current user cannot modify this resource");
        }
    }

    private void ensureActor(User actor) {
        if (actor == null) {
            throw new IllegalArgumentException("actor cannot be null");
        }
    }
}
