package de.flothari.ui.services.impl;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.services.LiveCameraService;

/**
 * startet die Phytonskripte auf dem PI
 */

@Creatable
@Singleton
public class HttpLiveCameraService extends AbstractPiLiveCameraService implements LiveCameraService
{

	private static final String SCRIPT = "rpicam_mjpeg_http.py";
	private static final String STREAM_URL = "http://192.168.178.166:8080/stream";

	public HttpLiveCameraService()
	{
		super("dieter", "192.168.178.166", "/home/dieter/scripts/");
	}

	@Override
	public String getId()
	{
		return "HTTP_PI_CAM";
	}

	@Override
	public String getDisplayName()
	{
		return "Raspi Kamera HTTP";
	}

	@Override
	public String getStreamUrl()
	{
		return STREAM_URL;
	}

	@Override
	public void start() throws Exception
	{
		startRemoteScript(SCRIPT);
	}

	@Override
	public void stop() throws Exception
	{
		stopRemoteScript(SCRIPT);
	}

	@Override
	public boolean isRunning() throws Exception
	{
		return isRemoteScriptRunning(SCRIPT);
	}
}
