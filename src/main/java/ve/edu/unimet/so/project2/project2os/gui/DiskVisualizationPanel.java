package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.DiskBlockSummary;
import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.FileSystemNodeSummary;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiskVisualizationPanel extends JPanel {

    private final BlockLabel[] blockCells;
    private final JTable allocationTable;
    private final DefaultTableModel allocationTableModel;
    private final JLabel lblFreeBlocks;

    public DiskVisualizationPanel(int totalBlocks) {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        JPanel gridPanel = new JPanel(new GridLayout(0, 24, 2, 2));
        gridPanel.setBackground(DarkTheme.BG_PANEL);
        gridPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        blockCells = new BlockLabel[totalBlocks];
        for (int i = 0; i < totalBlocks; i++) {
            BlockLabel cell = new BlockLabel(i);
            gridPanel.add(cell);
            blockCells[i] = cell;
        }

        JPanel gridWrapper = new JPanel(new BorderLayout());
        gridWrapper.setBackground(DarkTheme.BG_PANEL);
        gridWrapper.add(gridPanel, BorderLayout.NORTH);

        JScrollPane blockScrollPane = new JScrollPane(gridWrapper);
        blockScrollPane.setBorder(BorderFactory.createEmptyBorder());
        blockScrollPane.getViewport().setBackground(DarkTheme.BG_PANEL);

        allocationTableModel = new DefaultTableModel(
                new String[]{"Archivo", "Bloques", "Primer Bloque", "Color", "Propietario"},
                0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        allocationTable = new JTable(allocationTableModel);
        allocationTable.setBackground(DarkTheme.BG_PANEL);
        allocationTable.setForeground(DarkTheme.FG_PRIMARY);
        allocationTable.setGridColor(DarkTheme.BG_HEADER);
        allocationTable.setSelectionBackground(DarkTheme.ACCENT_BLUE);
        allocationTable.setSelectionForeground(DarkTheme.FG_PRIMARY);
        allocationTable.setRowHeight(22);
        allocationTable.getTableHeader().setReorderingAllowed(false);

        allocationTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column) {
                JLabel cell = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                Color color = DarkTheme.getColorForId(value != null ? value.toString() : null);
                cell.setText("");
                cell.setOpaque(true);
                cell.setBackground(color);
                if (isSelected) {
                    cell.setBorder(BorderFactory.createLineBorder(DarkTheme.FG_PRIMARY));
                } else {
                    cell.setBorder(BorderFactory.createEmptyBorder());
                }
                return cell;
            }
        });

        JScrollPane allocationScrollPane = new JScrollPane(allocationTable);
        allocationScrollPane.setBorder(BorderFactory.createTitledBorder("Tabla de Asignación"));
        allocationScrollPane.getViewport().setBackground(DarkTheme.BG_PANEL);
        allocationScrollPane.setPreferredSize(new Dimension(0, 400));

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, blockScrollPane, allocationScrollPane);
        centerSplit.setResizeWeight(0.35);
        centerSplit.setDividerSize(4);
        centerSplit.setBorder(BorderFactory.createEmptyBorder());

        add(centerSplit, BorderLayout.CENTER);

        lblFreeBlocks = new JLabel("Bloques libres: - / " + totalBlocks);
        lblFreeBlocks.setForeground(DarkTheme.FG_PRIMARY);
        lblFreeBlocks.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        add(lblFreeBlocks, BorderLayout.SOUTH);
    }

    public void updateFromSnapshot(DiskBlockSummary[] blocks, FileSystemNodeSummary[] nodes) {
        if (blocks == null) return;

        Map<String, String> nodeColors = new HashMap<>();
        List<FileSystemNodeSummary> fileNodes = new ArrayList<>();
        if (nodes != null) {
            for (FileSystemNodeSummary node : nodes) {
                nodeColors.put(node.getNodeId(), node.getColorId());
                if (!node.isRoot() && "FILE".equals(node.getType().name())) {
                    fileNodes.add(node);
                }
            }
        }

        fileNodes.sort(Comparator.comparingInt(FileSystemNodeSummary::getFirstBlockIndex));

        allocationTableModel.setRowCount(0);
        for (FileSystemNodeSummary fileNode : fileNodes) {
            allocationTableModel.addRow(new Object[]{
                    fileNode.getName(),
                    fileNode.getSizeInBlocks(),
                    fileNode.getFirstBlockIndex(),
                    fileNode.getColorId(),
                    fileNode.getOwnerUserId()
            });
        }

        int freeCount = 0;
        for (int i = 0; i < blocks.length; i++) {
            if (i >= blockCells.length) break;
            DiskBlockSummary block = blocks[i];
            BlockLabel cell = blockCells[i];

            if (block.isFree()) {
                freeCount++;
                cell.setBackground(DarkTheme.BG_HEADER);
                cell.setForeground(DarkTheme.FG_SECONDARY);
            } else {
                String colorId = nodeColors.get(block.getOwnerFileId());
                String colorKey = colorId != null ? colorId : block.getOwnerFileId();
                cell.setBackground(DarkTheme.getColorForId(colorKey));
                Color bg = cell.getBackground();
                double luma = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
                cell.setForeground(luma > 140 ? Color.BLACK : Color.WHITE);
            }

            StringBuilder tooltip = new StringBuilder("<html>");
            tooltip.append("<b>Bloque ").append(block.getIndex()).append("</b><br>");
            tooltip.append("Libre: ").append(block.isFree() ? "Sí" : "No").append("<br>");
            if (!block.isFree()) {
                tooltip.append("Propietario: ").append(block.getOwnerFileId()).append("<br>");
            }
            if (block.getOccupiedByProcessId() != null) {
                tooltip.append("En uso por: ").append(block.getOccupiedByProcessId()).append("<br>");
            }
            if (block.getNextBlockIndex() != -1) {
                tooltip.append("Sig. Bloque: ").append(block.getNextBlockIndex()).append("<br>");
            }
            tooltip.append("</html>");
            cell.setToolTipText(tooltip.toString());
        }

        lblFreeBlocks.setText("Bloques libres: " + freeCount + " / " + blocks.length);
    }

    private static class BlockLabel extends JLabel {
        private static final int ARC = 8;

        public BlockLabel(int index) {
            super(String.valueOf(index), SwingConstants.CENTER);
            setOpaque(false);
            setBackground(DarkTheme.BG_HEADER);
            setForeground(DarkTheme.FG_SECONDARY);
            setFont(getFont().deriveFont(Font.BOLD, 9f));
            setPreferredSize(new Dimension(32, 32));
            setToolTipText("Block " + index);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            
            // Subtle border
            g2.setColor(getBackground().darker());
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);

            super.paintComponent(g2);
            g2.dispose();
        }
    }
}
