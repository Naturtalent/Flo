package de.flothari.ui.services.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
public class OpenCvRecognitionGroupService
{

	private static volatile boolean OPENCV_LOADED = false;

	// --- Tuning (bei dir später feinjustieren) ---
	/*
	private final int maxDetectionsTotal = 10;
	private final int minGoodMatches = 20;
	private final int minInliers = 15;
	private final double ratioTest = 0.75; // Lowe ratio
	private final double ransacReproj = 3.0; // RANSAC threshold
	*/
	
	
	
	private final int maxDetectionsTotal = 10;
	private final int minGoodMatches = 8;
	private final int minInliers = 8;
	private final double ratioTest = 0.85; // Lowe ratio
	private final double ransacReproj = 5.0; // RANSAC threshold
	


	// ORB wird lazy initialisiert (threadsicher)
	private volatile ORB orb;

	// Ref-Cache: pro Datei ein Entry
	private final Map<Path, RefEntry> refCache = new ConcurrentHashMap<>();

	// Matcher ist leichtgewichtig; kann pro Call erstellt werden
	private DescriptorMatcher matcher()
	{
		return BFMatcher.create(Core.NORM_HAMMING, false);
	}

	/** Kann z.B. nach "Referenz hinzufügen" aufgerufen werden. */
	public void invalidateReferenceCache()
	{
		refCache.clear();
	}

	// ---------------- public API ----------------

	public GroupRecognitionResult recognizeGroup(Path captureFile) throws IOException
	{
		ensureOpenCvLoaded();
		ORB orb = orb(); // garantiert geladen + initialisiert

		AppSettings settings = new AppSettings();
		Path workDir = settings.getWorkDir();
		Path refDir = workDir.resolve("ref_bilder");

		if (!Files.isDirectory(refDir))
			throw new IOException("Referenzordner fehlt: " + refDir);
		if (!Files.exists(captureFile))
			throw new IOException("Capture fehlt: " + captureFile);

		Mat captureBgr = Imgcodecs.imread(captureFile.toString(), Imgcodecs.IMREAD_COLOR);
		if (captureBgr.empty())
			throw new IOException("Capture konnte nicht gelesen werden: " + captureFile);
		
		

		Mat captureGray = new Mat();
		Imgproc.cvtColor(captureBgr, captureGray, Imgproc.COLOR_BGR2GRAY);

		// Maske: 255 erlaubt, 0 gesperrt
		Mat mask = new Mat(captureGray.size(), CvType.CV_8U, new Scalar(255));
		
		MatOfKeyPoint capKp = new MatOfKeyPoint();
		Mat capDesc = new Mat();
		orb.detectAndCompute(captureGray, new Mat(), capKp, capDesc);
		System.out.println("CAP keypoints=" + capKp.toArray().length +
		                   " descEmpty=" + capDesc.empty() +
		                   " descRows=" + capDesc.rows());

		
		
		
		// Ergebnis
		List<DetectedForm> detections = new ArrayList<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		Mat annotated = captureBgr.clone();

		List<Path> refs = listReferenceImages(refDir);
		if (refs.isEmpty())
			throw new IOException("Keine Referenzbilder in: " + refDir);

		int remaining = maxDetectionsTotal;

		for (Path ref : refs)
		{
			if (remaining <= 0)
				break;

			RefEntry entry = getOrComputeRefEntry(ref, orb);
			if (entry == null || entry.desc.empty())
				continue;
			
			System.out.println("REF " + entry.name +
					   " kp=" + entry.kp.toArray().length +
					   " descEmpty=" + entry.desc.empty() +
					   " descRows=" + entry.desc.rows());

			int foundForThisRef = 0;

			while (remaining > 0)
			{
				DetectedForm det = tryFindOneInstance(entry.name, entry.size, entry.kp, entry.desc, captureGray, mask,
						orb);

				if (det == null)
					break;

				drawDetection(annotated, det);

				detections.add(det);
				counts.put(entry.name, counts.getOrDefault(entry.name, 0) + 1);

				foundForThisRef++;
				remaining--;

				// Bereich maskieren, um Doppelzählung zu reduzieren
				maskOut(mask, det.bbox());
			}

			// Optional: wenn pro Referenz nur 1 Treffer gewünscht:
			// if (foundForThisRef > 0) { /* break; */ }
		}

		Path annotatedOut = workDir.resolve("capture_annotated.png");
		Imgcodecs.imwrite(annotatedOut.toString(), annotated);

		saveResults(workDir, detections, counts, annotatedOut);

		// cleanup mats
		captureBgr.release();
		captureGray.release();
		mask.release();
		annotated.release();

		return new GroupRecognitionResult(annotatedOut, counts, detections);
	}

