package ve.edu.unimet.so.project2.application;

import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.session.Role;
import ve.edu.unimet.so.project2.session.User;

public final class PermissionService {

    public void ensureCanCreate(User actor, DirectoryNode parentDirectory, boolean systemFile) {
        ensureActor(actor);
        if (actor.getRole() == Role.ADMIN) {
            return;
        }
        if (systemFile) {
            throw new IllegalArgumentException("only ADMIN can create system files");
        }
        if (parentDirectory.isRoot()) {
            throw new IllegalArgumentException("USER cannot create directly under root");
        }
        if (!actor.getUserId().equals(parentDirectory.getOwnerUserId())) {
            throw new IllegalArgumentException("USER can only create under owned directories");
        }
    }

    public void ensureCanRead(User actor, FsNode targetNode) {
        ensureActor(actor);
        if (actor.getRole() == Role.ADMIN) {
            return;
        }
        if (actor.getUserId().equals(targetNode.getOwnerUserId())) {
            return;
        }
        if (!targetNode.getPermissions().isPublicReadable()) {
            throw new IllegalArgumentException("current user cannot read this resource");
        }
    }

    public void ensureCanModify(User actor, FsNode targetNode) {
        ensureActor(actor);
        if (actor.getRole() == Role.ADMIN) {
            return;
        }
        if (targetNode instanceof FileNode file && file.isSystemFile()) {
            throw new IllegalArgumentException("USER cannot modify system files");
        }
        if (!actor.getUserId().equals(targetNode.getOwnerUserId())) {
            throw new IllegalArgumentException("current user cannot modify this resource");
        }
    }

    private void ensureActor(User actor) {
        if (actor == null) {
            throw new IllegalArgumentException("actor cannot be null");
        }
    }
}
