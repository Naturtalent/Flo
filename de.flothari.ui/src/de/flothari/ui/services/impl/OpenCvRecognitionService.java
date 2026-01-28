package de.flothari.ui.services.impl;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import de.flothari.ui.domain.MatchResult;
import de.flothari.ui.services.RecognitionService;
import de.flothari.ui.settings.AppSettings;

@Creatable
@Singleton
public class OpenCvRecognitionService implements RecognitionService
{

	private static final Set<String> IMG_EXT = Set.of("png", "jpg", "jpeg");
	private static volatile boolean OPENCV_LOADED = false;

	@Override
	public MatchResult match(Path captureFile) throws Exception
	{
		ensureOpenCvLoaded();

		if (captureFile == null || !Files.exists(captureFile))
		{
			throw new IllegalArgumentException("Capture-Datei existiert nicht: " + captureFile);
		}

		Path workDir = new AppSettings().getWorkDir();
		Path refDir = workDir.resolve("ref_bilder");

		if (!Files.isDirectory(refDir))
		{
			throw new IllegalStateException("Referenzordner fehlt: " + refDir);
		}

		List<Path> refs = Files.list(refDir).filter(p -> isAllowedImage(p.getFileName().toString())).sorted()
				.collect(Collectors.toList());

		if (refs.isEmpty())
		{
			throw new IllegalStateException("Keine Referenzbilder in: " + refDir);
		}

		return findBestMatch(captureFile, refs);
	}

	// --------------------------------------------------
	// Matching-Logik (ORB)
	// --------------------------------------------------

	private MatchResult findBestMatch(Path captureFile, List<Path> refs)
	{

		Mat capture = readGray(captureFile);
		if (capture.empty())
		{
			throw new IllegalStateException("Capture konnte nicht gelesen werden: " + captureFile);
		}

		Imgproc.GaussianBlur(capture, capture, new Size(3, 3), 0);

		ORB orb = ORB.create(1200);

		MatOfKeyPoint kpC = new MatOfKeyPoint();
		Mat descC = new Mat();
		orb.detectAndCompute(capture, new Mat(), kpC, descC);

		if (descC.empty())
		{
			throw new IllegalStateException("Keine Features im Capture gefunden.");
		}

		BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);

		MatchResult best = null;

		for (Path ref : refs)
		{
			Mat imgR = readGray(ref);
			if (imgR.empty())
				continue;

			Imgproc.GaussianBlur(imgR, imgR, new Size(3, 3), 0);

			MatOfKeyPoint kpR = new MatOfKeyPoint();
			Mat descR = new Mat();
			orb.detectAndCompute(imgR, new Mat(), kpR, descR);

			if (descR.empty())
				continue;

			List<MatOfDMatch> knn = new ArrayList<>();
			matcher.knnMatch(descC, descR, knn, 2);

			int good = 0;
			for (MatOfDMatch m : knn)
			{
				DMatch[] d = m.toArray();
				if (d.length >= 2 && d[0].distance < 0.75f * d[1].distance)
				{
					good++;
				}
			}

			int cCount = Math.max(1, kpC.toArray().length);
			double score = (double) good / (double) cCount;

			// Heuristik (bewusst zentral hier!)
			boolean hit = good >= 25 && score >= 0.08;

			MatchResult r = new MatchResult(hit, stripExt(ref.getFileName().toString()), score, good, ref);

			if (best == null || r.getScore() > best.getScore())
			{
				best = r;
			}
		}

		return best;
	}

	// --------------------------------------------------
	// Helpers
	// --------------------------------------------------

	private static Mat readGray(Path p)
	{
		Mat img = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_GRAYSCALE);
		return img == null ? new Mat() : img;
	}

	private static boolean isAllowedImage(String name)
	{
		String ext = getExt(name);
		return IMG_EXT.contains(ext);
	}

	private static String getExt(String name)
	{
		int i = name.lastIndexOf('.');
		return (i < 0) ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
	}

	private static String stripExt(String name)
	{
		int i = name.lastIndexOf('.');
		return (i < 0) ? name : name.substring(0, i);
	}

	private static void ensureOpenCvLoaded()
	{
		if (OPENCV_LOADED)
			return;
		synchronized (OpenCvRecognitionService.class)
		{
			if (OPENCV_LOADED)
				return;
			System.loadLibrary("opencv_java460");
			OPENCV_LOADED = true;
		}
	}
}
