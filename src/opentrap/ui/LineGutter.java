package opentrap.ui;

import javax.swing.*;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * A line-number gutter for a text component, used as a scroll pane's row
 * header. Numbers follow the document's paragraph elements, so wrapped lines
 * keep one number and the gutter stays right when text is edited.
 */
public final class LineGutter extends JComponent {

    private final JTextComponent text;

    public LineGutter(JTextComponent text) {
        this.text = text;
        setFont(Ui.MONO);
        setOpaque(true);
        setBackground(Theme.BG);
        text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { revalidate(); repaint(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { revalidate(); repaint(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { repaint(); }
        });
        text.addPropertyChangeListener("font", e -> { setFont(text.getFont()); revalidate(); repaint(); });
        text.addPropertyChangeListener("document", e -> {
            ((javax.swing.text.Document) e.getNewValue()).addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent ev) { revalidate(); repaint(); }
                public void removeUpdate(javax.swing.event.DocumentEvent ev) { revalidate(); repaint(); }
                public void changedUpdate(javax.swing.event.DocumentEvent ev) { repaint(); }
            });
            revalidate(); repaint();
        });
    }

    private int lines() {
        return text.getDocument().getDefaultRootElement().getElementCount();
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int digits = Math.max(2, String.valueOf(lines()).length());
        return new Dimension(fm.charWidth('0') * digits + 14, text.getPreferredSize().height);
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(getBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(getFont());
        g2.setColor(Theme.MUTED);
        FontMetrics fm = g2.getFontMetrics();
        Element root = text.getDocument().getDefaultRootElement();
        Rectangle clip = g2.getClipBounds();
        int n = root.getElementCount();
        for (int i = 0; i < n; i++) {
            try {
                Rectangle r = text.modelToView(root.getElement(i).getStartOffset());
                if (r == null) continue;
                if (r.y + r.height < clip.y) continue;
                if (r.y > clip.y + clip.height) break;
                String s = String.valueOf(i + 1);
                g2.drawString(s, getWidth() - 8 - fm.stringWidth(s), r.y + fm.getAscent());
            } catch (javax.swing.text.BadLocationException ignore) { }
        }
        g2.dispose();
    }
}
