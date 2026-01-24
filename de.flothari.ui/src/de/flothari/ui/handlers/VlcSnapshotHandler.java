package de.flothari.ui.handlers;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import jakarta.inject.Named;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.vlc.VlcService;

import static de.flothari.ui.lifecycle.LifeCycle.CTX_VLC_RUNNING;

public class VlcSnapshotHandler {

    @Inject
    private VlcService vlc;

    @Inject
    private UISynchronize ui;


    @Execute
    public void execute() {
        try {
            // Empfohlen: Methode, die snapshot auslöst + capture.png erzeugt
            vlc.snapshotToCapturePng();

        } catch (Exception ex) {
            showErrorDialog(ex);
        }
    }

    @CanExecute
    public boolean canExecute(@Named(CTX_VLC_RUNNING) Boolean running) {
        return running != null && running.booleanValue();
    }

    // ---------------- UI ----------------


private void showErrorDialog(Exception ex) {
    ui.asyncExec(() -> {
        Shell shell = Display.getDefault().getActiveShell();   // ✅
        if (shell == null) {
            shell = Display.getDefault().getActiveShell(); // ✅ Fallback
        }

        MessageDialog.openError(
            shell,
            "Snapshot fehlgeschlagen",
            buildUserMessage(ex)
        );
    });
}

    private String buildUserMessage(Exception ex) {
        // Benutzerfreundliche Fehlermeldung
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("No such file")
             || ex.getMessage().contains("video")
             || ex.getMessage().contains("v4l2")
             || ex.getMessage().contains("dshow")) {

                return """
                       Es konnte kein Kamerabild aufgenommen werden.

                       Mögliche Ursachen:
                       • Keine USB-Kamera angeschlossen
                       • Kamera wird vom Betriebssystem nicht erkannt
                       • Kamera wird bereits von einem anderen Programm verwendet
                       • Defekte oder nicht unterstützte Kamera

                       Details:
                       """ + ex.getMessage();
            }
        }

        return """
               Der Snapshot konnte nicht erstellt werden.

               Bitte prüfen Sie:
               • Ob VLC läuft
               • Ob eine USB-Kamera angeschlossen ist
               • Ob die Kamera funktionsfähig ist

               Details:
               """ + ex.toString();
    }
}
