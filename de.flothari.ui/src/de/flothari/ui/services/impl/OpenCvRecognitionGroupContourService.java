package de.flothari.ui.services.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import de.flothari.ui.recognition.DetectedForm;
import de.flothari.ui.recognition.GroupRecognitionResult;
import de.flothari.ui.settings.AppSettings;

@Creatable
@Singleton
public class OpenCvRecognitionGroupContourService {

    private static volatile boolean OPENCV_LOADED = false;

    // --- Tuning ---
    private final int maxDetectionsTotal = 10;

    /** Je kleiner, desto besser. Typische gute Werte: 0.01 - 0.20 (stark abhängig von Konturen). */
    //private final double maxShapeDistance = 0.15;
    //private final double maxShapeDistance = 0.22;
    private final double maxShapeDistance = 0.50;

    /** Minimale Konturfläche im Capture, um Rauschen zu filtern (Pixel). */
    //private final double minContourArea = 1500.0;
    private final double minContourArea = 400.0;

    /** Optional: Wenn true, werden pro Referenz mehrere Treffer erlaubt. */
    private final boolean allowMultiplePerReference = true;

    // Cache: Referenzname -> Referenzkontur
    private final Map<Path, RefContour> refCache = new ConcurrentHashMap<>();

    public void invalidateReferenceCache() {
        refCache.clear();
    }

