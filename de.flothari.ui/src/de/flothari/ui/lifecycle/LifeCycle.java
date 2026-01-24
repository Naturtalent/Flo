package de.flothari.ui.lifecycle;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;

public class LifeCycle
{

	public static final String CTX_VLC_RUNNING = "vlc.running";

	@ProcessAdditions
	public void processAdditions(IEclipseContext context)
	{
		context.set(CTX_VLC_RUNNING, Boolean.FALSE);
	}
}
