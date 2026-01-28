package de.flothari.ui.services.impl;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.workbench.UIEvents;
import org.eclipse.swt.graphics.ImageData;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import de.flothari.ui.settings.AppSettings;

@Creatable
@Singleton
public class CameraLiveService
{

	@Inject
	private IEventBroker eventBroker;

	public interface FrameListener
	{
		void onFrame(ImageData imageData);

		void onError(String message);
	}

	private static volatile boolean OPENCV_LOADED = false;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private Thread worker;
	private volatile FrameListener listener;

	public boolean isRunning()
	{
		return running.get();
	}

	public void setFrameListener(FrameListener listener)
	{
		this.listener = listener;
	}

	public synchronized void start()
	{
		if (running.get())
			return;

		ensureOpenCvLoaded();
		running.set(true);
		
		fireEnablementUpdate();

		String device = new AppSettings().getAudioDeviceName(); // hier als Kamera-Device verwendet
		if (device == null || device.isBlank())
		{
			device = "/dev/video2"; // Default
		}

		final String finalDevice = device.trim();

		worker = new Thread(() -> runCaptureLoop(finalDevice), "camera-live");
		worker.setDaemon(true);
		worker.start();
	}

	public synchronized void stop()
	{
		if (!running.get()) return;
		running.set(false);
		
		fireEnablementUpdate();   
		
		if (worker != null)
			worker.interrupt();
		worker = null;
	}

	@PreDestroy
	public void shutdown()
	{
		stop();
	}

	// ---------------- intern ----------------

	private void runCaptureLoop(String device)
	{
		VideoCapture cap = new VideoCapture();

		boolean opened = false;

		// Linux: /dev/video2 direkt probieren
		if (device.startsWith("/dev/video"))
		{
			opened = cap.open(device, Videoio.CAP_V4L2);
		} else
		{
			// falls Nutzer nur "2" eingibt
			try
			{
				int idx = Integer.parseInt(device);
				opened = cap.open(idx, Videoio.CAP_V4L2);
			} catch (NumberFormatException ignored)
			{
			}
		}

		// Fallback: wenn /dev/video2 nicht ging, versuche Index 2
		if (!opened)
		{
			cap.release();
			cap = new VideoCapture(2, Videoio.CAP_V4L2);
		}

		// Optional: Auflösung setzen (muss unterstützt werden)
		cap.set(Videoio.CAP_PROP_FRAME_WIDTH, 640);
		cap.set(Videoio.CAP_PROP_FRAME_HEIGHT, 480);

		if (!cap.isOpened())
		{
			running.set(false);
			FrameListener l = listener;
			if (l != null)
				l.onError("Kamera konnte nicht geöffnet werden: " + device);
			cap.release();
			return;
		}

		Mat frame = new Mat();
		Mat rgb = new Mat();

		while (running.get())
		{
			if (!cap.read(frame) || frame.empty())
			{
				sleep(30);
				continue;
			}

			Imgproc.cvtColor(frame, rgb, Imgproc.COLOR_BGR2RGB);

			FrameListener l = listener;
			if (l != null)
			{
				l.onFrame(matToImageData(rgb));
			}

			sleep(30); // ~33 FPS
		}

		cap.release();
		frame.release();
		rgb.release();
	}

	private static ImageData matToImageData(Mat rgb)
	{
		int width = rgb.cols();
		int height = rgb.rows();

		byte[] data = new byte[width * height * 3];
		rgb.get(0, 0, data);

		org.eclipse.swt.graphics.PaletteData palette = new org.eclipse.swt.graphics.PaletteData(0xFF0000, 0x00FF00,
				0x0000FF);

		ImageData imageData = new ImageData(width, height, 24, palette);

		int bytesPerLine = imageData.bytesPerLine;
		byte[] dest = imageData.data;

		int srcStride = width * 3;
		for (int y = 0; y < height; y++)
		{
			System.arraycopy(data, y * srcStride, dest, y * bytesPerLine, srcStride);
		}

		return imageData;
	}

	private static void ensureOpenCvLoaded()
	{
		if (OPENCV_LOADED)
			return;
		synchronized (CameraLiveService.class)
		{
			if (OPENCV_LOADED)
				return;
			System.loadLibrary("opencv_java460");
			OPENCV_LOADED = true;
		}
	}

	private static void sleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		} catch (InterruptedException ignored)
		{
			Thread.currentThread().interrupt();
		}
	}

	private void fireEnablementUpdate()
	{
		if (eventBroker != null)
		{
			// Re-evaluates @CanExecute for commands/handled items
			eventBroker.post(UIEvents.REQUEST_ENABLEMENT_UPDATE_TOPIC, UIEvents.ALL_ELEMENT_ID);
		}
	}

}