    public GroupRecognitionResult recognizeGroup(Path captureFile) throws IOException {
        ensureOpenCvLoaded();

        AppSettings settings = new AppSettings();
        Path workDir = settings.getWorkDir();
        Path refDir = workDir.resolve("ref_bilder");

        if (!Files.isDirectory(refDir)) throw new IOException("Referenzordner fehlt: " + refDir);
        if (!Files.exists(captureFile)) throw new IOException("Capture fehlt: " + captureFile);

        Mat capBgr = Imgcodecs.imread(captureFile.toString(), Imgcodecs.IMREAD_COLOR);
        if (capBgr.empty()) throw new IOException("Capture konnte nicht gelesen werden: " + captureFile);

        // Capture binarisieren -> Konturen
        Mat capBin = preprocessToBinary(capBgr);

        List<MatOfPoint> capContours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(capBin, capContours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // Kandidaten im Capture: große Konturen, ggf. geglättet
        List<Candidate> candidates = new ArrayList<>();
        for (MatOfPoint c : capContours) {
            double area = Imgproc.contourArea(c);
            if (area < minContourArea) {
                c.release();
                continue;
            }
            Rect box = Imgproc.boundingRect(c);
            MatOfPoint approx = simplifyContour(c);
            candidates.add(new Candidate(approx, box, area));
            c.release();
        }

        hierarchy.release();
        capBin.release();

        if (candidates.isEmpty()) {
            capBgr.release();
            throw new IOException("Keine passenden Konturen im Capture gefunden (minContourArea zu hoch?).");
        }

        // Referenzen laden/cachen
        List<Path> refs = listReferenceImages(refDir);
        if (refs.isEmpty()) {
            capBgr.release();
            throw new IOException("Keine Referenzbilder in: " + refDir);
        }

        List<RefContour> refContours = new ArrayList<>();
        for (Path r : refs) {
            RefContour rc = getOrComputeRefContour(r);
            if (rc != null && rc.contour != null && rc.contour.total() > 0) {
                refContours.add(rc);
            }
        }

        if (refContours.isEmpty()) {
            capBgr.release();
            throw new IOException("Referenzkonturen konnten nicht berechnet werden.");
        }

        // Matching: Candidate x Referenz -> Score
        List<Match> matches = new ArrayList<>();
        for (Candidate cand : candidates) {
            for (RefContour ref : refContours) {
                // matchShapes: kleiner = besser
                double d = Imgproc.matchShapes(ref.contour, cand.contour, Imgproc.CONTOURS_MATCH_I1, 0);
                matches.add(new Match(ref.name, d, cand));
            }
        }

        // Sortiere beste Matches zuerst
        matches.sort(Comparator.comparingDouble(m -> m.distance));

        // Greedy Auswahl: bis maxDetectionsTotal, ohne Candidate doppelt zu verwenden
        Set<Candidate> usedCandidates = new HashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<DetectedForm> detections = new ArrayList<>();

        // Optional: Limit pro Referenz (wenn allowMultiplePerReference=false)
        Set<String> usedRefs = new HashSet<>();

        for (Match m : matches) {
            if (detections.size() >= maxDetectionsTotal) break;
            if (m.distance > maxShapeDistance) break; // ab hier wird's nur schlechter
            if (usedCandidates.contains(m.candidate)) continue;
            if (!allowMultiplePerReference && usedRefs.contains(m.name)) continue;

            usedCandidates.add(m.candidate);
            usedRefs.add(m.name);

            // score: invertiert (nur zur Info)
            double score = 1.0 / (1.0 + m.distance);

            detections.add(new DetectedForm(m.name, m.candidate.bbox, 0, score));
            counts.put(m.name, counts.getOrDefault(m.name, 0) + 1);
        }

        // Annotieren
        Mat annotated = capBgr.clone();
        for (DetectedForm d : detections) {
            drawDetection(annotated, d);
        }

        // Speichern
        Path annotatedOut = workDir.resolve("capture_annotated.png");
        Imgcodecs.imwrite(annotatedOut.toString(), annotated);

        saveResults(workDir, detections, counts, annotatedOut);

        // Cleanup
        for (Candidate c : candidates) c.release();
        annotated.release();
        capBgr.release();

        return new GroupRecognitionResult(annotatedOut, counts, detections);
    }

    // ----------------- Preprocessing -----------------

    /**
     * Macht aus dem Bild eine binäre Maske der "Formen".
     * Für Silikonformen: adaptive Threshold + Morphology funktioniert meist gut.
     */
    private static Mat preprocessToBinary(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);

        Mat blur = new Mat();
        Imgproc.GaussianBlur(gray, blur, new Size(5, 5), 0);

        Mat bin = new Mat();
        Imgproc.adaptiveThreshold(
                blur, bin,
                255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                5
        );

        // Morphology: Löcher schließen, Rauschen entfernen
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel);
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, kernel);

        kernel.release();
        gray.release();
        blur.release();
        return bin;
    }

    private static MatOfPoint simplifyContour(MatOfPoint contour) {
        MatOfPoint2f c2f = new MatOfPoint2f(contour.toArray());
        double peri = Imgproc.arcLength(c2f, true);

        MatOfPoint2f approx2f = new MatOfPoint2f();
        Imgproc.approxPolyDP(c2f, approx2f, 0.01 * peri, true);

        MatOfPoint approx = new MatOfPoint(approx2f.toArray());

        c2f.release();
        approx2f.release();
        return approx;
    }

    // ----------------- Reference Cache -----------------

    private RefContour getOrComputeRefContour(Path ref) {
        try {
            long lm = Files.getLastModifiedTime(ref).toMillis();
            RefContour cached = refCache.get(ref);
            if (cached != null && cached.lastModifiedMillis == lm) {
                return cached;
            }

            Mat refBgr = Imgcodecs.imread(ref.toString(), Imgcodecs.IMREAD_COLOR);
            if (refBgr.empty()) return null;

            Mat bin = preprocessToBinary(refBgr);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            hierarchy.release();
            bin.release();
            refBgr.release();

            if (contours.isEmpty()) {
                return null;
            }

            // größte Kontur als Referenz
            MatOfPoint best = null;
            double bestArea = -1;
            for (MatOfPoint c : contours) {
                double a = Imgproc.contourArea(c);
                if (a > bestArea) {
                    if (best != null) best.release();
                    best = c;
                    bestArea = a;
                } else {
                    c.release();
                }
            }

            MatOfPoint bestApprox = simplifyContour(best);
            best.release();

            String name = stripExt(ref.getFileName().toString());

            RefContour entry = new RefContour(ref, name, bestApprox, lm);

            RefContour old = refCache.put(ref, entry);
            if (old != null) old.release();

            return entry;

        } catch (Exception e) {
            return null;
        }
    }

    private static final class RefContour {
        final Path path;
        final String name;
        final MatOfPoint contour;
        final long lastModifiedMillis;

        RefContour(Path path, String name, MatOfPoint contour, long lm) {
            this.path = path;
            this.name = name;
            this.contour = contour;
            this.lastModifiedMillis = lm;
        }

        void release() {
            try { contour.release(); } catch (Exception ignored) {}
        }
    }

    // ----------------- Candidate/Match -----------------

    private static final class Candidate {
        final MatOfPoint contour;
        final Rect bbox;
        final double area;

        Candidate(MatOfPoint contour, Rect bbox, double area) {
            this.contour = contour;
            this.bbox = bbox;
            this.area = area;
        }

        void release() {
            try { contour.release(); } catch (Exception ignored) {}
        }
    }

    private static final class Match {
        final String name;
        final double distance;
        final Candidate candidate;

        Match(String name, double distance, Candidate candidate) {
            this.name = name;
            this.distance = distance;
            this.candidate = candidate;
        }
    }

    // ----------------- Drawing / Saving -----------------

    private static void drawDetection(Mat img, DetectedForm d) {
        Rect r = d.bbox();

        Imgproc.rectangle(img,
                new Point(r.x, r.y),
                new Point(r.x + r.width, r.y + r.height),
                new Scalar(0, 255, 0),
                2);

        String label = d.name();
        int[] baseline = new int[1];
        Size ts = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, 2, baseline);

        int tx = r.x;
        int ty = Math.max(0, r.y - 5);

        Imgproc.rectangle(img,
                new Point(tx, Math.max(0, ty - ts.height - 6)),
                new Point(tx + ts.width + 6, ty + 4),
                new Scalar(0, 255, 0),
                -1);

        Imgproc.putText(img,
                label,
                new Point(tx + 3, ty),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.6,
                new Scalar(0, 0, 0),
                2);
    }

    private static void saveResults(Path workDir,
                                    List<DetectedForm> detections,
                                    Map<String, Integer> counts,
                                    Path annotatedImage) throws IOException {

        Path csv = workDir.resolve("recognition_results.csv");
        StringBuilder sb = new StringBuilder();
        sb.append("name,count\n");
        for (var e : counts.entrySet()) {
            sb.append(e.getKey()).append(",").append(e.getValue()).append("\n");
        }
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);

        Path json = workDir.resolve("recognition_results.json");
        StringBuilder js = new StringBuilder();
        js.append("{\n");
        js.append("  \"annotatedImage\": \"").append(annotatedImage.getFileName()).append("\",\n");

        js.append("  \"counts\": {\n");
        int c = 0;
        for (var e : counts.entrySet()) {
            js.append("    \"").append(e.getKey()).append("\": ").append(e.getValue());
            c++;
            js.append(c < counts.size() ? ",\n" : "\n");
        }
        js.append("  },\n");

        js.append("  \"detections\": [\n");
        for (int i = 0; i < detections.size(); i++) {
            DetectedForm d = detections.get(i);
            Rect r = d.bbox();
            js.append("    {\"name\":\"").append(d.name()).append("\",")
              .append("\"x\":").append(r.x).append(",\"y\":").append(r.y)
              .append(",\"w\":").append(r.width).append(",\"h\":").append(r.height)
              .append(",\"score\":").append(String.format(Locale.ROOT, "%.6f", d.score()))
              .append("}");
            js.append(i < detections.size() - 1 ? ",\n" : "\n");
        }
        js.append("  ]\n");
        js.append("}\n");

        Files.writeString(json, js.toString(), StandardCharsets.UTF_8);
    }

    // ----------------- Utils -----------------

    private static List<Path> listReferenceImages(Path refDir) throws IOException {
        try (var s = Files.list(refDir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return (i < 0) ? name : name.substring(0, i);
    }

    private static void ensureOpenCvLoaded() {
        if (OPENCV_LOADED) return;
        synchronized (OpenCvRecognitionGroupContourService.class) {
            if (OPENCV_LOADED) return;
            System.loadLibrary("opencv_java460");
            OPENCV_LOADED = true;
        }
    }
}
