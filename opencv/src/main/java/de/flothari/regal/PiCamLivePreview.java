package de.flothari.regal;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PiCamLivePreview
{

	// Passe das an, wenn "which rpicam-vid" etwas anderes liefert (z.B.
	// /usr/bin/rpicam-vid)
	private static final String RPICAM_VID_BIN = "rpicam-vid";

	// Preview-Einstellungen (HDMI Display 0)
	// Tipp: Für flüssig auf Zero 2W eher 640x480 oder 1280x720 ausprobieren
	private static final String[] PREVIEW_CMD = new String[] {
	        "rpicam-vid",
	        "--width", "640",
	        "--height", "480",
	        "--framerate", "30",
	        "--timeout", "0"
	};



	public static void main(String[] args)
	{
		System.out.println("PiCam Live-Preview Test");
		System.out.println("Startet rpicam-vid als Livebild auf HDMI.");
		System.out.println("Drücke ENTER um zu stoppen, oder 'q' + ENTER zum Beenden.");

		Process preview = null;

		try (BufferedReader console = new BufferedReader(new InputStreamReader(System.in)))
		{

			// 1) Preview starten
			preview = startPreview();

			if (preview == null)
			{
				System.err.println("Preview konnte nicht gestartet werden.");
				return;
			}

			// 2) Warten auf User-Input
			while (true)
			{
				System.out.print("> ");
				String line = console.readLine();
				if (line == null)
					break;
				line = line.trim().toLowerCase();

				// ENTER oder q -> beenden
				if (line.isEmpty() || line.equals("q") || line.equals("quit") || line.equals("exit"))
				{
					break;
				}

				System.out.println("Drücke einfach ENTER um die Preview zu stoppen.");
			}

		} catch (Exception e)
		{
			System.err.println("Fehler: " + e.getMessage());
			e.printStackTrace();
		} finally
		{
			// 3) Preview stoppen
			stopPreview(preview);
			System.out.println("Beendet.");
		}
	}

	private static Process startPreview()
	{
		try
		{
			System.out.println("Starte Live-Preview: " + String.join(" ", PREVIEW_CMD));

			ProcessBuilder pb = new ProcessBuilder(PREVIEW_CMD);
			pb.redirectErrorStream(true);
			Process p = pb.start();

			// Ausgabe in separatem Thread lesen, damit nichts blockiert und du Fehler
			// siehst
			Thread logThread = new Thread(() ->
			{
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
				{
					String line;
					while ((line = br.readLine()) != null)
					{
						System.out.println("[rpicam-vid] " + line);
					}
				} catch (Exception ignored)
				{
				}
			});
			logThread.setDaemon(true);
			logThread.start();

			// Kurz warten, ob der Prozess sofort stirbt (z.B. "no cameras available")
			Thread.sleep(700);

			if (!p.isAlive())
			{
				int code = p.exitValue();
				System.err.println("rpicam-vid hat sofort beendet (ExitCode " + code + ").");
				System.err.println("Siehe Logausgabe oben (z.B. 'no cameras available').");
				return null;
			}

			System.out.println("Preview läuft.");
			return p;

		} catch (Exception e)
		{
			System.err.println("Konnte Preview nicht starten: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	private static void stopPreview(Process p)
	{
		if (p == null)
			return;

		try
		{
			System.out.println("Stoppe Preview...");
			p.destroy();

			if (!p.waitFor(2, TimeUnit.SECONDS))
			{
				p.destroyForcibly();
				p.waitFor(2, TimeUnit.SECONDS);
			}
		} catch (Exception e)
		{
			System.err.println("Fehler beim Stoppen: " + e.getMessage());
		}
	}
}
