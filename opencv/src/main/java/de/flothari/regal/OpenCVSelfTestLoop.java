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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

public class OpenCVSelfTestLoop
{

	private static final String REF_FOLDER_NAME = "ref_bilder";
	private static final String CAPTURE_FILENAME = "capture.jpg";

	private static final double LOWE_RATIO = 0.75;
	private static final double MIN_SCORE_THRESHOLD = 0.05; // ggf. feintunen

	private static class RefImage
	{
		final String shelfCode;
		final Path filePath;
		final Mat descriptors;

		RefImage(String shelfCode, Path filePath, Mat descriptors)
		{
			this.shelfCode = shelfCode;
			this.filePath = filePath;
			this.descriptors = descriptors;
		}
	}

	private final ORB orb;
	private final BFMatcher matcher;
	private final List<RefImage> references;

	private final Path baseDir;
	private final Path refDir;
	private final Path capturePath;

	public OpenCVSelfTestLoop()
	{
		// OpenCV native laden (Variante A)
		System.loadLibrary("opencv_java460");
		System.out.println("OpenCV Version: " + Core.VERSION);

		this.baseDir = Path.of(System.getProperty("user.dir"));
		this.refDir = baseDir.resolve(REF_FOLDER_NAME);
		this.capturePath = baseDir.resolve(CAPTURE_FILENAME);

		this.orb = ORB.create();
		this.matcher = BFMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING, false);
		this.references = new ArrayList<>();
	}

	public void loadReferences() throws Exception
	{
		if (!Files.isDirectory(refDir))
		{
			throw new IllegalStateException("Referenzordner nicht gefunden: " + refDir.toAbsolutePath());
		}

		List<Path> files = new ArrayList<>();
		try (Stream<Path> stream = Files.list(refDir))
		{
			stream.filter(p ->
			{
				String n = p.getFileName().toString().toLowerCase();
				return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
			}).sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).forEach(files::add);
		}
		if (files.isEmpty())
		{
			throw new IllegalStateException("Keine Bilder in: " + refDir.toAbsolutePath());
		}

		System.out.println("Arbeitsverzeichnis: " + baseDir.toAbsolutePath());
		System.out.println("Referenzordner:      " + refDir.toAbsolutePath());
		System.out.println("Capture-Datei:       " + capturePath.toAbsolutePath());
		System.out.println();
		System.out.println("Lade Referenzen...");

		for (Path p : files)
		{
			String fn = p.getFileName().toString();
			String shelf = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;

			Mat img = Imgcodecs.imread(p.toAbsolutePath().toString(), Imgcodecs.IMREAD_COLOR);
			if (img.empty())
			{
				System.err.println("Kann nicht laden: " + p);
				continue;
			}

			Mat gray = new Mat();
			Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

			MatOfKeyPoint kp = new MatOfKeyPoint();
			Mat desc = new Mat();
			orb.detectAndCompute(gray, new Mat(), kp, desc);

			if (desc.empty() || desc.type() != CvType.CV_8U)
			{
				System.err.println("Keine gültigen ORB-Deskriptoren: " + fn);
				continue;
			}

			references.add(new RefImage(shelf, p, desc));
			System.out.println("OK: " + shelf + " | keypoints=" + kp.rows() + " | desc=" + desc.rows());
		}

		if (references.isEmpty())
		{
			throw new IllegalStateException("Keine gültigen Referenzen geladen.");
		}

		System.out.println("\nFertig. Referenzen: " + references.size());
		System.out.println("Steuerung: 'w' = nächstes Bild | 'q' = Ende\n");
	}

	private void copyToCapture(Path src) throws Exception
	{
		Files.copy(src, capturePath, StandardCopyOption.REPLACE_EXISTING);
	}

	private String identifyCaptureAndReturnBest()
	{
		Mat img = Imgcodecs.imread(capturePath.toAbsolutePath().toString(), Imgcodecs.IMREAD_COLOR);
		if (img.empty())
		{
			return "FEHLER: capture.jpg konnte nicht geladen werden";
		}

		Mat gray = new Mat();
		Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

		MatOfKeyPoint kp = new MatOfKeyPoint();
		Mat desc = new Mat();
		orb.detectAndCompute(gray, new Mat(), kp, desc);

		if (desc.empty())
		{
			return "UNBEKANNT (keine Deskriptoren)";
		}

		double bestScore = 0.0;
		String bestShelf = null;

		for (RefImage ref : references)
		{
			double score = computeMatchScore(ref.descriptors, desc);
			if (score > bestScore)
			{
				bestScore = score;
				bestShelf = ref.shelfCode;
			}
		}

		if (bestShelf == null || bestScore < MIN_SCORE_THRESHOLD)
		{
			return "UNBEKANNT (bestScore=" + bestScore + ")";
		}

		return bestShelf + " (score=" + bestScore + ")";
	}

	private double computeMatchScore(Mat refDesc, Mat queryDesc)
	{
		List<MatOfDMatch> knn = new ArrayList<>();
		matcher.knnMatch(refDesc, queryDesc, knn, 2);

		int total = knn.size();
		if (total == 0)
			return 0.0;

		int good = 0;
		for (MatOfDMatch m : knn)
		{
			DMatch[] d = m.toArray();
			if (d.length < 2)
				continue;
			if (d[0].distance < LOWE_RATIO * d[1].distance)
				good++;
		}
		return (double) good / (double) total;
	}

	public void runLoop() throws Exception
	{
		int idx = 0;

		while (true)
		{
			RefImage current = references.get(idx);

			// 1) ref -> capture.jpg kopieren
			copyToCapture(current.filePath);

			// 2) Erkennung ausführen
			String result = identifyCaptureAndReturnBest();

			// 3) Ausgabe
			System.out.println("--------------------------------------------------");
			System.out.println("Testbild:  " + current.filePath.getFileName());
			System.out.println("Erwartet:  " + current.shelfCode);
			System.out.println("Erkannt:   " + result);
			System.out.println("--------------------------------------------------");
			System.out.println("Taste: w=weiter | q=ende");

			// 4) warten auf Eingabe
			String cmd = readSingleCommand();
			if ("q".equals(cmd))
			{
				System.out.println("Beende.");
				break;
			}
			if ("w".equals(cmd))
			{
				idx = (idx + 1) % references.size();
			} else
			{
				// andere Tasten ignorieren, gleiche Referenz nochmal anzeigen
			}
		}
	}

	private String readSingleCommand() throws Exception
	{
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		while (true)
		{
			System.out.print("> ");
			String line = br.readLine();
			if (line == null)
				return "q";
			line = line.trim().toLowerCase();
			if (line.equals("w") || line.equals("q"))
				return line;
		}
	}

	public static void main(String[] args) throws Exception
	{
		OpenCVSelfTestLoop app = new OpenCVSelfTestLoop();
		app.loadReferences();
		app.runLoop();
	}
}
