package de.flothari.ui.handlers;

import jakarta.inject.Inject;
import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;

import de.flothari.ui.services.impl.CameraLiveService;

public class StartLiveHandler {

    @Inject private CameraLiveService live;

    @Execute
    public void execute() {
        live.start();
    }

    @CanExecute
    public boolean canExecute() {
        return !live.isRunning();
    }
}

