package de.flothari.ui.handlers;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;

import de.flothari.ui.vlc.VlcController;
import jakarta.inject.Inject;

public class VlcQuitHandler
{
	@Inject
	private IEclipseContext context;

	@Execute
	public void execute() throws Exception
	{
		StartVlcCameraHandler.getVlc().quit();

		// 🔁 Enablement neu bewerten
		context.set(VlcController.class, null);
	}

	@CanExecute
	public boolean canExecute()
	{
		return StartVlcCameraHandler.getVlc() != null && StartVlcCameraHandler.getVlc().isRunning();
	}
}
