package de.flothari.regal;

import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.BFMatcher;
import org.bytedeco.opencv.opencv_features2d.ORB;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.opencv.global.opencv_core.NORM_HAMMING;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

public class FormIdentifier
{

	// Pfad anpassen:
	//private static final String REF_FOLDER = "/home/pi/ref_bilder";
	private static final String REF_FOLDER = "/home/dieter/MeineDaten/Flo/Regalsystem/opencv/refimg";
	private static final double MIN_SCORE_THRESHOLD = 0.05; // Minimaler Score, sonst "unbekannt"

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

	public FormIdentifier()
	{
		// ORB mit z.B. 500 Features
		this.orb = ORB.create();
		this.matcher = new BFMatcher(NORM_HAMMING, false);
	}

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

		System.out.println("Lade Referenzbilder...");

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
			String shelfCode = fileName.substring(0, fileName.lastIndexOf('.')); // Dateiendung entfernen

			referenceImages.add(new RefImage(shelfCode, gray, keypoints, descriptors));
			System.out.println("Geladen: " + shelfCode + " mit " + keypoints.size() + " Keypoints");
		}

		if (referenceImages.isEmpty())
		{
			throw new IllegalStateException("Es wurden keine gültigen Referenzbilder geladen.");
		}

		System.out.println("Fertig. Anzahl Referenzbilder: " + referenceImages.size());
	}

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

	/**
	 * Berechnet einen einfachen Match-Score mittels knnMatch + Lowe-Ratio-Test.
	 * Score = gute_matches / gesamt_matches
	 */
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

	// Sehr simple Vollbildanzeige des Regalfachs
	public static void showShelfOnScreen(String shelfCode)
	{
		JFrame frame = new JFrame("Regalfach");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setUndecorated(true);
		frame.setExtendedState(JFrame.MAXIMIZED_BOTH);

		JLabel label = new JLabel(shelfCode, SwingConstants.CENTER);
		label.setFont(new Font("SansSerif", Font.BOLD, 200));
		frame.getContentPane().add(label);

		frame.setVisible(true);
	}

	public static void main(String[] args)
	{
		if (args.length < 1)
		{
			System.err.println("Bitte Pfad zum aufgenommenen Bild als Argument übergeben.");
			System.err.println("Beispiel: java FormIdentifier /home/pi/captures/form1.jpg");
			System.exit(1);
		}

		String capturedImagePath = args[0];

		FormIdentifier identifier = new FormIdentifier();
		identifier.loadReferenceImages(REF_FOLDER);

		String shelfCode = identifier.identifyForm(capturedImagePath);
		System.out.println("Erkannte Ablage: " + shelfCode);

		// Anzeige auf Monitor
		showShelfOnScreen(shelfCode);
	}
}
