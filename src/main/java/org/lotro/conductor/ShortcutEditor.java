package org.lotro.conductor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.function.Consumer;

/** One modal editor used for both instrument shortcuts and Select fellow 2.
 * Typing and pasting a textual chord works; pressing a digit, punctuation slot,
 * function key or modified combination captures it immediately. Escape cancels,
 * Space unassigns. No key event is sent to LOTRO by the editor. */
public final class ShortcutEditor {
    private ShortcutEditor() { }
    public static void open(Window owner, String label, String existing, Consumer<Shortcut> saved) {
        JDialog dialog = new JDialog(owner, "Assign key — " + label, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(8,8));
        content.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        content.add(new JLabel("Type a shortcut, or press its key(s):"),BorderLayout.NORTH);
        JTextField field = new JTextField(existing == null ? "" : existing,22);
        content.add(field,BorderLayout.CENTER);
        JLabel hint = new JLabel("Enter saves  •  Esc cancels  •  Space removes");
        hint.setFont(hint.getFont().deriveFont(11f));
        JPanel bottom = new JPanel(new BorderLayout(5,5));
        bottom.add(hint,BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());
        JButton save = new JButton("Assign");
        save.addActionListener(e -> {
            try {
                Shortcut parsed=Shortcut.parse(field.getText());
                saved.accept(parsed);
                dialog.dispose();
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), "Invalid shortcut", JOptionPane.WARNING_MESSAGE);
                field.requestFocusInWindow();
            }
        });
        actions.add(cancel);actions.add(save);bottom.add(actions,BorderLayout.SOUTH);
        content.add(bottom,BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(save);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if(e.getKeyCode()==KeyEvent.VK_ESCAPE) {e.consume();dialog.dispose();return;}
                if(e.getKeyCode()==KeyEvent.VK_SPACE && e.getModifiersEx()==0) {
                    e.consume();saved.accept(null);dialog.dispose();return;
                }
                if(Set.of(KeyEvent.VK_CONTROL,KeyEvent.VK_ALT,KeyEvent.VK_SHIFT,KeyEvent.VK_META)
                        .contains(e.getKeyCode()))return;
                boolean slotKey=(e.getKeyCode()>=KeyEvent.VK_0 && e.getKeyCode()<=KeyEvent.VK_9)
                        || e.getKeyCode()==KeyEvent.VK_MINUS || e.getKeyCode()==KeyEvent.VK_EQUALS
                        || (e.getKeyCode()>=KeyEvent.VK_F1 && e.getKeyCode()<=KeyEvent.VK_F12);
                boolean selectedAll=field.getSelectedText()!=null && field.getSelectedText().equals(field.getText());
                boolean fresh=field.getText().isBlank() || selectedAll;
                // Shift is also needed to TYPE '+' and capital letters, so it only
                // counts as a physical chord for an untouched quickslot field.
                boolean chord=(e.isControlDown() || e.isAltDown() ||
                        (e.isShiftDown() && slotKey && fresh));
                // When typing "Ctrl+1", the '+' and final 1 must not prematurely
                // trigger captures. A fresh/selected field captures one-key chords.
                if((slotKey && fresh) || chord) {
                    try {
                        Shortcut key=Shortcut.capture(e);
                        e.consume();saved.accept(key);dialog.dispose();
                    }catch(IllegalArgumentException ignored){/* the typed shortcut remains editable */}
                }
            }
        });
        dialog.pack();dialog.setResizable(false);dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> {field.requestFocusInWindow();field.selectAll();});
        dialog.setVisible(true);
    }
}
