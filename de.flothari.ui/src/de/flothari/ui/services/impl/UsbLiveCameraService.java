package de.flothari.ui.services.impl;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.services.LiveCameraService;

@Creatable
@Singleton
public class UsbLiveCameraService extends AbstractPiLiveCameraService implements LiveCameraService {

    private static final String SCRIPT = "stream_server.py";
    private static final String STREAM_URL = "http://192.168.7.2:8080/stream";

    public UsbLiveCameraService() {
        super("dieter", "192.168.7.2", "/home/dieter");
    }

    @Override
    public String getId() {
        return "USB_PI_CAM";
    }

    @Override
    public String getDisplayName() {
        return "Raspi Kamera USB-Ethernet";
    }

    @Override
    public String getStreamUrl() {
        return STREAM_URL;
    }

    @Override
    public void start() throws Exception {
        startRemoteScript(SCRIPT);
    }

    @Override
    public void stop() throws Exception {
        stopRemoteScript(SCRIPT);
    }

    @Override
    public boolean isRunning() throws Exception {
        return isRemoteScriptRunning(SCRIPT);
    }
}
