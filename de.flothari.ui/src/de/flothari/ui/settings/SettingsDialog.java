package de.flothari.ui.settings;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

public class SettingsDialog extends TitleAreaDialog {

    private final AppSettings settings;

    private Text txtAudio;
    private Text txtWorkDir;

    public SettingsDialog(Shell parentShell, AppSettings settings) {
        super(parentShell);
        this.settings = settings;
    }

    @Override
    public void create() {
        super.create();
        setTitle("Einstellungen");
        setMessage("Audio-Gerätename (VLC) und Arbeitsverzeichnis festlegen. Änderungen werden gespeichert.");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);

        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(3, false));

        // Audio Device
        new Label(container, SWT.NONE).setText("Audio-Gerätename:");
        txtAudio = new Text(container, SWT.BORDER);
        txtAudio.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(container, SWT.NONE).setText(""); // Platzhalter

        // WorkDir
        new Label(container, SWT.NONE).setText("Arbeitsverzeichnis:");
        txtWorkDir = new Text(container, SWT.BORDER);
        txtWorkDir.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button btnBrowse = new Button(container, SWT.PUSH);
        btnBrowse.setText("…");
        btnBrowse.addListener(SWT.Selection, e -> browseDir());

        // Werte vorbefüllen
        txtAudio.setText(settings.getAudioDeviceName());
        txtWorkDir.setText(settings.getWorkDir().toString());

        return area;
    }

    private void browseDir() {
        DirectoryDialog dd = new DirectoryDialog(getShell());
        dd.setText("Arbeitsverzeichnis auswählen");
        dd.setMessage("Hier werden capture.png und ref_bilder gespeichert.");
        dd.setFilterPath(txtWorkDir.getText());

        String selected = dd.open();
        if (selected != null) {
            txtWorkDir.setText(selected);
        }
    }

    @Override
    protected void okPressed() {
        String audio = txtAudio.getText().trim();
        Path dir = Path.of(txtWorkDir.getText().trim());

        if (txtWorkDir.getText().trim().isEmpty()) {
            setErrorMessage("Bitte ein Arbeitsverzeichnis angeben.");
            return;
        }
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            setErrorMessage("Das Arbeitsverzeichnis existiert nicht oder ist kein Ordner.");
            return;
        }
        if (!Files.isWritable(dir)) {
            setErrorMessage("In das Arbeitsverzeichnis kann nicht geschrieben werden.");
            return;
        }

        settings.setAudioDeviceName(audio);
        settings.setWorkDir(dir);

        super.okPressed();
    }
}
