package ve.edu.unimet.so.project2.project2os.gui;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class DarkTheme {
    public static final Color BG_DARK = new Color(30, 34, 43);
    public static final Color BG_PANEL = new Color(40, 44, 52);
    public static final Color BG_HEADER = new Color(50, 55, 65);
    public static final Color FG_PRIMARY = new Color(220, 225, 235);
    public static final Color FG_SECONDARY = new Color(140, 150, 170);
    public static final Color ACCENT_BLUE = new Color(74, 144, 226);
    public static final Color ACCENT_GREEN = new Color(60, 179, 113);
    public static final Color ACCENT_RED = new Color(230, 74, 25);
    public static final Color ACCENT_YELLOW = new Color(245, 166, 35);
    
    private static final Color[] PALETTE = {
        new Color(214, 39, 40),    // Red
        new Color(44, 160, 44),    // Green
        new Color(31, 119, 180),   // Blue
        new Color(255, 127, 14),   // Orange
        new Color(148, 103, 189),  // Purple
        new Color(140, 86, 75),    // Brown
        new Color(227, 119, 194),  // Pink
        new Color(188, 189, 34),   // Olive
        new Color(23, 190, 207),   // Cyan
        new Color(255, 187, 120),  // Light Orange
        new Color(152, 223, 138),  // Light Green
        new Color(174, 199, 232),  // Light Blue
        new Color(255, 152, 150),  // Light Red
        new Color(197, 176, 213),  // Light Purple
        new Color(196, 156, 148),  // Light Brown
        new Color(247, 182, 210),  // Light Pink
        new Color(219, 219, 141),  // Light Olive
        new Color(158, 218, 229)   // Light Cyan
    };

    public static Color getColorForId(String colorId) {
        if (colorId == null) return FG_SECONDARY;
        int hash = Math.abs(colorId.hashCode());
        return PALETTE[hash % PALETTE.length];
    }

    public static void applyGlobalTheme() {
        UIManager.put("Panel.background", BG_DARK);
        UIManager.put("OptionPane.background", BG_DARK);
        UIManager.put("OptionPane.messageForeground", FG_PRIMARY);
        UIManager.put("Label.foreground", FG_PRIMARY);
        UIManager.put("Button.background", BG_HEADER);
        UIManager.put("Button.foreground", FG_PRIMARY);
        UIManager.put("ComboBox.background", BG_HEADER);
        UIManager.put("ComboBox.foreground", FG_PRIMARY);
        UIManager.put("ComboBox.selectionBackground", ACCENT_BLUE);
        UIManager.put("ComboBox.selectionForeground", FG_PRIMARY);
        UIManager.put("TextField.background", BG_PANEL);
        UIManager.put("TextField.foreground", FG_PRIMARY);
        UIManager.put("TextField.caretForeground", FG_PRIMARY);
        UIManager.put("TextArea.background", BG_PANEL);
        UIManager.put("TextArea.foreground", FG_PRIMARY);
        UIManager.put("Table.background", BG_PANEL);
        UIManager.put("Table.foreground", FG_PRIMARY);
        UIManager.put("Table.gridColor", BG_HEADER);
        UIManager.put("TableHeader.background", BG_HEADER);
        UIManager.put("TableHeader.foreground", FG_PRIMARY);
        UIManager.put("Tree.background", BG_PANEL);
        UIManager.put("Tree.textForeground", FG_PRIMARY);
        UIManager.put("Tree.textBackground", BG_PANEL);
        UIManager.put("Tree.selectionBackground", ACCENT_BLUE);
        UIManager.put("Tree.selectionForeground", FG_PRIMARY);
        UIManager.put("ScrollPane.background", BG_DARK);
        UIManager.put("Viewport.background", BG_PANEL);
        UIManager.put("SplitPane.background", BG_DARK);
        UIManager.put("SplitPaneDivider.draggingColor", ACCENT_BLUE);
    }

    public static JButton styledButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(BG_HEADER);
        btn.setForeground(FG_PRIMARY);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BG_HEADER.brighter(), 1),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        btn.setOpaque(true);
        return btn;
    }

    public static JPanel styledPanel(String title) {
        JPanel panel = new JPanel();
        panel.setBackground(BG_PANEL);
        if (title != null) {
            TitledBorder border = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(BG_HEADER), title);
            border.setTitleColor(FG_PRIMARY);
            panel.setBorder(border);
        }
        return panel;
    }
}
