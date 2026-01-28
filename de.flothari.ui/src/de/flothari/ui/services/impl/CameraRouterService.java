package de.flothari.ui.services.impl;

import java.nio.file.Path;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.services.CameraService;
import de.flothari.ui.settings.AppSettings;

@Creatable
@Singleton
public class CameraRouterService implements CameraService
{

	@Inject
	private PiCameraService piCamera;
	@Inject
	private LocalFileCameraService localCamera;

	@Override
	public Path capture() throws Exception
	{
		// später: Setting "camera.source"
		String src = new AppSettings().getCameraSource(); // z.B. "PI" oder "LOCAL"
		if ("LOCAL".equalsIgnoreCase(src))
		{
			// Pfad zum lokal im WorkingDir gespeicherten capture
			return localCamera.capture();
		}
		// lade das Bild von der piCam
		return piCamera.capture();
	}
}
