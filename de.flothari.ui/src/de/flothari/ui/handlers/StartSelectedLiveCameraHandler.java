package de.flothari.ui.handlers;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.services.LiveCameraService;
import de.flothari.ui.services.impl.LiveCameraManager;
import de.flothari.ui.settings.AppSettings;

public class StartSelectedLiveCameraHandler
{

	@Inject
	private LiveCameraManager manager;

	@Execute
	public void execute(Shell shell)
	{
		try
		{
			AppSettings settings = new AppSettings();
			String id = settings.getLiveCameraSource();
			
			LiveCameraService cam = manager.getById("HTTP_PI_CAM"); // oder USB_PI_CAM
			
			boolean isrunning = cam.isRunning();
			String url = cam.getStreamUrl();
			
			cam.start();
			
			
			//AppSettings settings = new AppSettings();			
			//manager.startExclusive(settings.getLiveCameraSource());

			MessageDialog.openInformation(shell, "Live Kamera",
					"Gestartet: " + cam.getDisplayName() + "\nURL: " + cam.getStreamUrl());

		} catch (Exception ex)
		{
			MessageDialog.openError(shell, "Fehler", ex.getMessage());
		}
	}
}
