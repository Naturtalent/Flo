package de.flothari.ui.handlers;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.settings.AppSettings;
import de.flothari.ui.settings.SettingsDialog;

public class OpenSettingsHandler {

    @Inject private UISynchronize ui;
    @Inject private EPartService partService;

    @Execute
    public void execute() {
        ui.asyncExec(() -> {
        	Shell shell = Display.getDefault().getActiveShell(); 
            if (shell == null) shell = org.eclipse.swt.widgets.Display.getDefault().getActiveShell();

            AppSettings settings = new AppSettings();
            new SettingsDialog(shell, settings).open();
        });
    }
}
