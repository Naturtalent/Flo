package de.flothari.ui.services;

import java.nio.file.Path;

public interface CameraService {
    /** Liefert den Pfad zur aktuellen Capture-Datei (png/jpg/jpeg) im WorkDir. */
    Path capture() throws Exception;
}
