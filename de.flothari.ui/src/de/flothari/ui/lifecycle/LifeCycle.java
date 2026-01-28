package de.flothari.ui.lifecycle;

import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.workbench.lifecycle.PostContextCreate;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;

import de.flothari.ui.services.CameraService;
import de.flothari.ui.services.InventoryService;
import de.flothari.ui.services.RecognitionService;
import de.flothari.ui.services.impl.CameraRouterService;
import de.flothari.ui.services.impl.InMemoryInventoryService;
import de.flothari.ui.services.impl.OpenCvRecognitionService;

public class LifeCycle
{

	public static final String CTX_VLC_RUNNING = "vlc.running";

	@ProcessAdditions
	public void processAdditions(IEclipseContext context)
	{
		context.set(CTX_VLC_RUNNING, Boolean.FALSE);
	}

	@PostContextCreate
	void postContextCreate(IEclipseContext ctx)
	{

		// Instanzen wirklich erzeugen (DI wird dabei angewendet!)
		OpenCvRecognitionService rec = ContextInjectionFactory.make(OpenCvRecognitionService.class, ctx);
		InMemoryInventoryService inv = ContextInjectionFactory.make(InMemoryInventoryService.class, ctx);
		CameraRouterService cam = ContextInjectionFactory.make(CameraRouterService.class, ctx);

		// jetzt in den Context legen
		ctx.set(OpenCvRecognitionService.class, rec);
		ctx.set(InMemoryInventoryService.class, inv);
		ctx.set(CameraRouterService.class, cam);

		// Interface-Bindings
		ctx.set(RecognitionService.class, rec);
		ctx.set(InventoryService.class, inv);
		ctx.set(CameraService.class, cam);
	}

}
