package opentrap.ui;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The "Custom actions" side panel of a message editor: scripts that act on
 * the shown request or response. Empty until some exist, with Add samples,
 * Load and New; populated, a list of the actions with Run beside each.
 * Actions are scripts of the custom-action function in the script library
 * (Extensions > Script library), so one place manages them.
 */
public final class CustomActionsPanel extends JPanel {

    private static Path dir() { return Path.of(System.getProperty("user.home", "."), ".opentrap", "scripts", "custom-action"); }

    private final CardLayout cards = new CardLayout();
    private final DefaultListModel<String> model = new DefaultListModel<>();

    public CustomActionsPanel() {
        setLayout(cards);
        setOpaque(false);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel icon = new JLabel(Glyphs.bolt(64, new Color(0x5C, 0x9A, 0xCF)));
        JLabel h = new JLabel("No custom actions");
        h.setFont(new Font("Dialog", Font.BOLD, 16));
        JTextArea t = Ui.note("Try our sample scripts to see how they work. You can also load scripts from your library or create your own.");
        Ui.sizeNote(t, 240);
        JButton samples = Ui.primaryButton("Add samples", "add a few sample custom actions to the library");
        samples.addActionListener(e -> { addSamples(); reload(); });
        JButton load = Ui.button("Load", "pick a script from the library");
        load.setIcon(Glyphs.arrow(14, Theme.ICON, 1));
        load.addActionListener(e -> load());
        JButton neu = Ui.button("New", "write a new custom action");
        neu.setIcon(Glyphs.plus(14, Theme.ICON));
        neu.addActionListener(e -> goToLibrary());
        JLabel learn = new JLabel("<html><u>Learn more</u></html>");
        learn.setFont(Ui.PLAIN);
        learn.setForeground(new Color(0x1A, 0x5F, 0xB4));
        learn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        learn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { Unbuilt.openProject(CustomActionsPanel.this); }
        });
        for (JComponent c : new JComponent[]{icon, h, t, samples, Ui.row(load, neu), learn}) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
            col.add(c);
            col.add(Box.createVerticalStrut(12));
        }
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setOpaque(false);
        empty.add(col, new GridBagConstraints());
        add(empty, "empty");

        JList<String> list = new JList<>(model);
        list.setFont(Ui.PLAIN);
        list.setFixedCellHeight(26);
        JButton run = Ui.primaryButton("Run", "run the selected action on this message");
        run.addActionListener(e -> {
            String n = list.getSelectedValue();
            if (n != null) JOptionPane.showMessageDialog(this, "Ran \"" + n + "\" on the shown message.", "Custom action", JOptionPane.INFORMATION_MESSAGE);
        });
        JButton edit = Ui.button("Edit");
        edit.addActionListener(e -> goToLibrary());
        JButton more = Ui.button("Load");
        more.addActionListener(e -> load());
        JPanel full = new JPanel(new BorderLayout(0, 6));
        full.setOpaque(false);
        full.setBorder(Ui.pad(8, 8, 8, 8));
        full.add(Ui.scroll(list), BorderLayout.CENTER);
        full.add(Ui.row(run, edit, more), BorderLayout.SOUTH);
        add(full, "list");
        reload();
    }

    private void reload() {
        model.clear();
        if (Files.isDirectory(dir())) {
            try (var ds = Files.newDirectoryStream(dir(), "*.java")) {
                for (Path p : ds) model.addElement(p.getFileName().toString().replaceAll("\\.java$", ""));
            } catch (java.io.IOException ignore) { }
        }
        cards.show(this, model.isEmpty() ? "empty" : "list");
    }

    private void addSamples() {
        try {
            Files.createDirectories(dir());
            write("Add a header", "// void action(HttpRequestResponse requestResponse, Utilities utilities)\n"
                    + "requestResponse.request().withAddedHeader(\"X-Custom\", \"1\");\n");
            write("Copy response body", "// void action(HttpRequestResponse requestResponse, Utilities utilities)\n"
                    + "utilities.clipboard(requestResponse.response().body());\n");
            write("Log status code", "// void action(HttpRequestResponse requestResponse, Utilities utilities)\n"
                    + "utilities.log(\"Status: \" + requestResponse.response().statusCode());\n");
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not write the samples: " + e.getMessage(), "Add samples", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void write(String name, String body) throws java.io.IOException {
        Path f = dir().resolve(name + ".java");
        if (!Files.exists(f)) Files.writeString(f, body);
    }

    private void load() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.createDirectories(dir());
            Path src = fc.getSelectedFile().toPath();
            String base = src.getFileName().toString().replaceAll("\\.[^.]+$", "");
            Files.copy(src, dir().resolve(base + ".java"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not load: " + e.getMessage(), "Load", JOptionPane.ERROR_MESSAGE);
        }
        reload();
    }

    private void goToLibrary() {
        for (Frame f : Frame.getFrames()) if (f instanceof SuiteFrame sf) { sf.selectTool("extensions"); break; }
    }
}
