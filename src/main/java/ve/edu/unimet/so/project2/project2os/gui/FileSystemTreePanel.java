package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.FileSystemNodeSummary;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Enumeration;

public class FileSystemTreePanel extends JPanel {

    private final JTree tree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode rootNode;
    private final Map<String, DefaultMutableTreeNode> nodeMap;

    public FileSystemTreePanel() {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        rootNode = new DefaultMutableTreeNode(new FsNodeData("root", "/ (Cargando...)", true, null));
        treeModel = new DefaultTreeModel(rootNode, true);
        nodeMap = new HashMap<>();

        tree = new JTree(treeModel);
        tree.setBackground(DarkTheme.BG_PANEL);
        tree.setForeground(DarkTheme.FG_PRIMARY);
        tree.setCellRenderer(new DarkTreeCellRenderer());
        tree.setShowsRootHandles(true);

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(DarkTheme.BG_PANEL);
        
        add(scrollPane, BorderLayout.CENTER);
    }

    public String getSelectedNodePath() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        DefaultMutableTreeNode selected = (DefaultMutableTreeNode) path.getLastPathComponent();
        FsNodeData data = (FsNodeData) selected.getUserObject();
        return data.path;
    }

    public void updateFromSnapshot(FileSystemNodeSummary[] nodes) {
        if (nodes == null || nodes.length == 0) return;

        Set<String> expandedIds = new HashSet<>();
        Enumeration<TreePath> expandedPaths = tree.getExpandedDescendants(new TreePath(rootNode));
        if (expandedPaths != null) {
            while (expandedPaths.hasMoreElements()) {
                TreePath path = expandedPaths.nextElement();
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) path.getLastPathComponent();
                FsNodeData data = (FsNodeData) n.getUserObject();
                if (data != null) expandedIds.add(data.id);
            }
        }

        String selectedId = null;
        TreePath selectedPathOrig = tree.getSelectionPath();
        if (selectedPathOrig != null) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) selectedPathOrig.getLastPathComponent();
            FsNodeData data = (FsNodeData) n.getUserObject();
            if (data != null) selectedId = data.id;
        }

        nodeMap.clear();
        rootNode.removeAllChildren();
        
        FileSystemNodeSummary rootSummary = null;
        for (FileSystemNodeSummary node : nodes) {
            if (node.isRoot()) {
                rootSummary = node;
                break;
            }
        }
        
        if (rootSummary != null) {
            rootNode.setUserObject(new FsNodeData(rootSummary.getNodeId(), rootSummary.getName(), true, rootSummary.getPath()));
            nodeMap.put(rootSummary.getNodeId(), rootNode);
            
            for (FileSystemNodeSummary node : nodes) {
                if (node.isRoot()) continue;
                boolean isDir = !node.getType().name().equals("FILE");
                String display = node.getName() + (!isDir ? " (" + node.getSizeInBlocks() + " blks)" : "");
                DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(
                        new FsNodeData(node.getNodeId(), display, isDir, node.getPath()), 
                        isDir
                );
                nodeMap.put(node.getNodeId(), treeNode);
            }
            
            for (FileSystemNodeSummary node : nodes) {
                if (node.isRoot()) continue;
                DefaultMutableTreeNode treeNode = nodeMap.get(node.getNodeId());
                DefaultMutableTreeNode parentNode = nodeMap.get(node.getParentNodeId());
                if (parentNode != null && treeNode != null) {
                    parentNode.add(treeNode);
                }
            }
        }
        
        treeModel.reload();
        
        for (String id : expandedIds) {
            DefaultMutableTreeNode n = nodeMap.get(id);
            if (n != null) {
                tree.expandPath(new TreePath(n.getPath()));
            }
        }
        if (selectedId != null) {
            DefaultMutableTreeNode n = nodeMap.get(selectedId);
            if (n != null) {
                tree.setSelectionPath(new TreePath(n.getPath()));
            }
        }
    }

    private static class FsNodeData {
        String id;
        String displayName;
        boolean isDir;
        String path;

        public FsNodeData(String id, String displayName, boolean isDir, String path) {
            this.id = id;
            this.displayName = displayName;
            this.isDir = isDir;
            this.path = path;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static class DarkTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            
            setBackgroundNonSelectionColor(DarkTheme.BG_PANEL);
            setTextNonSelectionColor(DarkTheme.FG_PRIMARY);
            setBackgroundSelectionColor(DarkTheme.ACCENT_BLUE);
            setTextSelectionColor(DarkTheme.FG_PRIMARY);
            
            return this;
        }
    }
}
