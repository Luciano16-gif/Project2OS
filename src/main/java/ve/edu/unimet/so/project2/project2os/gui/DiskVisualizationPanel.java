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

    private final JLabel[] blockCells;
    private final JTable allocationTable;
    private final DefaultTableModel allocationTableModel;
    private final JLabel lblFreeBlocks;

    public DiskVisualizationPanel(int totalBlocks) {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        JPanel gridPanel = new JPanel(new GridLayout(0, 20, 2, 2));
        gridPanel.setBackground(DarkTheme.BG_PANEL);
        gridPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        blockCells = new JLabel[totalBlocks];
        for (int i = 0; i < totalBlocks; i++) {
            JLabel cell = new JLabel(String.valueOf(i), SwingConstants.CENTER);
            cell.setOpaque(true);
            cell.setBackground(DarkTheme.BG_HEADER);
            cell.setForeground(DarkTheme.FG_SECONDARY);
            cell.setFont(cell.getFont().deriveFont(9f));
            cell.setPreferredSize(new Dimension(35, 25));
            cell.setToolTipText("Block " + i);
            gridPanel.add(cell);
            blockCells[i] = cell;
        }

        JScrollPane blockScrollPane = new JScrollPane(gridPanel);
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
        allocationTable.setRowHeight(24);
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
        allocationScrollPane.setPreferredSize(new Dimension(0, 190));

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, blockScrollPane, allocationScrollPane);
        centerSplit.setResizeWeight(0.70);
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
            JLabel cell = blockCells[i];

            if (block.isFree()) {
                freeCount++;
                cell.setBackground(DarkTheme.BG_HEADER);
                cell.setForeground(DarkTheme.FG_SECONDARY);
            } else {
                String colorId = nodeColors.get(block.getOwnerFileId());
                if (block.isSystemReserved()) {
                    cell.setBackground(DarkTheme.ACCENT_RED.darker());
                } else {
                    cell.setBackground(DarkTheme.getColorForId(colorId));
                }
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
}
