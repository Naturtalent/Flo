package de.flothari.ui.handlers;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.nio.file.Path;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.services.CameraService;

public class SnapshotHandler
{
	@Inject
	private CameraService camera; // -> PiCameraService
	@Inject
	private UISynchronize ui;
	

	@Inject @Named(IServiceConstants.ACTIVE_SHELL) Shell shell;
	
	@Execute
	public void execute()
	{
		new Thread(() ->
		{
			try
			{
				Path p = camera.capture();
				ui.asyncExec(() -> MessageDialog.openInformation(shell, "Snapshot", "Snapshot gespeichert:\n" + p));
			} catch (Exception ex)
			{
				ui.asyncExec(() -> MessageDialog.openError(shell, "Snapshot fehlgeschlagen",
						ex.getMessage() != null ? ex.getMessage() : ex.toString()));
			}
		}, "pi-snapshot").start();
	}

}
