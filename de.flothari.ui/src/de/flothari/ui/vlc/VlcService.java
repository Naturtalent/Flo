package de.flothari.ui.vlc;

import static de.flothari.ui.lifecycle.LifeCycle.CTX_VLC_RUNNING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.UIEvents;

import de.flothari.ui.settings.AppSettings;

import org.eclipse.e4.core.services.events.IEventBroker;

@Creatable
@Singleton
public class VlcService
{

	private final String host = "127.0.0.1";
	private final int port = 4212;

	private Process vlcProcess;
	
	private Path workDir;

	@Inject
	private IEclipseContext context;
	@Inject
	private IEventBroker eventBroker;
	// @Inject
	// private IWorkbench workbench; // optional, nur falls du später UI-Zugriff
	// brauchst

	public boolean isRunning()
	{
		return vlcProcess != null && vlcProcess.isAlive();
	}

	public void startCamera() throws IOException
	{
		if (isRunning())
			return;

		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String vlcExe = resolveVlcExecutable(os);

		// App-Verzeichnis (dort liegt später capture.png)
		AppSettings s = new AppSettings();
		workDir = s.getWorkDir();
		String audioDev = new AppSettings().getAudioDeviceName();		

		List<String> cmd = new ArrayList<>();
		cmd.add(vlcExe);

		//if (os.contains("win"))
		//{
		//	cmd.add("dshow://");
			// Optional, falls Default-Device nicht passt:
			// cmd.add(":dshow-vdev=USB Camera");
		//} else
		//{
		//	cmd.add("v4l2:///dev/video2");
		//}

		if (os.contains("win"))
		{
			cmd.add("dshow://");
			// unter Windows wird 'audioDev'
		} else
		{
			cmd.add("v4l2://"+audioDev);
		}

		

		// RC Interface
		cmd.add("--extraintf");
		cmd.add("rc");
		cmd.add("--rc-host");
		cmd.add(host + ":" + port);

		// Snapshot-Settings (VLC erzeugt capture<suffix>.png; wir benennen danach um)
		cmd.add("--snapshot-path=" + workDir.toAbsolutePath());
		cmd.add("--snapshot-prefix=capture");
		cmd.add("--snapshot-format=png");

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		vlcProcess = pb.start();

		registerExitWatcher();
		
		setRunning(true);
	}
	
	// sichert enablement wenn vlc geschlossen wird
	private void registerExitWatcher() {
	    if (vlcProcess == null) return;

	    vlcProcess.onExit().thenRun(() -> {
	        // VLC ist beendet (normal/Crash/Fenster-X)
	        vlcProcess = null;
	        setRunning(false);

	        // Optional: hier könntest du noch loggen, UI-Status setzen, etc.
	        // Wichtig: keine Dialoge erzwingen beim Shutdown, aber Status/Enablement ist ok.
	    });
	}

	public void quit() throws IOException
	{
		if (!isRunning())
			return;

		try
		{
			sendRc("quit");
		} finally
		{
			if (vlcProcess != null)
			{
				vlcProcess.destroy();
				vlcProcess = null;
			}
			setRunning(false);
		}
	}

	/**
	 * Snapshot anstoßen (hier nur RC; Umbenennung zu capture.png machst du wie
	 * besprochen danach).
	 */
	public void snapshot() throws IOException
	{
		if (!isRunning())
			return;
		sendRc("snapshot");
	}

	// ---- intern ----

	private void setRunning(boolean running)
	{
		context.set(CTX_VLC_RUNNING, Boolean.valueOf(running));

		// e4 soll @CanExecute neu bewerten (Toolbar/Menu sofort aktualisieren)
		eventBroker.post(UIEvents.REQUEST_ENABLEMENT_UPDATE_TOPIC, UIEvents.ALL_ELEMENT_ID);
	}

	private void sendRc(String command) throws IOException
	{
		try (var socket = new java.net.Socket(host, port);
				var out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(),
						java.nio.charset.StandardCharsets.UTF_8)))
		{
			out.write(command);
			out.write("\n");
			out.flush();
		}
	}

	private String resolveVlcExecutable(String os)
	{
		if (os.contains("win"))
		{
			var p1 = java.nio.file.Paths.get("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe");
			var p2 = java.nio.file.Paths.get("C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe");
			if (java.nio.file.Files.exists(p1))
				return p1.toString();
			if (java.nio.file.Files.exists(p2))
				return p2.toString();
			return "vlc";
		}
		return "vlc";
	}

	public void snapshotToCapturePng() throws IOException, InterruptedException
	{
		//Path appDir = Paths.get(System.getProperty("user.dir"));
		//Path target = appDir.resolve("capture.png");
		Path target = workDir.resolve("capture.png");		

		// 1) Snapshot anstoßen (VLC erzeugt capture<timestamp>.png)
		sendRc("snapshot");

		// 2) Kurz warten, bis Datei wirklich geschrieben ist (robust)
		Path newest = waitForNewestCapture(workDir, Duration.ofSeconds(2));

		// 3) Ziel überschreiben
		Files.move(newest, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);		
	}

	private Path waitForNewestCapture(Path dir, Duration timeout) throws IOException, InterruptedException
	{
		Instant end = Instant.now().plus(timeout);

		Path candidate = null;
		FileTime bestTime = null;

		while (Instant.now().isBefore(end))
		{
			Optional<Path> newest = findNewestCaptureFile(dir);

			if (newest.isPresent())
			{
				Path p = newest.get();
				FileTime t = Files.getLastModifiedTime(p);

				// wenn neu oder stabiler Kandidat: merken und kurz warten, ob VLC noch schreibt
				if (bestTime == null || t.compareTo(bestTime) > 0)
				{
					candidate = p;
					bestTime = t;
				}

				// Kleine Stabilitätsprüfung: Größe bleibt gleich?
				long s1 = Files.size(candidate);
				Thread.sleep(80);
				long s2 = Files.size(candidate);

				if (s1 == s2 && s2 > 0)
				{
					return candidate;
				}
			}

			Thread.sleep(80);
		}

		throw new IOException("Kein neuer capture*.png Snapshot im Timeout gefunden in: " + dir);
	}

	private Optional<Path> findNewestCaptureFile(Path dir) throws IOException
	{
		try (Stream<Path> s = Files.list(dir))
		{
			return s.filter(p ->
			{
				String n = p.getFileName().toString().toLowerCase();
				return n.startsWith("capture") && n.endsWith(".png") && !n.equals("capture.png");
			}).max(Comparator.comparing(p ->
			{
				try
				{
					return Files.getLastModifiedTime(p);
				} catch (IOException e)
				{
					return FileTime.fromMillis(0);
				}
			}));
		}
	}

	@PreDestroy
	public void shutdown()
	{
		if (isRunning())
		{
			try
			{
				quit(); // sauber beenden
			} catch (Exception e)
			{
				// letztes Fallback – wir sind im Shutdown
				if (vlcProcess != null)
				{
					vlcProcess.destroy();
				}
			}
		}
	}
}
