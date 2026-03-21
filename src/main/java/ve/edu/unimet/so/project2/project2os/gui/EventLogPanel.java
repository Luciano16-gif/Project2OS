package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.EventLogEntrySummary;

import javax.swing.*;
import java.awt.*;

public class EventLogPanel extends JPanel {

    private final JTextArea textArea;
    private long lastSeenSequence = -1;
    private static final String CATEGORY_PLAYBACK = "PLAYBACK";

    public EventLogPanel() {
        setLayout(new BorderLayout());
        setBackground(DarkTheme.BG_PANEL);

        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setBackground(DarkTheme.BG_DARK);
        textArea.setForeground(DarkTheme.ACCENT_GREEN);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setMargin(new Insets(5, 5, 5, 5));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        
        add(scrollPane, BorderLayout.CENTER);
    }

    public void updateFromSnapshot(EventLogEntrySummary[] entries) {
        if (entries == null) return;

        if (entries.length == 0) {
            if (lastSeenSequence >= 0) {
                textArea.setText("");
            }
            lastSeenSequence = -1;
            return;
        }

        // Backend reinitializations can reset sequence numbering; restart local tracking to avoid a dead panel.
        if (entries[0].getSequenceNumber() <= lastSeenSequence) {
            textArea.setText("");
            lastSeenSequence = -1;
        }

        boolean added = false;
        for (EventLogEntrySummary entry : entries) {
            if (entry.getSequenceNumber() <= lastSeenSequence) {
                continue;
            }
            lastSeenSequence = entry.getSequenceNumber();
            if (CATEGORY_PLAYBACK.equalsIgnoreCase(entry.getCategory())) {
                continue;
            }
            textArea.append(entry.getCategory() + " (" + entry.getTick() + "): " + entry.getMessage() + "\n");
            added = true;
        }
        
        if (added) {
            textArea.setCaretPosition(textArea.getDocument().getLength());
        }
    }
    
    public void clearLog() {
        textArea.setText("");
        lastSeenSequence = -1;
    }
}
