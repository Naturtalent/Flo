package de.flothari.ui.handlers;

import java.nio.file.Path;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.services.impl.CameraLiveService;
import de.flothari.ui.services.impl.MultiCameraSnapshotService;
import de.flothari.ui.settings.AppSettings;

public class CaptureCam2Handler {

    @Inject private CameraLiveService live;
    @Inject private MultiCameraSnapshotService snapshot;
    @Inject private UISynchronize ui;

    @Execute
    public void execute(Shell shell) {

        new Thread(() -> {
            boolean restartLive = false;

            try {
                // 1) Live ggf. stoppen
                if (live.isRunning()) {
                    restartLive = true;
                    live.stop();
                    Thread.sleep(150); // Kamera wirklich freigeben
                }

                // 2) Snapshot aufnehmen
                String device = new AppSettings().getVideoDeviceName();
                Path p = snapshot.captureTo(device, "capture.png");

                ui.asyncExec(() ->
                    MessageDialog.openInformation(shell, "Snapshot",
                            "Schnappschuss gespeichert:\n" + p)
                );

            } catch (Exception ex) {
                ui.asyncExec(() ->
                    MessageDialog.openError(shell, "Snapshot fehlgeschlagen",
                            ex.getMessage())
                );

            } finally {
                // 3) Live ggf. wieder starten
                if (restartLive) {
                    try {
                        Thread.sleep(150); // kleine Pause, stabilisiert V4L2
                        live.start();
                    } catch (Exception ignored) { }
                }
            }
        }, "snapshot-cam2").start();
    }
}
