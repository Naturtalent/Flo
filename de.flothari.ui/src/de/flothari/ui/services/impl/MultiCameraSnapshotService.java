package de.flothari.ui.services.impl;

import java.nio.file.*;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import de.flothari.ui.settings.AppSettings;

@Creatable
@Singleton
public class MultiCameraSnapshotService
{

	private static volatile boolean OPENCV_LOADED = false;

	public Path captureTo(String device, String fileName) throws Exception
	{
		ensureOpenCvLoaded();

		Path workDir = new AppSettings().getWorkDir();
		Files.createDirectories(workDir);

		Path target = workDir.resolve(fileName);

		VideoCapture cap = open(device);
		try
		{
			if (!cap.isOpened())
			{
				throw new IllegalStateException("Kamera konnte nicht geöffnet werden: " + device);
			}

			Mat frame = new Mat();
			try
			{
				if (!cap.read(frame) || frame.empty())
				{
					throw new IllegalStateException("Kein Frame von Kamera erhalten: " + device);
				}

				boolean ok = Imgcodecs.imwrite(target.toString(), frame);
				if (!ok)
					throw new IllegalStateException("Konnte Bild nicht speichern: " + target);

			} finally
			{
				frame.release();
			}

		} finally
		{
			cap.release(); // ✅ wichtig
		}

		return target;
	}

	private VideoCapture open(String device)
	{
		VideoCapture cap = new VideoCapture();

		if (device.startsWith("/dev/video"))
		{
			cap.open(device, Videoio.CAP_V4L2);
			if (cap.isOpened())
				return cap;
		}

		// fallback: device als Index interpretieren
		try
		{
			int idx = Integer.parseInt(device.trim());
			cap.open(idx, Videoio.CAP_V4L2);
		} catch (NumberFormatException ignored)
		{
		}

		return cap;
	}

	private static void ensureOpenCvLoaded()
	{
		if (OPENCV_LOADED)
			return;
		synchronized (MultiCameraSnapshotService.class)
		{
			if (OPENCV_LOADED)
				return;
			System.loadLibrary("opencv_java460");
			OPENCV_LOADED = true;
		}
	}
}
