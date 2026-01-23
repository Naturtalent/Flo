package de.flothari.ui.handlers;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Execute;
import de.flothari.ui.vlc.VlcController;
import jakarta.inject.Inject;

public class StartVlcCameraHandler
{

	// In echt würdest du das als Singleton/Service halten.
	// Fürs Beispiel: statisch.
	private static final VlcController VLC = new VlcController("127.0.0.1", 4212);

	@Inject
	private IEclipseContext context;

	@Execute
	public void execute() throws Exception
	{
		VLC.startCamera();
		
		// 🔁 Enablement neu bewerten
        context.set(VlcController.class, VLC);
	}

	public static VlcController getVlc()
	{
		return VLC;
	}
}
