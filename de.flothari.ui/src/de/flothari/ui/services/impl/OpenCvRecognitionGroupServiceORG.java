package de.flothari.ui.services.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import de.flothari.ui.recognition.DetectedForm;
import de.flothari.ui.recognition.GroupRecognitionResult;
import de.flothari.ui.settings.AppSettings;

@Creatable
@Singleton
public class OpenCvRecognitionGroupServiceORG
{

	private static volatile boolean OPENCV_LOADED = false;

	// Tuning-Parameter (musst du ggf. an deine Motive anpassen)
	private final int maxDetectionsTotal = 10;
	private final int minGoodMatches = 20;
	private final int minInliers = 15;
	
	
	// ORB – schnell, robust genug für gedrehte Motive
	//private final ORB orb = ORB.create(1500); Fehler: Java-Bindings neuer als Native Lib
	
	//private final ORB orb = ORB.create(); // fix
	
	private volatile ORB orb;

	private ORB orb() {
	    ORB o = orb;
	    if (o == null) {
	        synchronized (this) {
	            o = orb;
	            if (o == null) {
	                ensureOpenCvLoaded();
	                o = ORB.create(1500);
	                orb = o;
	            }
	        }
	    }
	    return o;
	}


	public GroupRecognitionResult recognizeGroup(Path captureFile) throws IOException
	{
		//ensureOpenCvLoaded();
		orb = ORB.create(); 
				
		AppSettings settings = new AppSettings();
		Path workDir = settings.getWorkDir();
		Path refDir = workDir.resolve("ref_bilder");

		if (!Files.isDirectory(refDir))
		{
			throw new IOException("Referenzordner fehlt: " + refDir);
		}
		if (!Files.exists(captureFile))
		{
			throw new IOException("Capture fehlt: " + captureFile);
		}

		// Capture laden
		Mat captureBgr = Imgcodecs.imread(captureFile.toString(), Imgcodecs.IMREAD_COLOR);
		if (captureBgr.empty())
			throw new IOException("Capture konnte nicht gelesen werden: " + captureFile);

		Mat captureGray = new Mat();
		Imgproc.cvtColor(captureBgr, captureGray, Imgproc.COLOR_BGR2GRAY);

		// Maske: Anfangs alles erlaubt
		Mat mask = Mat.ones(captureGray.size(), CvType.CV_8U);
		mask.setTo(new Scalar(255));

		// Capture Features einmalig
		MatOfKeyPoint capKp = new MatOfKeyPoint();
		Mat capDesc = new Mat();
		orb.detectAndCompute(captureGray, mask, capKp, capDesc);

		if (capDesc.empty())
		{
			throw new IOException("Keine Features im Capture gefunden (zu glatt/unscharf?).");
		}

		List<Path> refs = listReferenceImages(refDir);
		if (refs.isEmpty())
			throw new IOException("Keine Referenzbilder in: " + refDir);

		// Ergebnis
		List<DetectedForm> detections = new ArrayList<>();
		Map<String, Integer> counts = new LinkedHashMap<>();

		// Für Zeichnen
		Mat annotated = captureBgr.clone();

		int remaining = maxDetectionsTotal;

		// Wir iterieren über Referenzen und versuchen pro Referenz ggf. mehrere
		// Instanzen zu finden
		for (Path ref : refs)
		{
			if (remaining <= 0)
				break;

			String refName = stripExt(ref.getFileName().toString());

			Mat refBgr = Imgcodecs.imread(ref.toString(), Imgcodecs.IMREAD_COLOR);
			if (refBgr.empty())
				continue;

			Mat refGray = new Mat();
			Imgproc.cvtColor(refBgr, refGray, Imgproc.COLOR_BGR2GRAY);

			MatOfKeyPoint refKp = new MatOfKeyPoint();
			Mat refDesc = new Mat();
			orb.detectAndCompute(refGray, new Mat(), refKp, refDesc);

			if (refDesc.empty())
				continue;

			// Pro Referenz versuchen wir wiederholt Instanzen zu finden
			int foundForThisRef = 0;

			while (remaining > 0)
			{
				DetectedForm det = tryFindOneInstance(refName, refGray.size(), refKp, refDesc, capKp, capDesc,
						captureGray, mask);

				if (det == null)
					break;

				// zeichnen
				drawDetection(annotated, det);

				detections.add(det);
				counts.put(refName, counts.getOrDefault(refName, 0) + 1);

				foundForThisRef++;
				remaining--;

				// Bereich maskieren, damit wir dieselbe Instanz nicht nochmal finden
				maskOut(mask, det.bbox());
			}

			// optional: wenn du pro Referenz nur 1 Treffer willst, hier breaken
			// if(foundForThisRef>0) { ... }
		}

		// Annotiertes Bild speichern
		Path annotatedOut = workDir.resolve("capture_annotated.png");
		Imgcodecs.imwrite(annotatedOut.toString(), annotated);

		// Ergebnisliste speichern
		saveResults(workDir, detections, counts, annotatedOut);

		// cleanup
		captureBgr.release();
		captureGray.release();
		capKp.release();
		capDesc.release();
		mask.release();
		annotated.release();

		return new GroupRecognitionResult(annotatedOut, counts, detections);
	}

