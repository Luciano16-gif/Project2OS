package ve.edu.unimet.so.project2.filesystem;

import ve.edu.unimet.so.project2.datastructures.SimpleList;

public final class FileSystemCatalog {

    private final DirectoryNode root;
    private final SimpleList<FsNode> nodesById;

    public FileSystemCatalog(DirectoryNode root) {
        if (root == null) {
            throw new IllegalArgumentException("root cannot be null");
        }
        if (!root.isRoot()) {
            throw new IllegalArgumentException("root must be a root directory");
        }
        this.root = root;
        this.nodesById = new SimpleList<>();
        registerSubtree(root);
    }

    public DirectoryNode getRoot() {
        return root;
    }

    public FsNode findById(String nodeId) {
        requireNonBlank(nodeId, "nodeId");
        for (int i = 0; i < nodesById.size(); i++) {
            FsNode node = nodesById.get(i);
            if (node.getId().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    public FsNode requireById(String nodeId) {
        FsNode node = findById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("node not found: " + nodeId);
        }
        return node;
    }

    public FsNode findByPath(String path) {
        String canonicalPath = canonicalizePath(path);
        if ("/".equals(canonicalPath)) {
            return root;
        }

        String[] segments = canonicalPath.substring(1).split("/");
        FsNode current = root;
        for (String segment : segments) {
            if (!(current instanceof DirectoryNode directory)) {
                return null;
            }
            current = directory.findChildByName(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public FsNode requireByPath(String path) {
        FsNode node = findByPath(path);
        if (node == null) {
            throw new IllegalArgumentException("node not found at path: " + path);
        }
        return node;
    }

    public DirectoryNode requireDirectoryByPath(String path) {
        FsNode node = requireByPath(path);
        if (!(node instanceof DirectoryNode directory)) {
            throw new IllegalArgumentException("path does not reference a directory: " + path);
        }
        return directory;
    }

    public FileNode requireFileByPath(String path) {
        FsNode node = requireByPath(path);
        if (!(node instanceof FileNode file)) {
            throw new IllegalArgumentException("path does not reference a file: " + path);
        }
        return file;
    }

    public DirectoryNode requireDirectoryById(String nodeId) {
        FsNode node = requireById(nodeId);
        if (!(node instanceof DirectoryNode directory)) {
            throw new IllegalArgumentException("node is not a directory: " + nodeId);
        }
        return directory;
    }

    public FileNode requireFileById(String nodeId) {
        FsNode node = requireById(nodeId);
        if (!(node instanceof FileNode file)) {
            throw new IllegalArgumentException("node is not a file: " + nodeId);
        }
        return file;
    }

    public void addNode(String parentDirectoryId, FsNode node) {
        DirectoryNode parent = requireDirectoryById(parentDirectoryId);
        addNode(parent, node);
    }

    public void addNode(DirectoryNode parent, FsNode node) {
        if (parent == null) {
            throw new IllegalArgumentException("parent cannot be null");
        }
        if (node == null) {
            throw new IllegalArgumentException("node cannot be null");
        }
        if (findById(node.getId()) != null) {
            throw new IllegalArgumentException("duplicate node id: " + node.getId());
        }
        parent.addChild(node);
        registerSubtree(node);
    }

    public void removeNode(String nodeId) {
        removeNode(requireById(nodeId));
    }

    public void removeNode(FsNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node cannot be null");
        }
        if (node.isRoot()) {
            throw new IllegalArgumentException("root cannot be removed");
        }
        DirectoryNode parent = node.getParent();
        if (parent == null) {
            throw new IllegalStateException("node is detached from filesystem");
        }
        parent.removeChild(node);
        unregisterSubtree(node);
    }

    public FsNode[] getAllNodesSnapshot() {
        FsNode[] snapshot = new FsNode[nodesById.size()];
        for (int i = 0; i < nodesById.size(); i++) {
            snapshot[i] = nodesById.get(i);
        }
        return snapshot;
    }

    public FsNode[] getSubtreeSnapshot(String nodeId) {
        return getSubtreeSnapshot(requireById(nodeId));
    }

    public FsNode[] getSubtreeSnapshot(FsNode node) {
        SimpleList<FsNode> collected = new SimpleList<>();
        collectSubtree(node, collected);
        FsNode[] snapshot = new FsNode[collected.size()];
        for (int i = 0; i < collected.size(); i++) {
            snapshot[i] = collected.get(i);
        }
        return snapshot;
    }

    public static String canonicalizePath(String path) {
        requireNonBlank(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        if ("/".equals(path)) {
            return "/";
        }
        if (path.endsWith("/")) {
            throw new IllegalArgumentException("non-root paths must not end with /");
        }
        if (path.contains("//")) {
            throw new IllegalArgumentException("path must not contain empty segments");
        }
        if (path.contains("/./") || path.endsWith("/.") || path.contains("/../") || path.endsWith("/..")) {
            throw new IllegalArgumentException("path must not contain . or .. segments");
        }
        return path;
    }

    public static String buildChildPath(String parentPath, String childName) {
        canonicalizePath(parentPath);
        requireNonBlank(childName, "childName");
        if ("/".equals(parentPath)) {
            return "/" + childName;
        }
        return parentPath + "/" + childName;
    }

    private void registerSubtree(FsNode node) {
        nodesById.add(node);
        if (node instanceof DirectoryNode directory) {
            Object[] children = directory.getChildrenSnapshot();
            for (Object child : children) {
                registerSubtree((FsNode) child);
            }
        }
    }

    private void unregisterSubtree(FsNode node) {
        if (node instanceof DirectoryNode directory) {
            Object[] children = directory.getChildrenSnapshot();
            for (Object child : children) {
                unregisterSubtree((FsNode) child);
            }
        }
        removeFromIndex(node);
    }

    private void removeFromIndex(FsNode node) {
        for (int i = 0; i < nodesById.size(); i++) {
            if (nodesById.get(i) == node) {
                nodesById.removeAt(i);
                return;
            }
        }
        throw new IllegalStateException("node is not registered in index: " + node.getId());
    }

    private void collectSubtree(FsNode node, SimpleList<FsNode> collected) {
        collected.add(node);
        if (node instanceof DirectoryNode directory) {
            Object[] children = directory.getChildrenSnapshot();
            for (Object child : children) {
                collectSubtree((FsNode) child, collected);
            }
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value;
    }
}
