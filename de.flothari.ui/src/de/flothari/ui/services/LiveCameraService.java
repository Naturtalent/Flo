package de.flothari.ui.services;

public interface LiveCameraService {

    String getId();

    String getDisplayName();

    String getStreamUrl();

    void start() throws Exception;

    void stop() throws Exception;

    boolean isRunning() throws Exception;
}
