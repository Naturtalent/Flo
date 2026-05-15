package de.flothari.ui.settings;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

public class AppSettings
{

	// Wichtig: muss dein Plugin-ID / Bundle-SymbolicName sein
	private static final String NODE = "de.flothari.ui";

	private static final String KEY_VIDEO_DEVICE = "video.device";
	private static final String KEY_AUDIO_DEVICE_NAME = "vlc.audioDeviceName";
	private static final String KEY_WORK_DIR = "app.workDir";
	private static final String KEY_PI_BASE_URL = "pi.baseUrl";
	private static final String KEY_CAMERA_SOURCE = "camera.source"; // "PI" oder "LOCAL"
	
	private static final String KEY_LIVE_CAMERA_SOURCE = "live.camera.source";
	public static final String LIVE_CAMERA_SOURCE_HTTP = "HTTP_PI_CAM";
	public static final String LIVE_CAMERA_SOURCE_USB  = "USB_PI_CAM";

	private final IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(NODE);

	public String getVideoDeviceName()
	{
		String v = prefs.get(KEY_VIDEO_DEVICE, null);
		if (v != null && !v.isBlank())
		{
			return v.trim();
		}
		return defaultVideoDevice();
	}
	
	public void setVideoDeviceName(String value) {
	    if (value == null || value.isBlank()) {
	        // wenn leer -> Key entfernen, damit Default wieder greift
	        prefs.remove(KEY_VIDEO_DEVICE);
	    } else {
	        prefs.put(KEY_VIDEO_DEVICE, value.trim());
	    }
	    flushQuietly();
	}
	
	private String defaultVideoDevice() {
	    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);

	    if (os.contains("win")) {
	        // Für OpenCV unter Windows ist der Index am einfachsten:
	        // 0 = erste Kamera, 1 = zweite, ...
	        return "0";
	    }

	    if (os.contains("linux")) {
	        // stabiler als /dev/video2, weil sich Indizes ändern können:
	        // (falls vorhanden) /dev/v4l/by-id/... nutzen – hier Default klassisch:
	        return "/dev/video2";
	    }

	    // macOS oder unbekannt
	    return "0";
	}
	
	/*
	public String getAudioDeviceName()
	{
		return prefs.get(KEY_AUDIO_DEVICE_NAME, "");
	}

	public void setAudioDeviceName(String value)
	{
		prefs.put(KEY_AUDIO_DEVICE_NAME, value != null ? value.trim() : "");
		flushQuietly();
	}
	*/

	/** Verzeichnis, in dem capture.png + ref_bilder liegen sollen. */
	public Path getWorkDir()
	{
		String def = System.getProperty("user.dir"); // Default: App-Startverzeichnis
		String s = prefs.get(KEY_WORK_DIR, def);
		return Paths.get(s);
	}

	public void setWorkDir(Path dir)
	{
		if (dir == null)
			return;
		prefs.put(KEY_WORK_DIR, dir.toAbsolutePath().toString());
		flushQuietly();
	}

	private void flushQuietly()
	{
		try
		{
			prefs.flush();
		} catch (BackingStoreException e)
		{
			// In der Praxis: loggen (Error Log), aber nicht die UI blockieren
			e.printStackTrace();
		}
	}

	public String getPiBaseUrl()
	{
		return prefs.get(KEY_PI_BASE_URL, "http://raspi-ip:8080");
	}

	public void setPiBaseUrl(String url)
	{
		prefs.put(KEY_PI_BASE_URL, url != null ? url.trim() : "");
		flushQuietly();
	}

	public String getCameraSource()
	{
		return prefs.get(KEY_CAMERA_SOURCE, "PI");
	}

	public void setCameraSource(String value)
	{
		prefs.put(KEY_CAMERA_SOURCE, (value == null || value.isBlank()) ? "PI" : value.trim());
		flushQuietly();
	}
	
	public String getLiveCameraSource() {
	    return prefs.get(KEY_LIVE_CAMERA_SOURCE, LIVE_CAMERA_SOURCE_HTTP);
	}

	public void setLiveCameraSource(String value) {
	    if (value == null || value.isBlank()) {
	        prefs.put(KEY_LIVE_CAMERA_SOURCE, LIVE_CAMERA_SOURCE_HTTP);
	    } else {
	        prefs.put(KEY_LIVE_CAMERA_SOURCE, value.trim());
	    }
	    flushQuietly();
	}
	
	public boolean isHttpLiveCameraSelected() {
	    return LIVE_CAMERA_SOURCE_HTTP.equals(getLiveCameraSource());
	}

	public boolean isUsbLiveCameraSelected() {
	    return LIVE_CAMERA_SOURCE_USB.equals(getLiveCameraSource());
	}

}
