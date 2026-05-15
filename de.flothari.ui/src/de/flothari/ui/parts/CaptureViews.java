package de.flothari.ui.parts;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;

import jakarta.annotation.PostConstruct;

public class CaptureViews {

    @PostConstruct
    public void createControls(Composite parent) {
        // Layout für den Part: 2 Spalten nebeneinander
        parent.setLayout(new GridLayout(2, true)); // 2 columns, same width

        // Linkes „Fenster“ mit Rahmen
        Group left = new Group(parent, SWT.NONE);
        left.setText("Links"); // Rahmen-Titel
        left.setLayout(new GridLayout(1, false));
        left.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Rechtes „Fenster“ mit Rahmen
        Group right = new Group(parent, SWT.NONE);
        right.setText("Rechts");
        right.setLayout(new GridLayout(1, false));
        right.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Hier deine Controls hinzufügen, z.B. Labels, Canvas, Tabellen ...
        // new Label(left, SWT.NONE).setText("Inhalt links");
        // new Label(right, SWT.NONE).setText("Inhalt rechts");
    }
}
