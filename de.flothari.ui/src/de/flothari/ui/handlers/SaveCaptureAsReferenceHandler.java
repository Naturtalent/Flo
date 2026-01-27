package de.flothari.ui.handlers;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.settings.AppSettings;

/**
 * Das Bild 'capture' wird als Referenzbild übernommen. Mit einem Eingabedialog
 * kann eine Dateiname definiert werden. Dieser soll identisch mit dem
 * Regalfach der dargestellten Form sein. 
 */
public class SaveCaptureAsReferenceHandler {

    @Inject private UISynchronize ui;
    @Inject private EPartService partService;

    @Execute
    public void execute() {
        ui.asyncExec(() -> {
            Shell shell = safeShell();
            
            AppSettings s = new AppSettings();
            Path workDir = s.getWorkDir();
            Path capture = workDir.resolve("capture.png");
            Path refDir  = workDir.resolve("ref_bilder");

            if (!Files.exists(capture)) {
                MessageDialog.openError(shell, "Fehler", "capture.png wurde im App-Verzeichnis nicht gefunden:\n" + capture);
                return;
            }

            String defaultName = "ref_" + System.currentTimeMillis(); // oder leer lassen
            InputDialog dlg = new InputDialog(
                    shell,
                    "Referenzbild speichern",
                    "Dateiname (ohne Endung) für das Referenzbild:",
                    defaultName,
                    input -> validateFileName(input)
            );

            if (dlg.open() != Window.OK) {
                return; // abgebrochen
            }

            String baseName = sanitize(dlg.getValue());
            if (baseName.isBlank()) {
                MessageDialog.openError(shell, "Fehler", "Ungültiger Dateiname.");
                return;
            }

            Path target = refDir.resolve(baseName + ".png");

            try {
                Files.createDirectories(refDir);

                if (Files.exists(target)) {
                    boolean overwrite = MessageDialog.openQuestion(
                            shell,
                            "Datei existiert bereits",
                            "Die Datei existiert bereits:\n" + target.getFileName() + "\n\nÜberschreiben?"
                    );
                    if (!overwrite) return;
                }

                Files.copy(capture, target, StandardCopyOption.REPLACE_EXISTING);

                MessageDialog.openInformation(
                        shell,
                        "Gespeichert",
                        "Referenzbild gespeichert als:\n" + target
                );

            } catch (IOException ex) {
                MessageDialog.openError(shell, "Fehler beim Speichern", ex.getMessage());
            }
        });
    }

    private Shell safeShell() {
        //Shell shell = partService.getActiveShell();
		Shell shell = Display.getDefault().getActiveShell();
        if (shell == null) {
            shell = org.eclipse.swt.widgets.Display.getDefault().getActiveShell();
        }
        return shell;
    }

    // --- Validierung im Dialog (liefert null = OK, sonst Fehltext) ---
    private String validateFileName(String input) {
        if (input == null) return "Bitte einen Namen eingeben.";
        String s = sanitize(input);
        if (s.isBlank()) return "Bitte einen Namen eingeben.";
        if (s.length() > 80) return "Bitte einen kürzeren Namen wählen (max. 80 Zeichen).";
        return null;
    }

    // Entfernt problematische Zeichen (Windows/Linux kompatibel)
    private String sanitize(String input) {
        String s = input.trim();

        // Endung entfernen, falls Nutzer ".png" tippt
        if (s.toLowerCase(Locale.ROOT).endsWith(".png")) {
            s = s.substring(0, s.length() - 4);
        }

        // verbotene Dateizeichen ersetzen
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        s = s.replaceAll("\\s+", "_"); // spaces -> underscore
        return s;
    }
}
