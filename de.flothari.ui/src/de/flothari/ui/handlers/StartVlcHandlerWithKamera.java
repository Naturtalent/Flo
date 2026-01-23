package de.flothari.ui.handlers;

import java.io.IOException;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

public class StartVlcHandlerWithKamera
{
	@Execute
	public void execute(Shell shell)
	{
		try
		{
			executeStartVlcWithCamera();
		} catch (IOException e)
		{
			MessageDialog.openError(shell, "VLC konnte nicht gestartet werden", e.getMessage());
		}
	}

	private void executeStartVlcWithCamera() throws IOException
	{
		String os = System.getProperty("os.name").toLowerCase();

		ProcessBuilder pb;

		if (os.contains("win"))
		{
			// Windows: DirectShow (USB Webcam)
			pb = new ProcessBuilder("C:\\\\Program Files\\\\VideoLAN\\\\VLC\\\\vlc.exe", "dshow://");

		} else if (os.contains("mac"))
		{
			// macOS
			pb = new ProcessBuilder("/Applications/VLC.app/Contents/MacOS/VLC", "qtcapture://");

		} else
		{
			// Linux / Raspberry Pi
			pb = new ProcessBuilder("vlc", "v4l2:///dev/video2");			
		}

		pb.start();
	}
}
