package de.flothari.ui.handlers;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.services.LiveCameraService;
import de.flothari.ui.services.impl.LiveCameraManager;

public class StopSelectedLiveCameraHandler {

    @Inject private LiveCameraManager manager;

    @Execute
    public void execute(Shell shell) {
        try {
            LiveCameraService cam = manager.getById("HTTP_PI_CAM"); // oder USB_PI_CAM
            cam.stop();

            MessageDialog.openInformation(shell, "Live Kamera",
                    "Gestartet: " + cam.getDisplayName() +
                    "\nURL: " + cam.getStreamUrl());

        } catch (Exception ex) {
            MessageDialog.openError(shell, "Fehler", ex.getMessage());
        }
    }
}