	private DetectedForm tryFindOneInstance(String name, Size refSize, MatOfKeyPoint refKp, Mat refDesc,
			MatOfKeyPoint capKp, Mat capDesc, Mat captureGray, Mat mask)
	{

		// Für bessere Ergebnisse: Features im Capture mit aktueller Maske neu berechnen
		// (damit masked Bereiche wirklich raus sind)
		MatOfKeyPoint capKpMasked = new MatOfKeyPoint();
		Mat capDescMasked = new Mat();
		orb.detectAndCompute(captureGray, mask, capKpMasked, capDescMasked);
		if (capDescMasked.empty())
			return null;

		DescriptorMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);

		List<MatOfDMatch> knn = new ArrayList<>();
		matcher.knnMatch(refDesc, capDescMasked, knn, 2);

		// Lowe ratio test
		List<DMatch> good = new ArrayList<>();
		for (MatOfDMatch m : knn)
		{
			DMatch[] d = m.toArray();
			if (d.length < 2)
				continue;
			if (d[0].distance < 0.75 * d[1].distance)
			{
				good.add(d[0]);
			}
		}
		if (good.size() < minGoodMatches)
			return null;

		// Punkte für Homography
		List<KeyPoint> refPoints = refKp.toList();
		List<KeyPoint> capPoints = capKpMasked.toList();

		List<Point> obj = new ArrayList<>();
		List<Point> scene = new ArrayList<>();
		for (DMatch m : good)
		{
			obj.add(refPoints.get(m.queryIdx).pt);
			scene.add(capPoints.get(m.trainIdx).pt);
		}

		MatOfPoint2f objMat = new MatOfPoint2f();
		objMat.fromList(obj);

		MatOfPoint2f sceneMat = new MatOfPoint2f();
		sceneMat.fromList(scene);

		Mat inlierMask = new Mat();
		Mat H = Calib3d.findHomography(objMat, sceneMat, Calib3d.RANSAC, 3.0, inlierMask);

		if (H.empty())
			return null;

		int inliers = Core.countNonZero(inlierMask);
		if (inliers < minInliers)
			return null;

		// Referenz-Ecken transformieren
		MatOfPoint2f refCorners = new MatOfPoint2f(new Point(0, 0), new Point(refSize.width - 1, 0),
				new Point(refSize.width - 1, refSize.height - 1), new Point(0, refSize.height - 1));
		MatOfPoint2f sceneCorners = new MatOfPoint2f();
		Core.perspectiveTransform(refCorners, sceneCorners, H);

		Rect bbox = boundingRectClamped(sceneCorners.toArray(), captureGray.size());
		if (bbox.width <= 2 || bbox.height <= 2)
			return null;

		double score = (double) inliers / (double) good.size();

		// cleanup
		objMat.release();
		sceneMat.release();
		inlierMask.release();
		H.release();
		refCorners.release();
		sceneCorners.release();
		capKpMasked.release();
		capDescMasked.release();

