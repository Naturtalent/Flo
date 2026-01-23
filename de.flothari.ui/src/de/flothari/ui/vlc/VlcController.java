package de.flothari.ui.vlc;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.time.Instant;
import java.time.Duration;

public class VlcController
{

	private final String host;
	private final int port;
	private Process vlcProcess;

	public VlcController(String host, int port)
	{
		this.host = host;
		this.port = port;
	}

	/** Startet VLC extern + öffnet nach Möglichkeit die USB-Kamera. */
	public void startCamera() throws IOException
	{
		if (isRunning())
		{
			return; // schon gestartet
		}

		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);

		// VLC executable: wenn nicht im PATH, hier absolute Pfade setzen
		String vlcExe = resolveVlcExecutable(os);

		// Snapshot-Zielordner
		//Path snapshotDir = Paths.get(System.getProperty("user.home"), "vlc-snapshots");
		Path snapshotDir = Paths.get(System.getProperty("user.dir"));

		Files.createDirectories(snapshotDir);
		
		List<String> cmd = new ArrayList<>();
		cmd.add(vlcExe);

		// Quelle (Kamera) OS-abhängig
		if (os.contains("win"))
		{
			// Windows: DirectShow. Oft reicht dshow:// und VLC nimmt Default-Devices.
			// Falls es nicht klappt: dshow-vdev explizit setzen (siehe weiter unten).
			cmd.add("dshow://");
		} else
		{
			// Linux: v4l2 device
			cmd.add("v4l2:///dev/video0");
		}

		
		
		// RC-Interface via TCP-Socket
		cmd.add("--extraintf");
		cmd.add("rc");
		cmd.add("--rc-host");
		cmd.add(host + ":" + port);

		// Snapshot-Settings
		cmd.add("--snapshot-path=" + snapshotDir.toAbsolutePath());		
		cmd.add("--snapshot-prefix=capture");
		cmd.add("--snapshot-format=png");
		cmd.add("--no-snapshot-sequential");  // verhindert die laufende Incrementierung

		// Optional: Fenster direkt anzeigen, Audio stummschalten etc.
		// cmd.add("--no-audio");

		// Optional: Titel / UI-Einstellungen
		// cmd.add("--video-title=Regalsystem Kamera");

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);
		vlcProcess = pb.start();

		// Optional: Output lesen (Debug)
		// new Thread(() -> drain(vlcProcess.getInputStream())).start();
	}

	/** Löst einen Snapshot in VLC aus (speichert im snapshot-path). */
	public void snapshot() throws IOException
	{
		// VLC RC: "snapshot" triggert Speicherung im konfigurierten snapshot-path
		sendRc("snapshot");
	}

	/** Stoppt die Wiedergabe. */
	public void stop() throws IOException
	{
		sendRc("stop");
	}

	/** Beendet VLC sauber. */
	public void quit() throws IOException
	{
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
		}
	}

	public boolean isRunning()
	{
		return vlcProcess != null && vlcProcess.isAlive();
	}

	// ---------------- intern ----------------

	private void sendRc(String command) throws IOException
	{
		try (Socket socket = new Socket(host, port);
				BufferedWriter out = new BufferedWriter(
						new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)))
		{
			out.write(command);
			out.write("\n");
			out.flush();
		}
	}

	private String resolveVlcExecutable(String os)
	{
		// 1) PATH-Variante:
		// Wenn "vlc" im PATH ist, reicht "vlc".
		// 2) Sonst: hier typische Pfade ergänzen.

		if (os.contains("win"))
		{
			// häufigster Pfad unter Windows:
			Path p1 = Paths.get("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe");
			Path p2 = Paths.get("C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe");
			if (Files.exists(p1))
				return p1.toString();
			if (Files.exists(p2))
				return p2.toString();
			return "vlc"; // fallback PATH
		} else
		{
			// Linux: meist per Paketmanager vorhanden
			return "vlc";
		}
	}

	@SuppressWarnings("unused")
	private static void drain(InputStream in)
	{
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			while (br.readLine() != null)
			{
				/* ignore */ }
		} catch (IOException ignored)
		{
		}
	}

	/**
	 * Optional: eigener Dateiname (wird nicht von VLC RC genutzt), nur als Helper
	 * für dich.
	 */
	public static String timestampName()
	{
		return "vlc_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png";
	}
	
	
	public void snapshotToCapturePng() throws IOException, InterruptedException
	{
		Path appDir = Paths.get(System.getProperty("user.dir"));
		Path target = appDir.resolve("capture.png");

		// 1) Snapshot anstoßen (VLC erzeugt capture<timestamp>.png)
		sendRc("snapshot");

		// 2) Kurz warten, bis Datei wirklich geschrieben ist (robust)
		Path newest = waitForNewestCapture(appDir, Duration.ofSeconds(2));

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
}
