package de.flothari.ui.handlers;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import de.flothari.ui.settings.AppSettings;

public class CompareCaptureWithReferencesHandler {

    @Inject private UISynchronize ui;
    @Inject private EPartService partService;

    private static volatile boolean OPENCV_LOADED = false;
    private static final Set<String> IMG_EXT = Set.of("png", "jpg", "jpeg");

    @Execute
    public void execute() {
        ui.asyncExec(() -> {
            Shell shell = safeShell();

            try {
                ensureOpenCvLoaded();

                Path workDir = new AppSettings().getWorkDir();
                Path refDir  = workDir.resolve("ref_bilder");

                Path capture = findCaptureFile(workDir);
                if (capture == null) {
                    MessageDialog.openError(
                            shell,
                            "Fehler",
                            "Kein capture.* gefunden.\nErwartet: capture.png / capture.jpg / capture.jpeg\n\nArbeitsverzeichnis:\n" + workDir
                    );
                    return;
                }

                if (!Files.isDirectory(refDir)) {
                    MessageDialog.openError(shell, "Fehler", "Referenzordner nicht gefunden:\n" + refDir);
                    return;
                }

                List<Path> refs = Files.list(refDir)
                        .filter(p -> isAllowedImage(p.getFileName().toString()))
                        .sorted()
                        .collect(Collectors.toList());

                if (refs.isEmpty()) {
                    MessageDialog.openInformation(shell, "Vergleich",
                            "Keine Referenzbilder (png/jpg/jpeg) in:\n" + refDir);
                    return;
                }

                MatchResult best = findBestMatch(capture, refs);

                if (best != null && best.isHit) {
                    String name = stripExt(best.refFile.getFileName().toString());
                    MessageDialog.openInformation(
                            shell,
                            "Treffer",
                            "Übereinstimmung gefunden:\n" + name
                                    + "\n\nScore: " + String.format(Locale.ROOT, "%.3f", best.score)
                                    + "\nGood Matches: " + best.goodMatches
                    );
                } else {
                    MessageDialog.openInformation(shell, "Kein Treffer",
                            "Keine ausreichende Übereinstimmung gefunden.");
                }

            } catch (Exception ex) {
                MessageDialog.openError(shell, "Fehler beim Vergleich",
                        ex.getMessage() != null ? ex.getMessage() : ex.toString());
            }
        });
    }

    // ----------------- Matching (ORB) -----------------

    private MatchResult findBestMatch(Path captureFile, List<Path> refs) {
        Mat capture = readGray(captureFile);
        if (capture.empty()) throw new IllegalStateException("Capture konnte nicht gelesen werden: " + captureFile);

        Imgproc.GaussianBlur(capture, capture, new Size(3, 3), 0);

        ORB orb = ORB.create(1200);

        MatOfKeyPoint kpC = new MatOfKeyPoint();
        Mat descC = new Mat();
        orb.detectAndCompute(capture, new Mat(), kpC, descC);

        if (descC.empty()) {
            throw new IllegalStateException("Keine Features in " + captureFile.getFileName() + " gefunden.");
        }

        BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);

        MatchResult best = null;

        for (Path ref : refs) {
            Mat imgR = readGray(ref);
            if (imgR.empty()) continue;

            Imgproc.GaussianBlur(imgR, imgR, new Size(3, 3), 0);

            MatOfKeyPoint kpR = new MatOfKeyPoint();
            Mat descR = new Mat();
            orb.detectAndCompute(imgR, new Mat(), kpR, descR);

            if (descR.empty()) continue;

            List<MatOfDMatch> knn = new ArrayList<>();
            matcher.knnMatch(descC, descR, knn, 2);

            int good = 0;
            for (MatOfDMatch m : knn) {
                DMatch[] d = m.toArray();
                if (d.length >= 2 && d[0].distance < 0.75f * d[1].distance) {
                    good++;
                }
            }

            int cCount = Math.max(1, kpC.toArray().length);
            double score = (double) good / (double) cCount;

            // Heuristik (bei Bedarf tunen)
            boolean hit = good >= 25 && score >= 0.08;

            MatchResult r = new MatchResult(ref, score, good, hit);
            if (best == null || r.score > best.score) best = r;
        }

        return best;
    }

    private static Mat readGray(Path p) {
        Mat img = Imgcodecs.imread(p.toString(), Imgcodecs.IMREAD_GRAYSCALE);
        return img == null ? new Mat() : img;
    }

    // ----------------- File selection -----------------

    private static Path findCaptureFile(Path dir) throws Exception {
        // Priorität: png > jpg > jpeg
        List<String> candidates = List.of("capture.png", "capture.jpg", "capture.jpeg");
        for (String c : candidates) {
            Path p = dir.resolve(c);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static boolean isAllowedImage(String name) {
        String ext = getExt(name);
        return IMG_EXT.contains(ext);
    }

    private static String getExt(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) return "";
        return name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i < 0) ? name : name.substring(0, i);
    }

    private static class MatchResult {
        final Path refFile;
        final double score;
        final int goodMatches;
        final boolean isHit;

        MatchResult(Path refFile, double score, int goodMatches, boolean isHit) {
            this.refFile = refFile;
            this.score = score;
            this.goodMatches = goodMatches;
            this.isHit = isHit;
        }
    }

    // ----------------- UI / OpenCV init -----------------

    private Shell safeShell() {
    	 Shell shell = Display.getDefault().getActiveShell(); 
        if (shell == null) shell = org.eclipse.swt.widgets.Display.getDefault().getActiveShell();
        return shell;
    }

    private static void ensureOpenCvLoaded() {
        if (OPENCV_LOADED) return;
        synchronized (CompareCaptureWithReferencesHandler.class) {
            if (OPENCV_LOADED) return;
            System.loadLibrary("opencv_java460"); // du setzt -Djava.library.path passend
            OPENCV_LOADED = true;
        }
    }
}
