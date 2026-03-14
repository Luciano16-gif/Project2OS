package ve.edu.unimet.so.project2.application;

import ve.edu.unimet.so.project2.datastructures.SimpleList;
import ve.edu.unimet.so.project2.filesystem.AccessPermissions;
import ve.edu.unimet.so.project2.filesystem.DirectoryNode;
import ve.edu.unimet.so.project2.filesystem.FileSystemCatalog;
import ve.edu.unimet.so.project2.filesystem.FileNode;
import ve.edu.unimet.so.project2.filesystem.FsNode;
import ve.edu.unimet.so.project2.session.Role;
import ve.edu.unimet.so.project2.session.SessionContext;
import ve.edu.unimet.so.project2.session.User;
import ve.edu.unimet.so.project2.session.UserStore;

public final class SimulationApplicationState {

    private final FileSystemCatalog fileSystemCatalog;
    private final UserStore userStore;
    private final SessionContext sessionContext;
    private final SimpleList<Integer> reservedBlockIndexes;

    private long nextNodeNumber;
    private long nextColorNumber;

    public SimulationApplicationState(
            FileSystemCatalog fileSystemCatalog,
            UserStore userStore,
            SessionContext sessionContext,
            long nextNodeNumber,
            long nextColorNumber) {
        if (fileSystemCatalog == null) {
            throw new IllegalArgumentException("fileSystemCatalog cannot be null");
        }
        if (userStore == null) {
            throw new IllegalArgumentException("userStore cannot be null");
        }
        if (sessionContext == null) {
            throw new IllegalArgumentException("sessionContext cannot be null");
        }
        if (nextNodeNumber <= 0) {
            throw new IllegalArgumentException("nextNodeNumber must be positive");
        }
        if (nextColorNumber <= 0) {
            throw new IllegalArgumentException("nextColorNumber must be positive");
        }
        this.fileSystemCatalog = fileSystemCatalog;
        this.userStore = userStore;
        this.sessionContext = sessionContext;
        this.reservedBlockIndexes = new SimpleList<>();
        this.nextNodeNumber = nextNodeNumber;
        this.nextColorNumber = nextColorNumber;
    }

    public FileSystemCatalog getFileSystemCatalog() {
        return fileSystemCatalog;
    }

    public UserStore getUserStore() {
        return userStore;
    }

    public SessionContext getSessionContext() {
        return sessionContext;
    }

    public SimulationApplicationState deepCopy() {
        UserStore copiedUserStore = copyUserStore(userStore);
        FileSystemCatalog copiedCatalog = new FileSystemCatalog(copyDirectorySubtree(fileSystemCatalog.getRoot()));
        SessionContext copiedSessionContext = new SessionContext(
                copiedUserStore.requireById(sessionContext.getCurrentUserId()));
        SimulationApplicationState copy = new SimulationApplicationState(
                copiedCatalog,
                copiedUserStore,
                copiedSessionContext,
                nextNodeNumber,
                nextColorNumber);
        return copy;
    }

    public String nextNodeId() {
        return "NODE-" + nextNodeNumber++;
    }

    public String nextColorId() {
        return "COLOR-" + nextColorNumber++;
    }

    public void reserveBlockIndexes(int[] blockIndexes) {
        if (blockIndexes == null || blockIndexes.length == 0) {
            throw new IllegalArgumentException("blockIndexes cannot be null or empty");
        }
        for (int blockIndex : blockIndexes) {
            if (blockIndex < 0) {
                throw new IllegalArgumentException("blockIndex cannot be negative");
            }
            if (isReservedBlock(blockIndex)) {
                throw new IllegalArgumentException("block is already reserved: " + blockIndex);
            }
        }
        for (int blockIndex : blockIndexes) {
            reservedBlockIndexes.add(blockIndex);
        }
    }

    public void releaseReservedBlockIndexes(int[] blockIndexes) {
        if (blockIndexes == null) {
            return;
        }
        for (int blockIndex : blockIndexes) {
            removeReservedBlock(blockIndex);
        }
    }

    public boolean isReservedBlock(int blockIndex) {
        for (int i = 0; i < reservedBlockIndexes.size(); i++) {
            if (reservedBlockIndexes.get(i) == blockIndex) {
                return true;
            }
        }
        return false;
    }

    public static SimulationApplicationState createDefault() {
        User admin = new User("admin", "Administrator", Role.ADMIN);
        User user1 = new User("user-1", "User One", Role.USER);
        User user2 = new User("user-2", "User Two", Role.USER);

        UserStore userStore = new UserStore();
        userStore.addUser(admin);
        userStore.addUser(user1);
        userStore.addUser(user2);

        DirectoryNode root = DirectoryNode.createRoot("root", admin.getUserId(), AccessPermissions.publicReadAccess());
        DirectoryNode homeUser1 = new DirectoryNode("NODE-1", "home-user-1", user1.getUserId(), AccessPermissions.privateAccess());
        DirectoryNode homeUser2 = new DirectoryNode("NODE-2", "home-user-2", user2.getUserId(), AccessPermissions.privateAccess());
        root.addChild(homeUser1);
        root.addChild(homeUser2);

        return new SimulationApplicationState(
                new FileSystemCatalog(root),
                userStore,
                new SessionContext(admin),
                3L,
                1L);
    }

    private void removeReservedBlock(int blockIndex) {
        for (int i = 0; i < reservedBlockIndexes.size(); i++) {
            if (reservedBlockIndexes.get(i) == blockIndex) {
                reservedBlockIndexes.removeAt(i);
                return;
            }
        }
    }

    private static UserStore copyUserStore(UserStore original) {
        UserStore copy = new UserStore();
        User[] users = original.getUsersSnapshot();
        for (User user : users) {
            copy.addUser(new User(user.getUserId(), user.getUsername(), user.getRole()));
        }
        return copy;
    }

    private static DirectoryNode copyDirectorySubtree(DirectoryNode original) {
        DirectoryNode copy = original.isRoot()
                ? DirectoryNode.createRoot(
                        original.getId(),
                        original.getOwnerUserId(),
                        copyPermissions(original.getPermissions()))
                : new DirectoryNode(
                        original.getId(),
                        original.getName(),
                        original.getOwnerUserId(),
                        copyPermissions(original.getPermissions()));

        Object[] children = original.getChildrenSnapshot();
        for (Object childObject : children) {
            FsNode child = (FsNode) childObject;
            copy.addChild(copyNode(child));
        }
        return copy;
    }

    private static FsNode copyNode(FsNode original) {
        if (original instanceof DirectoryNode directory) {
            return copyDirectorySubtree(directory);
        }

        FileNode file = (FileNode) original;
        return new ve.edu.unimet.so.project2.filesystem.FileNode(
                file.getId(),
                file.getName(),
                file.getOwnerUserId(),
                copyPermissions(file.getPermissions()),
                file.getSizeInBlocks(),
                file.getFirstBlockIndex(),
                file.getColorId(),
                file.isSystemFile());
    }

    private static AccessPermissions copyPermissions(AccessPermissions permissions) {
        return permissions.isPublicReadable()
                ? AccessPermissions.publicReadAccess()
                : AccessPermissions.privateAccess();
    }
}