	// ---------------- Recognition Core ----------------

	private DetectedForm tryFindOneInstance(String name, Size refSize, MatOfKeyPoint refKp, Mat refDesc,
			Mat captureGray, Mat mask, ORB orb)
	{
		// Capture-Features mit Maske (damit "ausmaskierte" Bereiche weniger stören)
		MatOfKeyPoint capKp = new MatOfKeyPoint();
		Mat capDesc = new Mat();
		orb.detectAndCompute(captureGray, mask, capKp, capDesc);
		if (capDesc.empty())
		{
			capKp.release();
			capDesc.release();
			return null;
		}

		// KNN match
		List<MatOfDMatch> knn = new ArrayList<>();
		matcher().knnMatch(refDesc, capDesc, knn, 2);

		List<DMatch> good = new ArrayList<>();
		for (MatOfDMatch m : knn)
		{
			DMatch[] d = m.toArray();
			if (d.length < 2)
				continue;
			if (d[0].distance < ratioTest * d[1].distance)
				good.add(d[0]);
		}

		if (good.size() < minGoodMatches)
		{
			capKp.release();
			capDesc.release();
			return null;
		}

		List<KeyPoint> refPoints = refKp.toList();
		List<KeyPoint> capPoints = capKp.toList();

		List<Point> obj = new ArrayList<>(good.size());
		List<Point> scene = new ArrayList<>(good.size());
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
		Mat H = Calib3d.findHomography(objMat, sceneMat, Calib3d.RANSAC, ransacReproj, inlierMask);

		if (H.empty())
		{
			cleanup(capKp, capDesc, objMat, sceneMat, inlierMask, H);
			return null;
		}

		int inliers = Core.countNonZero(inlierMask);
		if (inliers < minInliers)
		{
			cleanup(capKp, capDesc, objMat, sceneMat, inlierMask, H);
			return null;
		}

		// Ecken der Referenz -> transformieren
		MatOfPoint2f refCorners = new MatOfPoint2f(new Point(0, 0), new Point(refSize.width - 1, 0),
				new Point(refSize.width - 1, refSize.height - 1), new Point(0, refSize.height - 1));
		MatOfPoint2f sceneCorners = new MatOfPoint2f();
		Core.perspectiveTransform(refCorners, sceneCorners, H);

		Rect bbox = boundingRectClamped(sceneCorners.toArray(), captureGray.size());
		if (bbox.width <= 2 || bbox.height <= 2)
		{
			refCorners.release();
			sceneCorners.release();
			cleanup(capKp, capDesc, objMat, sceneMat, inlierMask, H);
			return null;
		}

		double score = (double) inliers / (double) good.size();

		refCorners.release();
		sceneCorners.release();
		cleanup(capKp, capDesc, objMat, sceneMat, inlierMask, H);

		return new DetectedForm(name, bbox, inliers, score);
	}

	private static void cleanup(MatOfKeyPoint capKp, Mat capDesc, Mat objMat, Mat sceneMat, Mat inlierMask, Mat H)
	{
		capKp.release();
		capDesc.release();
		objMat.release();
		sceneMat.release();
		inlierMask.release();
		H.release();
	}

	// ---------------- Reference Cache ----------------

