package de.flothari.regal;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FormIdentifierLoop
{

	// Dateinamen/Ordner relativ zum Arbeitsverzeichnis (== Verzeichnis, wo du
	// startest)
	private static final String CAPTURE_FILENAME = "capture.jpg";
	private static final String REF_FOLDER_NAME = "ref_bilder";

	// Kamera-Aufnahme (rpicam)
	private static final String CAPTURE_CMD_TEMPLATE = "rpicam-still --nopreview --width 640 --height 480 --timeout 500 -o \"%s\"";

	// Matching-Parameter
	private static final double LOWE_RATIO = 0.75;
	private static final double MIN_SCORE_THRESHOLD = 0.05; // ggf. feinjustieren

	private static class RefImage
	{
		final String shelfCode; // Dateiname ohne Extension
		final Mat descriptors; // ORB Descriptors (CV_8U)
		final int descriptorRows; // zur schnellen Auswertung

		RefImage(String shelfCode, Mat descriptors)
		{
			this.shelfCode = shelfCode;
			this.descriptors = descriptors;
			this.descriptorRows = descriptors.rows();
		}
	}

	private final ORB orb;
	private final BFMatcher matcher;
	private final List<RefImage> referenceImages = new ArrayList<>();

	private final String baseDir;
	private final String refFolder;
	private final String capturePath;
	private final String captureCmd;

	public FormIdentifierLoop()
	{
		// Native OpenCV laden:
		// Option A: loadLibrary (benötigt java.library.path passend)
		System.loadLibrary("opencv_java460");

		// Arbeitsverzeichnis (dort liegt dein jar / oder du startest dort)
		this.baseDir = new File(System.getProperty("user.dir")).getAbsolutePath();
		this.refFolder = new File(baseDir, REF_FOLDER_NAME).getAbsolutePath();
		this.capturePath = new File(baseDir, CAPTURE_FILENAME).getAbsolutePath();
		this.captureCmd = String.format(CAPTURE_CMD_TEMPLATE, this.capturePath);

		// ORB + Matcher
		this.orb = ORB.create(); // Standardwerte
		this.matcher = BFMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING, false);
	}

	public void loadReferenceImages()
	{
		File folder = new File(refFolder);
		if (!folder.exists() || !folder.isDirectory())
		{
			throw new IllegalArgumentException("Referenzordner existiert nicht: " + refFolder);
		}

		File[] files = folder.listFiles((dir, name) ->
		{
			String n = name.toLowerCase();
			return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
		});

		if (files == null || files.length == 0)
		{
			throw new IllegalStateException("Keine Referenzbilder im Ordner gefunden: " + refFolder);
		}

		System.out.println("Arbeitsverzeichnis: " + baseDir);
		System.out.println("Referenzordner:      " + refFolder);
		System.out.println("Capture-Datei:       " + capturePath);
		System.out.println();
		System.out.println("Lade Referenzbilder...");

		for (File f : files)
		{
			Mat img = Imgcodecs.imread(f.getAbsolutePath(), Imgcodecs.IMREAD_COLOR);
			if (img.empty())
			{
				System.err.println("Konnte Bild nicht laden: " + f.getAbsolutePath());
				continue;
			}

			Mat gray = new Mat();
			Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

			MatOfKeyPoint keypoints = new MatOfKeyPoint();
			Mat descriptors = new Mat();
			orb.detectAndCompute(gray, new Mat(), keypoints, descriptors);

			if (descriptors.empty() || descriptors.type() != CvType.CV_8U)
			{
				System.err.println("Keine gültigen ORB-Deskriptoren bei: " + f.getName());
				continue;
			}

			String fileName = f.getName();
			int dot = fileName.lastIndexOf('.');
			String shelfCode = (dot > 0) ? fileName.substring(0, dot) : fileName;

			referenceImages.add(new RefImage(shelfCode, descriptors));
			System.out.println("Geladen: " + shelfCode + " | Keypoints: " + keypoints.rows() + " | Descriptors: "
					+ descriptors.rows());
		}

		if (referenceImages.isEmpty())
		{
			throw new IllegalStateException("Es wurden keine gültigen Referenzbilder geladen.");
		}

		System.out.println("Fertig. Anzahl Referenzen: " + referenceImages.size());
		System.out.println();
	}

	public boolean captureImage()
	{
		try
		{
			System.out.println("Mache Foto...");
			ProcessBuilder pb = new ProcessBuilder("bash", "-c", captureCmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();

			try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream())))
			{
				String line;
				while ((line = br.readLine()) != null)
				{
					// optional: auskommentieren, wenn zu viel
					// System.out.println("[rpicam] " + line);
				}
			}

			int exit = p.waitFor();
			if (exit != 0)
			{
				System.err.println("rpicam-still Fehlercode: " + exit);
				return false;
			}

			if (!Files.exists(new File(capturePath).toPath()))
			{
				System.err.println("Capture-Datei nicht gefunden: " + capturePath);
				return false;
			}

			System.out.println("Foto gespeichert: " + capturePath);
			return true;
		} catch (Exception e)
		{
			System.err.println("Fehler bei Aufnahme: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	public String identifyCaptured()
	{
		Mat img = Imgcodecs.imread(capturePath, Imgcodecs.IMREAD_COLOR);
		if (img.empty())
		{
			throw new IllegalArgumentException("Konnte aufgenommenes Bild nicht laden: " + capturePath);
		}

		Mat gray = new Mat();
		Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

		MatOfKeyPoint keypoints = new MatOfKeyPoint();
		Mat descriptors = new Mat();
		orb.detectAndCompute(gray, new Mat(), keypoints, descriptors);

		if (descriptors.empty())
		{
			return "UNBEKANNT";
		}

		double bestScore = 0.0;
		String bestShelf = null;

		for (RefImage ref : referenceImages)
		{
			double score = computeMatchScore(ref.descriptors, descriptors);
			System.out.println("Score zu " + ref.shelfCode + " = " + score);

			if (score > bestScore)
			{
				bestScore = score;
				bestShelf = ref.shelfCode;
			}
		}

		if (bestShelf == null || bestScore < MIN_SCORE_THRESHOLD)
		{
			System.out.println("Kein ausreichend gutes Matching. BestScore=" + bestScore);
			return "UNBEKANNT";
		}

		System.out.println("Beste Übereinstimmung: " + bestShelf + " (Score " + bestScore + ")");
		return bestShelf;
	}

	private double computeMatchScore(Mat refDesc, Mat queryDesc)
	{
		// knnMatch: für jedes ref-Feature die 2 besten Matches im Query
		List<MatOfDMatch> knnMatches = new ArrayList<>();
		matcher.knnMatch(refDesc, queryDesc, knnMatches, 2);

		int total = knnMatches.size();
		if (total == 0)
			return 0.0;

		int good = 0;
		for (MatOfDMatch m : knnMatches)
		{
			DMatch[] d = m.toArray();
			if (d.length < 2)
				continue;

			if (d[0].distance < LOWE_RATIO * d[1].distance)
			{
				good++;
			}
		}

		return (double) good / (double) total;
	}

	public static void main(String[] args)
	{
		try
		{
			// Version ausgeben (hilft beim Debug)
			System.out.println("OpenCV Version: " + Core.VERSION);

			FormIdentifierLoop app = new FormIdentifierLoop();
			app.loadReferenceImages();

			System.out.println("Bereit. Enter = Foto+Erkennung | q = Ende");
			BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

			while (true)
			{
				System.out.print("> ");
				String line = console.readLine();
				if (line == null)
					break;
				line = line.trim().toLowerCase();

				if (line.equals("q") || line.equals("quit") || line.equals("exit"))
				{
					System.out.println("Beende.");
					break;
				}

				if (!app.captureImage())
				{
					System.out.println("Aufnahme fehlgeschlagen.");
					continue;
				}

				String shelf = app.identifyCaptured();
				System.out.println(">>> Erkannte Ablage: " + shelf);
				System.out.println();
			}
		} catch (UnsatisfiedLinkError ule)
		{
			System.err.println("Native OpenCV Library konnte nicht geladen werden.");
			System.err.println("Tipp: java -Djava.library.path=/usr/lib/jni -jar ...");
			ule.printStackTrace();
		} catch (Exception e)
		{
			e.printStackTrace();
		}
	}
}
