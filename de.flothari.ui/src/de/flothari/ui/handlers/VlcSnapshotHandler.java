package de.flothari.ui.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import de.flothari.ui.vlc.VlcController;
import org.eclipse.e4.core.di.annotations.CanExecute;

public class VlcSnapshotHandler
{
	// dieselbe Instanz wie beim Start-Handler
	private static final VlcController VLC = StartVlcCameraHandler.getVlc();

	@Execute
	public void execute() throws Exception
	{
		// StartVlcCameraHandler.getVlc().snapshot();
		StartVlcCameraHandler.getVlc().snapshotToCapturePng();
	}

	@CanExecute
	public boolean canExecute()
	{
		// 👉 Button ist nur aktiv, wenn VLC läuft
		return VLC != null && VLC.isRunning();
	}
}