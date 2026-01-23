package de.flothari.ui.handlers;

import java.io.IOException;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.jface.dialogs.MessageDialog;

public class StartVlcHandler
{

	@Execute
	public void execute(Shell shell)
	{
		String vlcCommand = getVlcCommand();

		try
		{
			new ProcessBuilder(vlcCommand).start();
		} catch (IOException e)
		{
			MessageDialog.openError(shell, "VLC konnte nicht gestartet werden", e.getMessage());
		}
	}

	private String getVlcCommand()
	{
		String os = System.getProperty("os.name").toLowerCase();

		if (os.contains("win"))
		{
			return "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe";
		}
		if (os.contains("mac"))
		{
			return "/Applications/VLC.app/Contents/MacOS/VLC";
		}
		return "vlc"; // Linux
	}
}
