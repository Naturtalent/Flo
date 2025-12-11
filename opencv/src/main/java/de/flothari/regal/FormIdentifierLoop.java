package de.flothari.regal;

import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import org.bytedeco.opencv.opencv_core.DMatch;
import org.bytedeco.opencv.opencv_core.DMatchVector;
import org.bytedeco.opencv.opencv_core.DMatchVectorVector;
import org.bytedeco.opencv.opencv_features2d.ORB;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.NORM_HAMMING;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

public class FormIdentifierLoop
{

	// *** Pfade anpassen ***
	//private static final String REF_FOLDER = "/home/pi/ref_bilder";
	//private static final String CAPTURE_PATH = "/home/pi/captures/capture.jpg";
	
	private static final String REF_FOLDER = "/home/dieter/MeineDaten/Flo/Regalsystem/opencv/refimg";
	private static final String CAPTURE_PATH = "/home/dieter/MeineDaten/Flo/Regalsystem/opencv/capture.jpg";


	// libcamera-Befehl für Foto
	private static final String CAPTURE_CMD = "libcamera-still -n -o " + CAPTURE_PATH + " --width 640 --height 480";

	// Minimaler Match-Score, sonst "UNBEKANNT"
	private static final double MIN_SCORE_THRESHOLD = 0.05;

	private static class RefImage
	{
		String shelfCode; // z.B. "A-21-G"
		Mat imageGray; // Graustufenbild
		KeyPointVector keypoints;
		Mat descriptors;

		RefImage(String shelfCode, Mat imageGray, KeyPointVector keypoints, Mat descriptors)
		{
			this.shelfCode = shelfCode;
			this.imageGray = imageGray;
			this.keypoints = keypoints;
			this.descriptors = descriptors;
		}
	}

	private final ORB orb;
	private final BFMatcher matcher;
	private final List<RefImage> referenceImages = new ArrayList<>();

	public FormIdentifierLoop()
	{
		// Wichtig: ohne Parameter, damit keine create(int)-Fehlermeldung
		this.orb = ORB.create();
		this.matcher = new BFMatcher(NORM_HAMMING, false);
	}

	// Referenzbilder aus Ordner laden
	public void loadReferenceImages(String folderPath)
	{
		File folder = new File(folderPath);
		if (!folder.exists() || !folder.isDirectory())
		{
			throw new IllegalArgumentException("Referenzordner existiert nicht: " + folderPath);
		}

		File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg")
				|| name.toLowerCase().endsWith(".jpeg") || name.toLowerCase().endsWith(".png"));

		if (files == null || files.length == 0)
		{
			throw new IllegalStateException("Keine Referenzbilder im Ordner gefunden: " + folderPath);
		}

		System.out.println("Lade Referenzbilder aus: " + folderPath);

		for (File f : files)
		{
			Mat img = imread(f.getAbsolutePath());
			if (img == null || img.empty())
			{
				System.err.println("Konnte Bild nicht laden: " + f.getAbsolutePath());
				continue;
			}

			Mat gray = new Mat();
			cvtColor(img, gray, COLOR_BGR2GRAY);

			KeyPointVector keypoints = new KeyPointVector();
			Mat descriptors = new Mat();
			orb.detectAndCompute(gray, new Mat(), keypoints, descriptors);

			if (descriptors == null || descriptors.empty())
			{
				System.err.println("Keine Deskriptoren gefunden bei: " + f.getName());
				continue;
			}

			String fileName = f.getName();
			int dotIndex = fileName.lastIndexOf('.');
			String shelfCode = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;

			referenceImages.add(new RefImage(shelfCode, gray, keypoints, descriptors));
			System.out.println("Geladen: " + shelfCode + " mit " + keypoints.size() + " Keypoints");
		}

		if (referenceImages.isEmpty())
		{
			throw new IllegalStateException("Es wurden keine gültigen Referenzbilder geladen.");
		}

