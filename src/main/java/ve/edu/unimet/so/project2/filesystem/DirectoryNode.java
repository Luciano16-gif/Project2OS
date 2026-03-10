package ve.edu.unimet.so.project2.filesystem;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public class DirectoryNode extends FsNode {

    private final SimpleList<FsNode> children;

    public DirectoryNode(String id, String name, String ownerUserId, AccessPermissions permissions) {
        this(id, name, ownerUserId, permissions, false);
    }

    private DirectoryNode(String id, String name, String ownerUserId, AccessPermissions permissions, boolean root) {
        super(id, name, ownerUserId, permissions, FsNodeType.DIRECTORY, root);
        this.children = new SimpleList<>();
    }

    public static DirectoryNode createRoot(String id, String ownerUserId, AccessPermissions permissions) {
        return new DirectoryNode(id, "/", ownerUserId, permissions, true);
    }

    public int getChildCount() {
        return children.size();
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }

    public FsNode getChildAt(int index) {
        return children.get(index);
    }

    public FsNode findChildByName(String childName) {
        requireNonBlank(childName, "childName");
        for (int i = 0; i < children.size(); i++) {
            FsNode child = children.get(i);
            if (child.getName().equals(childName)) {
                return child;
            }
        }
        return null;
    }

    public boolean hasChildNamed(String childName) {
        return findChildByName(childName) != null;
    }

    boolean hasChildNamedExcluding(String childName, FsNode excludedNode) {
        for (int i = 0; i < children.size(); i++) {
            FsNode child = children.get(i);
            if (child != excludedNode && child.getName().equals(childName)) {
                return true;
            }
        }
        return false;
    }

    public void addChild(FsNode child) {
        if (child == null) {
            throw new IllegalArgumentException("child cannot be null");
        }
        if (child == this) {
            throw new IllegalArgumentException("directory cannot be its own child");
        }
        if (child.isRoot()) {
            throw new IllegalArgumentException("root node cannot be added as a child");
        }
        if (child.getParent() != null) {
            throw new IllegalStateException("child is already attached to a parent");
        }
        if (hasChildNamed(child.getName())) {
            throw new IllegalArgumentException("duplicate child name under directory: " + child.getName());
        }
        if (wouldCreateCycle(child)) {
            throw new IllegalArgumentException("adding child would create a cycle");
        }

        child.attachToParent(this);
        children.add(child);
    }

    public boolean removeChild(FsNode child) {
        if (child == null) {
            return false;
        }

        boolean removed = children.removeFirst(child);
        if (removed) {
            child.detachFromParent(this);
        }
        return removed;
    }

    public Object[] getChildrenSnapshot() {
        return children.toArray();
    }

    public void forEachChild(SimpleList.Visitor<FsNode> visitor) {
        children.forEach(visitor);
    }

    private boolean wouldCreateCycle(FsNode child) {
        if (!(child instanceof DirectoryNode childDirectory)) {
            return false;
        }

        DirectoryNode current = this;
        while (current != null) {
            if (current == childDirectory) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
