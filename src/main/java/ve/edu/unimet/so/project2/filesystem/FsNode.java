package ve.edu.unimet.so.project2.filesystem;

public abstract class FsNode {

    private final String id;
    private String name;
    private final String ownerUserId;
    private final AccessPermissions permissions;
    private final FsNodeType type;
    private final boolean root;
    private DirectoryNode parent;

    protected FsNode(
            String id,
            String name,
            String ownerUserId,
            AccessPermissions permissions,
            FsNodeType type,
            boolean root) {
        this.id = requireNonBlank(id, "id");
        this.ownerUserId = requireNonBlank(ownerUserId, "ownerUserId");
        if (permissions == null) {
            throw new IllegalArgumentException("permissions cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }

        if (root && type != FsNodeType.DIRECTORY) {
            throw new IllegalArgumentException("root node must be a directory");
        }

        if (root) {
            validateRootName(name);
        } else {
            validateRegularNodeName(name);
        }

        this.name = name;
        this.permissions = permissions;
        this.type = type;
        this.root = root;
        this.parent = null;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public AccessPermissions getPermissions() {
        return permissions;
    }

    public FsNodeType getType() {
        return type;
    }

    public DirectoryNode getParent() {
        return parent;
    }

    public boolean isRoot() {
        return root;
    }

    public boolean isAttached() {
        return root || parent != null;
    }

    public String getPath() {
        if (root) {
            return "/";
        }
        if (parent == null) {
            throw new IllegalStateException("detached nodes do not have a filesystem path");
        }

        String parentPath = parent.getPath();
        if ("/".equals(parentPath)) {
            return parentPath + name;
        }
        return parentPath + "/" + name;
    }

    public void rename(String newName) {
        if (root) {
            throw new IllegalStateException("root node cannot be renamed");
        }

        validateRegularNodeName(newName);

        if (parent != null && parent.hasChildNamedExcluding(newName, this)) {
            throw new IllegalArgumentException("duplicate child name under parent: " + newName);
        }

        this.name = newName;
    }

    void attachToParent(DirectoryNode newParent) {
        if (root) {
            throw new IllegalStateException("root node cannot have a parent");
        }
        if (newParent == null) {
            throw new IllegalArgumentException("newParent cannot be null");
        }
        if (parent != null) {
            throw new IllegalStateException("node is already attached to a parent");
        }
        this.parent = newParent;
    }

    void detachFromParent(DirectoryNode expectedParent) {
        if (parent != expectedParent) {
            throw new IllegalStateException("node is not attached to the expected parent");
        }
        this.parent = null;
    }

    private static void validateRootName(String candidate) {
        if (!"/".equals(candidate)) {
            throw new IllegalArgumentException("root node name must be /");
        }
    }

    private static void validateRegularNodeName(String candidate) {
        requireNonBlank(candidate, "name");
        if (candidate.contains("/")) {
            throw new IllegalArgumentException("name cannot contain /");
        }
        if (".".equals(candidate) || "..".equals(candidate)) {
            throw new IllegalArgumentException("name cannot be . or ..");
        }
    }

    protected static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
