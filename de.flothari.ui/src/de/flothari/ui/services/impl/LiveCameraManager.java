package de.flothari.ui.services.impl;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.services.LiveCameraService;

/*
 * Managed die CameraService Klassen
 */

@Creatable
@Singleton
public class LiveCameraManager
{

	@Inject
	private HttpLiveCameraService httpCamera;
	@Inject
	private UsbLiveCameraService usbCamera;

	public List<LiveCameraService> getAvailableCameras()
	{
		return List.of(httpCamera, usbCamera);
	}

	public LiveCameraService getById(String id)
	{
		return getAvailableCameras().stream().filter(c -> c.getId().equals(id)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown camera id: " + id));
	}

	public HttpLiveCameraService getHttpCamera()
	{
		return httpCamera;
	}

	public UsbLiveCameraService getUsbCamera()
	{
		return usbCamera;
	}

	public void startExclusive(String id) throws Exception
	{
		for (LiveCameraService cam : getAvailableCameras())
		{
			if (cam.isRunning())
			{
				cam.stop();
			}
		}
		getById(id).start();
	}

}