	private RefEntry getOrComputeRefEntry(Path ref, ORB orb)
	{
		try
		{
			long lm = Files.getLastModifiedTime(ref).toMillis();
			RefEntry cached = refCache.get(ref);

			if (cached != null && cached.lastModifiedMillis == lm)
			{
				return cached;
			}

			// Neu berechnen
			Mat refBgr = Imgcodecs.imread(ref.toString(), Imgcodecs.IMREAD_COLOR);
			if (refBgr.empty())
				return null;

			Mat refGray = new Mat();
			Imgproc.cvtColor(refBgr, refGray, Imgproc.COLOR_BGR2GRAY);

			MatOfKeyPoint kp = new MatOfKeyPoint();
			Mat desc = new Mat();
			orb.detectAndCompute(refGray, new Mat(), kp, desc);

			Size size = refGray.size();
			String name = stripExt(ref.getFileName().toString());

			// cleanup mats
			refBgr.release();
			refGray.release();

			RefEntry entry = new RefEntry(ref, name, size, kp, desc, lm);

			// Falls schon ein älterer Entry da war: Ressourcen freigeben
			RefEntry old = refCache.put(ref, entry);
			if (old != null)
				old.release();

			return entry;

		} catch (Exception e)
		{
			return null;
		}
	}

	private static final class RefEntry
	{
		final Path path;
		final String name;
		final Size size;
		final MatOfKeyPoint kp;
		final Mat desc;
		final long lastModifiedMillis;

		RefEntry(Path path, String name, Size size, MatOfKeyPoint kp, Mat desc, long lastModifiedMillis)
		{
			this.path = path;
			this.name = name;
			this.size = size;
			this.kp = kp;
			this.desc = desc;
			this.lastModifiedMillis = lastModifiedMillis;
		}

		void release()
		{
			try
			{
				kp.release();
			} catch (Exception ignored)
			{
			}
			try
			{
				desc.release();
			} catch (Exception ignored)
			{
			}
		}
	}

	// ---------------- Drawing / Masking ----------------

	private static void maskOut(Mat mask, Rect r)
	{
		Imgproc.rectangle(mask, new Point(r.x, r.y), new Point(r.x + r.width, r.y + r.height), new Scalar(0), -1);
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

	private static void drawDetection(Mat img, DetectedForm d)
	{
		Rect r = d.bbox();

		Imgproc.rectangle(img, new Point(r.x, r.y), new Point(r.x + r.width, r.y + r.height), new Scalar(0, 255, 0), 2);

		String label = d.name();
		int[] baseline = new int[1];
		Size ts = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, 2, baseline);

		int tx = r.x;
		int ty = Math.max(0, r.y - 5);

		Imgproc.rectangle(img, new Point(tx, Math.max(0, ty - ts.height - 6)), new Point(tx + ts.width + 6, ty + 4),
				new Scalar(0, 255, 0), -1);

		Imgproc.putText(img, label, new Point(tx + 3, ty), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(0, 0, 0), 2);
	}

	// ---------------- Saving ----------------

	private static void saveResults(Path workDir, List<DetectedForm> detections, Map<String, Integer> counts,
			Path annotatedImage) throws IOException
	{

		Path csv = workDir.resolve("recognition_results.csv");
		StringBuilder sb = new StringBuilder();
		sb.append("name,count\n");
		for (var e : counts.entrySet())
		{
			sb.append(e.getKey()).append(",").append(e.getValue()).append("\n");
		}
		Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);

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

	// ---------------- Utils ----------------

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

	// ---------------- Lazy ORB + Load ----------------

	private ORB orb()
	{
		ORB o = orb;
		if (o == null)
		{
			synchronized (this)
			{
				o = orb;
				if (o == null)
				{
					ensureOpenCvLoaded();
					o = ORB.create(3000); // funktioniert bei dir nach ensureOpenCvLoaded()
					orb = o;
				}
			}
		}
		return o;
	}

	private static void ensureOpenCvLoaded()
	{
		if (OPENCV_LOADED)
			return;
		synchronized (OpenCvRecognitionGroupService.class)
		{
			if (OPENCV_LOADED)
				return;
			System.loadLibrary("opencv_java460");
			OPENCV_LOADED = true;
		}
	}
	
	private static Mat preprocessForFeatures(Mat bgrOrGray) {
	    Mat gray = new Mat();
	    if (bgrOrGray.channels() == 3) {
	        Imgproc.cvtColor(bgrOrGray, gray, Imgproc.COLOR_BGR2GRAY);
	    } else {
	        gray = bgrOrGray.clone();
	    }

	    Mat eq = new Mat();
	    Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(gray, eq);

	    Mat edges = new Mat();
	    Imgproc.Canny(eq, edges, 60, 140);

	    gray.release();
	    eq.release();
	    return edges; // caller must release
	}

}
