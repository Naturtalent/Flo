package de.flothari.ui.services.impl;

import java.nio.file.*;
import java.util.List;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.services.CameraService;
import de.flothari.ui.settings.AppSettings;

/**
 * ermittelt den Pfad zum capture im Arbeitsverzeichnis
 */
@Creatable
@Singleton
public class LocalFileCameraService implements CameraService
{

	@Override
	public Path capture() throws Exception
	{
		Path workDir = new AppSettings().getWorkDir();
		for (String name : List.of("capture.png", "capture.jpg", "capture.jpeg"))
		{
			Path p = workDir.resolve(name);
			if (Files.exists(p))
				return p;
		}
		throw new IllegalStateException("Kein capture.(png|jpg|jpeg) im WorkDir gefunden: " + workDir);
	}
}