		System.out.println("Fertig. Anzahl Referenzbilder: " + referenceImages.size());
	}

	// Ein neues Bild erkennen (Pfad zum aufgenommenen Bild)
	public String identifyForm(String capturedImagePath)
	{
		Mat img = imread(capturedImagePath);
		if (img == null || img.empty())
		{
			throw new IllegalArgumentException("Konnte aufgenommenes Bild nicht laden: " + capturedImagePath);
		}

		Mat gray = new Mat();
		cvtColor(img, gray, COLOR_BGR2GRAY);

		KeyPointVector keypoints = new KeyPointVector();
		Mat descriptors = new Mat();
		orb.detectAndCompute(gray, new Mat(), keypoints, descriptors);

		if (descriptors == null || descriptors.empty())
		{
			throw new IllegalStateException("Keine Deskriptoren im aufgenommenen Bild gefunden.");
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
			System.out.println("Kein ausreichend gutes Matching gefunden. BestScore: " + bestScore);
			return "UNBEKANNT";
		}

		System.out.println("Beste Übereinstimmung: " + bestShelf + " (Score " + bestScore + ")");
		return bestShelf;
	}

	// Matchscore mit knnMatch + Lowe-Ratio
	private double computeMatchScore(Mat refDescriptors, Mat queryDescriptors)
	{
		DMatchVectorVector knnMatches = new DMatchVectorVector();
		matcher.knnMatch(refDescriptors, queryDescriptors, knnMatches, 2); // k = 2

		long totalMatches = knnMatches.size();
		if (totalMatches == 0)
			return 0.0;

		int goodMatches = 0;
		for (long i = 0; i < knnMatches.size(); i++)
		{
			DMatchVector mv = knnMatches.get(i);
			if (mv.size() < 2)
				continue;

			DMatch m1 = mv.get(0);
			DMatch m2 = mv.get(1);

			if (m1.distance() < 0.75 * m2.distance())
			{ // Lowe-Ratio
				goodMatches++;
			}
		}

		return (double) goodMatches / (double) totalMatches;
	}

	// Ein Foto mit libcamera-still aufnehmen
	private static boolean captureImage()
	{
		try
		{
			System.out.println("Mache Foto mit Kamera...");
			ProcessBuilder pb = new ProcessBuilder("bash", "-c", CAPTURE_CMD);
			pb.redirectErrorStream(true);
			Process process = pb.start();

			// Konsolenausgabe von libcamera mitlesen (optional)
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					System.out.println("[libcamera] " + line);
				}
			}

			int exitCode = process.waitFor();
			if (exitCode != 0)
			{
				System.err.println("libcamera-still beendete sich mit Fehlercode: " + exitCode);
				return false;
			}

			File f = new File(CAPTURE_PATH);
			if (!f.exists())
			{
				System.err.println("Aufgenommenes Bild nicht gefunden: " + CAPTURE_PATH);
				return false;
			}

			System.out.println("Foto aufgenommen: " + CAPTURE_PATH);
			return true;
		} catch (Exception e)
		{
			System.err.println("Fehler beim Aufnehmen des Bildes: " + e.getMessage());
			e.printStackTrace();
			return false;
		}
	}

	// MAIN: Endlosschleife
	public static void main(String[] args)
	{
		try
		{
			FormIdentifierLoop identifier = new FormIdentifierLoop();
			identifier.loadReferenceImages(REF_FOLDER);

			System.out.println();
			System.out.println("Bereit.");
			System.out.println("Enter drücken, um ein neues Foto zu machen und zu erkennen.");
			System.out.println("'q' + Enter zum Beenden.");

			BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));

			while (true)
			{
				System.out.print("> ");
				String line = consoleReader.readLine();
				if (line == null)
					break; // EOF
				line = line.trim().toLowerCase();

				if (line.equals("q") || line.equals("quit") || line.equals("exit"))
				{
					System.out.println("Beende Programm.");
					break;
				}

				// Jede andere Eingabe (auch leere) startet eine Aufnahme
				if (!captureImage())
				{
					System.out.println("Aufnahme fehlgeschlagen. Nochmal versuchen?");
					continue;
				}

				String shelfCode = identifier.identifyForm(CAPTURE_PATH);
				System.out.println(">>> Erkannte Ablage: " + shelfCode);
				System.out.println();
			}

		} catch (Exception e)
		{
			System.err.println("Fehler im Programm: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
