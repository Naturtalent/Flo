package de.flothari.ui.handlers;

import jakarta.inject.Inject;
import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import jakarta.inject.Named;

import de.flothari.ui.vlc.VlcService;

import static de.flothari.ui.lifecycle.LifeCycle.CTX_VLC_RUNNING;

public class StartVlcCameraHandler {

    @Inject private VlcService vlc;

    @Execute
    public void execute() throws Exception {
        vlc.startCamera();
    }

    @CanExecute
    public boolean canExecute(@Named(CTX_VLC_RUNNING) Boolean running) {
        return running == null || !running.booleanValue();
    }
}