		return new DetectedForm(name, bbox, inliers, score);
	}

	private static Rect boundingRectClamped(Point[] pts, Size imgSize)
	{
		MatOfPoint mop = new MatOfPoint(pts);
		Rect r = Imgproc.boundingRect(mop);
		mop.release();

		int x = Math.max(0, r.x);
		int y = Math.max(0, r.y);
		int w = Math.min((int) imgSize.width - x, r.width);
		int h = Math.min((int) imgSize.height - y, r.height);

		return new Rect(x, y, Math.max(0, w), Math.max(0, h));
	}

	private static void maskOut(Mat mask, Rect r)
	{
		// Weiß=zulässig, Schwarz=gesperrt
		Imgproc.rectangle(mask, new Point(r.x, r.y), new Point(r.x + r.width, r.y + r.height), new Scalar(0), // schwarz
				-1); // filled
	}

	private static void drawDetection(Mat img, DetectedForm d)
	{
		Rect r = d.bbox();
		Imgproc.rectangle(img, new Point(r.x, r.y), new Point(r.x + r.width, r.y + r.height), new Scalar(0, 255, 0), // grün
																														// (BGR)
				2);

		String label = d.name();
		int baseline[] = new int[1];
		Size ts = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, 2, baseline);

		int tx = r.x;
		int ty = Math.max(0, r.y - 5);

		// Hintergrundbox (damit Text lesbar ist)
		Imgproc.rectangle(img, new Point(tx, Math.max(0, ty - ts.height - 6)), new Point(tx + ts.width + 6, ty + 4),
				new Scalar(0, 255, 0), -1);

		Imgproc.putText(img, label, new Point(tx + 3, ty), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(0, 0, 0), 2);
	}

	private static List<Path> listReferenceImages(Path refDir) throws IOException
	{
		try (var s = Files.list(refDir))
		{
			return s.filter(Files::isRegularFile).filter(p ->
			{
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
			}).sorted().collect(Collectors.toList());
		}
	}

	private static String stripExt(String name)
	{
		int i = name.lastIndexOf('.');
		return (i < 0) ? name : name.substring(0, i);
	}

	private static void saveResults(Path workDir, List<DetectedForm> detections, Map<String, Integer> counts,
			Path annotatedImage) throws IOException
	{

		// CSV (einfach zu lesen)
		Path csv = workDir.resolve("recognition_results.csv");
		StringBuilder sb = new StringBuilder();
		sb.append("name,count\n");
		for (var e : counts.entrySet())
		{
			sb.append(e.getKey()).append(",").append(e.getValue()).append("\n");
		}
		Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);

		// JSON (Details)
		Path json = workDir.resolve("recognition_results.json");
		StringBuilder js = new StringBuilder();
		js.append("{\n");
		js.append("  \"annotatedImage\": \"").append(annotatedImage.getFileName()).append("\",\n");
		js.append("  \"counts\": {\n");
		int c = 0;
		for (var e : counts.entrySet())
		{
			js.append("    \"").append(e.getKey()).append("\": ").append(e.getValue());
			c++;
			js.append(c < counts.size() ? ",\n" : "\n");
		}
		js.append("  },\n");
		js.append("  \"detections\": [\n");
		for (int i = 0; i < detections.size(); i++)
		{
			DetectedForm d = detections.get(i);
			Rect r = d.bbox();
			js.append("    {\"name\":\"").append(d.name()).append("\",").append("\"x\":").append(r.x).append(",\"y\":")
					.append(r.y).append(",\"w\":").append(r.width).append(",\"h\":").append(r.height)
					.append(",\"inliers\":").append(d.inliers()).append(",\"score\":")
					.append(String.format(Locale.ROOT, "%.4f", d.score())).append("}");
			js.append(i < detections.size() - 1 ? ",\n" : "\n");
		}
		js.append("  ]\n");
		js.append("}\n");

		Files.writeString(json, js.toString(), StandardCharsets.UTF_8);
	}

	private static void ensureOpenCvLoaded()
	{
		if (OPENCV_LOADED)
			return;
		
		synchronized (OpenCvRecognitionGroupServiceORG.class)
		{
			if (OPENCV_LOADED)
				return;
			System.loadLibrary("opencv_java460");
			OPENCV_LOADED = true;
		}
	}
}
