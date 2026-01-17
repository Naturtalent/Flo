package de.flothari.regal;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.highgui.HighGui;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class GroupMatchAnnotator
{

	private static final String REF_FOLDER_NAME = "ref_bilder";
	private static final String CAPTURE_FILENAME = "capture.jpg";
	private static final String OUTPUT_FILENAME = "annotated.jpg";

	// --- Tuning für "wild gedreht" ---
	private static final double LOWE_RATIO = 0.75;

	private static final int MIN_GOOD_MATCHES = 20; // eher hoch, damit False Positives runtergehen
	private static final int MIN_INLIERS = 14;
	private static final double MIN_INLIER_RATIO = 0.35; // inliers / goodMatches
	private static final double RANSAC_REPROJ_THRESH = 4.0;

	private static final int MAX_TOTAL_DETECTIONS = 10; // max 10 Formen im Bild
	private static final int MAX_INSTANCES_PER_REF = 1; // bei dir meist 1; auf 2 erhöhen, wenn doppelt vorkommt

	private static class Ref
	{
		final String name;
		final MatOfKeyPoint keypoints;
		final Mat descriptors;
		final Size size;

		Ref(String name, MatOfKeyPoint keypoints, Mat descriptors, Size size)
		{
			this.name = name;
			this.keypoints = keypoints;
			this.descriptors = descriptors;
			this.size = size;
		}
	}

	private static class MatchResult
	{
		final String name;
		final Point[] quad;
		final int goodMatches;
		final int inliers;
		final double inlierRatio;

		MatchResult(String name, Point[] quad, int goodMatches, int inliers, double inlierRatio)
		{
			this.name = name;
			this.quad = quad;
			this.goodMatches = goodMatches;
			this.inliers = inliers;
			this.inlierRatio = inlierRatio;
		}
	}

	public static void main(String[] args) throws Exception
	{
		System.loadLibrary("opencv_java460");
		System.out.println("OpenCV Version: " + Core.VERSION);

		Path baseDir = Path.of(System.getProperty("user.dir"));
		Path refDir = baseDir.resolve(REF_FOLDER_NAME);
		Path capturePath = baseDir.resolve(CAPTURE_FILENAME);
		Path outPath = baseDir.resolve(OUTPUT_FILENAME);

		if (!Files.isDirectory(refDir))
			throw new IllegalStateException("Referenzordner fehlt: " + refDir);
		if (!Files.exists(capturePath))
			throw new IllegalStateException("capture.jpg fehlt: " + capturePath);

		Mat captureColor = Imgcodecs.imread(capturePath.toString(), Imgcodecs.IMREAD_COLOR);
		if (captureColor.empty())
			throw new IllegalStateException("capture.jpg konnte nicht geladen werden.");

		Mat captureGray = new Mat();
		Imgproc.cvtColor(captureColor, captureGray, Imgproc.COLOR_BGR2GRAY);

		// ORB robuster konfigurieren
		ORB orb = ORB.create(1200, // nfeatures (mehr Features)
				1.2f, // scaleFactor
				10, // nlevels (mehr Levels)
				31, // edgeThreshold
				0, // firstLevel
				2, // WTA_K
				ORB.HARRIS_SCORE, 31, // patchSize
				20 // fastThreshold
		);

		BFMatcher matcher = BFMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING, false);

		// Capture Features (wir werden später maskieren und neu berechnen)
		Mat mask = Mat.ones(captureGray.size(), CvType.CV_8U); // 255 = alles erlaubt
		mask.setTo(new Scalar(255));

		List<Ref> refs = loadReferences(refDir, orb);
		System.out.println("Referenzen geladen: " + refs.size());

		Mat annotated = captureColor.clone();
		List<String> found = new ArrayList<>();
		Map<String, Integer> perRefCount = new HashMap<>();

		int totalDetections = 0;

		// Iterativ suchen, bis max 10 gefunden oder nichts mehr
		while (totalDetections < MAX_TOTAL_DETECTIONS)
		{
			// Features des Capture unter aktueller Maske berechnen
			MatOfKeyPoint capKp = new MatOfKeyPoint();
			Mat capDesc = new Mat();
			orb.detectAndCompute(captureGray, mask, capKp, capDesc);
			if (capDesc.empty())
				break;

			MatchResult best = null;

			for (Ref ref : refs)
			{
				int count = perRefCount.getOrDefault(ref.name, 0);
				if (count >= MAX_INSTANCES_PER_REF)
					continue;

				MatchResult mr = tryFind(ref, capKp, capDesc, matcher);
				if (mr == null)
					continue;

				// Best-Kriterium: InlierRatio zuerst, dann Inliers
				if (best == null || mr.inlierRatio > best.inlierRatio
						|| (mr.inlierRatio == best.inlierRatio && mr.inliers > best.inliers))
				{
					best = mr;
				}
			}

			if (best == null)
				break;

			// Treffer übernehmen
			found.add(best.name);
			perRefCount.put(best.name, perRefCount.getOrDefault(best.name, 0) + 1);
			totalDetections++;

			drawDetection(annotated, best);
			applyMask(mask, best.quad); // Bereich sperren, damit er nicht nochmal gefunden wird

			System.out.println("Gefunden: " + best.name + " | good=" + best.goodMatches + " | inliers=" + best.inliers
					+ " | ratio=" + String.format(Locale.US, "%.2f", best.inlierRatio));
		}

		if (found.isEmpty())
		{
			System.out.println("Gefunden: (keine)");
		} else
		{
			// Unique-Namen als String (wenn du Dopplungen willst, nimm found direkt)
			LinkedHashSet<String> unique = new LinkedHashSet<>(found);
			System.out.println("Gefunden: " + String.join(", ", unique));
		}

		Imgcodecs.imwrite(outPath.toString(), annotated);
		System.out.println("Annotiertes Bild gespeichert: " + outPath.toAbsolutePath());

		// Optional Anzeige
		try
		{
			HighGui.imshow("Detections", annotated);
			HighGui.waitKey(0);
			HighGui.destroyAllWindows();
		} catch (Throwable t)
		{
			System.out.println("Anzeige (HighGui) nicht verfügbar. Nutze annotated.jpg.");
		}
	}

	private static List<Ref> loadReferences(Path refDir, ORB orb) throws Exception
	{
		List<Path> files = new ArrayList<>();
		try (Stream<Path> s = Files.list(refDir))
		{
			s.filter(p ->
			{
				String n = p.getFileName().toString().toLowerCase();
				return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
			}).sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase())).forEach(files::add);
		}

		List<Ref> refs = new ArrayList<>();
		for (Path p : files)
		{
			String fn = p.getFileName().toString();
			String name = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;

			Mat img = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_COLOR);
			if (img.empty())
				continue;

			Mat gray = new Mat();
			Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY);

			MatOfKeyPoint kp = new MatOfKeyPoint();
			Mat desc = new Mat();
			orb.detectAndCompute(gray, new Mat(), kp, desc);

			if (desc.empty())
			{
				System.err.println("Skip (keine Features): " + fn);
				continue;
			}

			refs.add(new Ref(name, kp, desc, gray.size()));
		}

		if (refs.isEmpty())
			throw new IllegalStateException("Keine gültigen Referenzen.");
		return refs;
	}

	private static MatchResult tryFind(Ref ref, MatOfKeyPoint capKp, Mat capDesc, BFMatcher matcher)
	{
		List<MatOfDMatch> knn = new ArrayList<>();
		matcher.knnMatch(ref.descriptors, capDesc, knn, 2);

		List<DMatch> good = new ArrayList<>();
		for (MatOfDMatch m : knn)
		{
			DMatch[] d = m.toArray();
			if (d.length < 2)
				continue;
			if (d[0].distance < LOWE_RATIO * d[1].distance)
				good.add(d[0]);
		}

		if (good.size() < MIN_GOOD_MATCHES)
			return null;

		KeyPoint[] refKps = ref.keypoints.toArray();
		KeyPoint[] capKps = capKp.toArray();

		List<Point> srcPts = new ArrayList<>();
		List<Point> dstPts = new ArrayList<>();
		for (DMatch m : good)
		{
			srcPts.add(refKps[m.queryIdx].pt);
			dstPts.add(capKps[m.trainIdx].pt);
		}

		MatOfPoint2f src = new MatOfPoint2f();
		src.fromList(srcPts);
		MatOfPoint2f dst = new MatOfPoint2f();
		dst.fromList(dstPts);

		Mat inlierMask = new Mat();
		Mat H = Calib3d.findHomography(src, dst, Calib3d.RANSAC, RANSAC_REPROJ_THRESH, inlierMask);
		if (H.empty())
			return null;

		int inliers = Core.countNonZero(inlierMask);
		if (inliers < MIN_INLIERS)
			return null;

		double ratio = (double) inliers / (double) good.size();
		if (ratio < MIN_INLIER_RATIO)
			return null;

		// Ecken transformieren
		MatOfPoint2f refCorners = new MatOfPoint2f(new Point(0, 0), new Point(ref.size.width - 1, 0),
				new Point(ref.size.width - 1, ref.size.height - 1), new Point(0, ref.size.height - 1));
		MatOfPoint2f capCorners = new MatOfPoint2f();
		Core.perspectiveTransform(refCorners, capCorners, H);
		Point[] quad = capCorners.toArray();
		if (quad.length != 4)
			return null;

		// Plausibilitätscheck: Boxfläche
		double area = polygonArea(quad);
		if (area < 5000)
			return null; // zu klein = wahrscheinlich false positive / degeneriert

		return new MatchResult(ref.name, quad, good.size(), inliers, ratio);
	}

	private static double polygonArea(Point[] p)
	{
		// Shoelace
		double a = 0;
		for (int i = 0; i < p.length; i++)
		{
			Point p1 = p[i];
			Point p2 = p[(i + 1) % p.length];
			a += (p1.x * p2.y - p2.x * p1.y);
		}
		return Math.abs(a) * 0.5;
	}

	private static void applyMask(Mat mask, Point[] quad)
	{
		MatOfPoint poly = new MatOfPoint(quad);
		List<MatOfPoint> polys = List.of(poly);
		// Bereich auf 0 setzen => dort werden künftig keine Features berechnet
		Imgproc.fillPoly(mask, polys, new Scalar(0));
	}

	private static void drawDetection(Mat img, MatchResult res)
	{
		MatOfPoint poly = new MatOfPoint(res.quad);
		Imgproc.polylines(img, List.of(poly), true, new Scalar(0, 255, 0), 3);

		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
		for (Point p : res.quad)
		{
			minX = Math.min(minX, p.x);
			minY = Math.min(minY, p.y);
		}
		Point textPos = new Point(Math.max(5, minX), Math.max(25, minY - 8));

		int font = Imgproc.FONT_HERSHEY_SIMPLEX;
		double scale = 0.8;
		int thickness = 2;

		int[] baseLine = new int[1];
		Size textSize = Imgproc.getTextSize(res.name, font, scale, thickness, baseLine);

		Point r1 = new Point(textPos.x - 3, textPos.y - textSize.height - 6);
		Point r2 = new Point(textPos.x + textSize.width + 6, textPos.y + 6);
		Imgproc.rectangle(img, r1, r2, new Scalar(0, 255, 0), Imgproc.FILLED);
		Imgproc.putText(img, res.name, textPos, font, scale, new Scalar(0, 0, 0), thickness);
	}
}
