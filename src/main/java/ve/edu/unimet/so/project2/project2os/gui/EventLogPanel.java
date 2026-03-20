package ve.edu.unimet.so.project2.project2os.gui;

import ve.edu.unimet.so.project2.coordinator.snapshot.SimulationSnapshot.EventLogEntrySummary;

import javax.swing.*;
import java.awt.*;

public class EventLogPanel extends JPanel {

    private final JTextArea textArea;
    private long lastSeenSequence = -1;

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

        boolean added = false;
        for (EventLogEntrySummary entry : entries) {
            if (entry.getSequenceNumber() > lastSeenSequence) {
                lastSeenSequence = entry.getSequenceNumber();
                textArea.append(entry.getCategory() + " (" + entry.getTick() + "): " + entry.getMessage() + "\n");
                added = true;
            }
        }
        
        if (added) {
            textArea.setCaretPosition(textArea.getDocument().getLength());
        }
    }
    
    public void clearLog() {
        textArea.setText("");
    }
}
