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
public class OpenCvRecognitionGroupContourServiceV3 {

    private static volatile boolean OPENCV_LOADED = false;

    // ---------- TUNING ----------
    private final int maxDetectionsTotal = 10;

    /** Normalisierung: alle Bilder auf feste Breite skalieren (px). */
    private final int normalizeWidth = 900;

    /** Konturen kleiner als diese Fläche (nach Normalisierung) ignorieren. */
    private final double minContourArea = 1200.0;

    /**
     * Kosten-Schwelle (kleiner = besser). Da wir cost = shape + weights machen,
     * starte z.B. bei 0.80 und taste dich runter.
     */
    private final double maxCost = 0.80;
    //private final double maxCost = 0.50;
    //private final double maxCost = 0.60;//

    /**
     * Margin-Test: best muss deutlich besser sein als second.
     * Wenn zu streng -> kleiner machen (z.B. 0.01). Wenn zu viele falsche Labels -> größer (z.B. 0.05).
     */
    private final double minMargin = 0.03;

    /** Nested Boxes (Innenkonturen) entfernen wenn inner zu 90% in outer liegt. */
    private final double nestedContainmentThreshold = 0.90;

    /** Referenzkontur zusätzlich per convex hull stabilisieren. */
    private final boolean useConvexHull = true;

    /** Debugbilder schreiben */
    private final boolean debugWriteImages = true;

    // Cost weights: shape + (1-areaSim)*w + (1-aspectSim)*w
    private final double wArea = 0.5;
    private final double wAspect = 0.3;

    // ---------- CACHE ----------
    private final Map<Path, RefContour> refCache = new ConcurrentHashMap<>();

    public void invalidateReferenceCache() {
        refCache.clear();
    }

    // ---------- API ----------
    public GroupRecognitionResult recognizeGroup(Path captureFile) throws IOException {
        ensureOpenCvLoaded();

        AppSettings settings = new AppSettings();
        Path workDir = settings.getWorkDir();
        Path refDir = workDir.resolve("ref_bilder");

        if (!Files.isDirectory(refDir)) throw new IOException("Referenzordner fehlt: " + refDir);
        if (!Files.exists(captureFile)) throw new IOException("Capture fehlt: " + captureFile);

        Mat capBgr0 = Imgcodecs.imread(captureFile.toString(), Imgcodecs.IMREAD_COLOR);
        if (capBgr0.empty()) throw new IOException("Capture konnte nicht gelesen werden: " + captureFile);

        Mat capBgr = resizeToWidth(capBgr0, normalizeWidth);
        if (capBgr != capBgr0) capBgr0.release();

        // 1) Silhouette-Maske
        Mat capMask = binaryMask(capBgr);

        if (debugWriteImages) {
            Imgcodecs.imwrite(workDir.resolve("debug_capture_mask.png").toString(), capMask);
        }

        // 2) Kandidaten aus Maske
        List<Candidate> candidates = findCandidates(capMask, minContourArea);
        capMask.release();

        if (candidates.isEmpty()) {
            capBgr.release();
            throw new IOException("Keine passenden Konturen im Capture gefunden (minContourArea zu hoch?).");
        }

        // 3) Nested-Box suppression (Innenboxen entfernen)
        candidates = suppressNestedCandidates(candidates, nestedContainmentThreshold);

        if (debugWriteImages) {
            Mat dbg = capBgr.clone();
            for (Candidate c : candidates) {
                Imgproc.rectangle(dbg,
                        new Point(c.bbox.x, c.bbox.y),
                        new Point(c.bbox.x + c.bbox.width, c.bbox.y + c.bbox.height),
                        new Scalar(255, 0, 0), 2);
            }
            Imgcodecs.imwrite(workDir.resolve("debug_capture_contours.png").toString(), dbg);
            dbg.release();
        }

        // 4) Referenzen laden (Cache)
        List<Path> refs = listReferenceImages(refDir);
        if (refs.isEmpty()) {
            cleanupCandidates(candidates);
            capBgr.release();
            throw new IOException("Keine Referenzbilder in: " + refDir);
        }

        List<RefContour> refContours = new ArrayList<>();
        for (Path r : refs) {
            RefContour rc = getOrComputeRefContour(r, workDir);
            if (rc != null && rc.contour != null && rc.contour.total() > 0) {
                refContours.add(rc);
            }
        }

        if (refContours.isEmpty()) {
            cleanupCandidates(candidates);
            capBgr.release();
            throw new IOException("Referenzkonturen konnten nicht berechnet werden.");
        }

        // 5) Pro Candidate: bestes Label bestimmen (mit cost + margin)
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<DetectedForm> detections = new ArrayList<>();

        for (Candidate cand : candidates) {
            List<Match> ms = new ArrayList<>(refContours.size());
            for (RefContour ref : refContours) {
                double cost = computeCost(ref, cand);
                ms.add(new Match(ref.name, cost, cand));
            }

            ms.sort(Comparator.comparingDouble(m -> m.cost));

            Match best = ms.get(0);
            Match second = ms.size() > 1 ? ms.get(1) : null;

            double margin = (second != null) ? (second.cost - best.cost) : 999.0;

            // Debug (optional)
            // System.out.println("Candidate " + cand.bbox + " best=" + best.name + " cost=" + best.cost + " margin=" + margin);

            // Reject rules
            if (best.cost > maxCost) continue;
            if (second != null && margin < minMargin) continue;

            double score = 1.0 / (1.0 + best.cost);

            detections.add(new DetectedForm(best.name, cand.bbox, 0, score));
            counts.put(best.name, counts.getOrDefault(best.name, 0) + 1);

            if (detections.size() >= maxDetectionsTotal) break;
        }

        // 6) Annotieren + speichern
        Mat annotated = capBgr.clone();
        for (DetectedForm d : detections) {
            drawDetection(annotated, d);
        }

        Path annotatedOut = workDir.resolve("capture_annotated.png");
        Imgcodecs.imwrite(annotatedOut.toString(), annotated);

        saveResults(workDir, detections, counts, annotatedOut);

        // Cleanup
        annotated.release();
        cleanupCandidates(candidates);
        capBgr.release();

        return new GroupRecognitionResult(annotatedOut, counts, detections);
    }

    // ---------- COST ----------
    private double computeCost(RefContour ref, Candidate cand) {
        double shape = Imgproc.matchShapes(ref.contour, cand.contour, Imgproc.CONTOURS_MATCH_I1, 0);

        double areaSim = Math.min(ref.area, cand.area) / Math.max(ref.area, cand.area);

        double aspSim = 1.0 - Math.min(1.0,
                Math.abs(ref.aspect - cand.aspect) / Math.max(0.01, ref.aspect));

        return shape
                + wArea * (1.0 - areaSim)
                + wAspect * (1.0 - aspSim);
    }

    // ---------- MASK PIPELINE ----------
    /**
     * Liefert eine gefüllte Silhouette (binär), robust für matchShapes.
     * OTSU + Morph Close/Open.
     */
    private static Mat binaryMask(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);

        Mat blur = new Mat();
        Imgproc.GaussianBlur(gray, blur, new Size(5, 5), 0);

        Mat bin = new Mat();
        Imgproc.threshold(blur, bin, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        Mat kClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(9, 9));
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kClose);

        Mat kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_OPEN, kOpen);

        kClose.release();
        kOpen.release();
        gray.release();
        blur.release();
        return bin;
    }

    // ---------- CANDIDATES ----------
    private List<Candidate> findCandidates(Mat mask, double minArea) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();

        List<Candidate> out = new ArrayList<>();
        for (MatOfPoint c : contours) {
            double area = Imgproc.contourArea(c);
            if (area < minArea) {
                c.release();
                continue;
            }

            MatOfPoint norm = normalizeContour(c);
            Rect box = Imgproc.boundingRect(norm);
            double aspect = box.height > 0 ? (double) box.width / (double) box.height : 0.0;

            out.add(new Candidate(norm, box, area, aspect));
            c.release();
        }
        return out;
    }

    private MatOfPoint normalizeContour(MatOfPoint contour) {
        MatOfPoint approx = simplify(contour);
        if (useConvexHull) {
            MatOfPoint hull = convexHull(approx);
            approx.release();
            return hull;
        }
        return approx;
    }

    private static void cleanupCandidates(List<Candidate> candidates) {
        for (Candidate c : candidates) {
            try { c.release(); } catch (Exception ignored) {}
        }
    }

    // Nested-Box suppression (entfernt Innenboxen)
    private static List<Candidate> suppressNestedCandidates(List<Candidate> in, double containmentThreshold) {
        List<Candidate> list = new ArrayList<>(in);
        list.sort((a, b) -> Double.compare(b.area, a.area)); // groß -> klein

        List<Candidate> out = new ArrayList<>();
        for (Candidate c : list) {
            boolean contained = false;
            for (Candidate keep : out) {
                double frac = containmentFraction(c.bbox, keep.bbox);
                if (frac >= containmentThreshold) {
                    contained = true;
                    break;
                }
            }
            if (!contained) out.add(c);
        }
        return out;
    }

    private static double containmentFraction(Rect inner, Rect outer) {
        Rect inter = intersect(inner, outer);
        if (inter == null) return 0.0;
        double interArea = (double) inter.width * inter.height;
        double innerArea = (double) inner.width * inner.height;
        if (innerArea <= 0) return 0.0;
        return interArea / innerArea;
    }

    private static Rect intersect(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 0 || h <= 0) return null;
        return new Rect(x1, y1, w, h);
    }

    // ---------- REFERENCE CACHE ----------
    private RefContour getOrComputeRefContour(Path ref, Path workDir) {
        try {
            long lm = Files.getLastModifiedTime(ref).toMillis();
            RefContour cached = refCache.get(ref);
            if (cached != null && cached.lastModifiedMillis == lm) {
                return cached;
            }

            Mat refBgr0 = Imgcodecs.imread(ref.toString(), Imgcodecs.IMREAD_COLOR);
            if (refBgr0.empty()) return null;

            Mat refBgr = resizeToWidth(refBgr0, normalizeWidth);
            if (refBgr != refBgr0) refBgr0.release();

            Mat refMask = binaryMask(refBgr);

            String name = stripExt(ref.getFileName().toString()).trim();
            if (debugWriteImages) {
                Imgcodecs.imwrite(workDir.resolve("debug_ref_" + safe(name) + "_mask.png").toString(), refMask);
            }

            MatOfPoint best = largestContour(refMask, minContourArea * 0.6);
            refMask.release();
            refBgr.release();

            if (best == null) return null;

            MatOfPoint norm = normalizeContour(best);
            best.release();

            double area = Imgproc.contourArea(norm);
            Rect box = Imgproc.boundingRect(norm);
            double aspect = box.height > 0 ? (double) box.width / (double) box.height : 0.0;

            RefContour entry = new RefContour(ref, name, norm, lm, area, aspect);
            RefContour old = refCache.put(ref, entry);
            if (old != null) old.release();

            return entry;

        } catch (Exception e) {
            return null;
        }
    }

    private static MatOfPoint largestContour(Mat mask, double minArea) {
        List<MatOfPoint> cs = new ArrayList<>();
        Mat hier = new Mat();
        Imgproc.findContours(mask, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hier.release();

        MatOfPoint best = null;
        double bestArea = -1;

        for (MatOfPoint c : cs) {
            double a = Imgproc.contourArea(c);
            if (a >= minArea && a > bestArea) {
                if (best != null) best.release();
                best = c;
                bestArea = a;
            } else {
                c.release();
            }
        }
        return best;
    }

    // ---------- CONTOUR OPS ----------
    private static MatOfPoint simplify(MatOfPoint contour) {
        MatOfPoint2f c2f = new MatOfPoint2f(contour.toArray());
        double peri = Imgproc.arcLength(c2f, true);

        MatOfPoint2f approx2f = new MatOfPoint2f();
        Imgproc.approxPolyDP(c2f, approx2f, 0.01 * peri, true);

        MatOfPoint approx = new MatOfPoint(approx2f.toArray());
        c2f.release();
        approx2f.release();
        return approx;
    }

    private static MatOfPoint convexHull(MatOfPoint contour) {
        MatOfInt hullIdx = new MatOfInt();
        Imgproc.convexHull(contour, hullIdx);

        Point[] pts = contour.toArray();
        int[] idx = hullIdx.toArray();
        Point[] hullPts = new Point[idx.length];
        for (int i = 0; i < idx.length; i++) hullPts[i] = pts[idx[i]];

        hullIdx.release();
        return new MatOfPoint(hullPts);
    }

    // ---------- DRAWING ----------
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

    // ---------- RESULTS ----------
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

    // ---------- UTILS ----------
    private static Mat resizeToWidth(Mat src, int targetW) {
        int w = src.cols();
        int h = src.rows();
        if (w <= 0 || h <= 0 || w == targetW) return src;

        double s = (double) targetW / (double) w;
        int nh = Math.max(1, (int) Math.round(h * s));

        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(targetW, nh), 0, 0, Imgproc.INTER_AREA);
        return dst;
    }

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

    private static String safe(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void ensureOpenCvLoaded() {
        if (OPENCV_LOADED) return;
        synchronized (OpenCvRecognitionGroupContourServiceV3.class) {
            if (OPENCV_LOADED) return;
            System.loadLibrary("opencv_java460");
            OPENCV_LOADED = true;
        }
    }

    // ---------- TYPES ----------
    private static final class Candidate {
        final MatOfPoint contour;
        final Rect bbox;
        final double area;
        final double aspect;

        Candidate(MatOfPoint contour, Rect bbox, double area, double aspect) {
            this.contour = contour;
            this.bbox = bbox;
            this.area = area;
            this.aspect = aspect;
        }

        void release() {
            if (contour != null) contour.release();
        }
    }

    private static final class Match {
        final String name;
        final double cost;
        final Candidate candidate;

        Match(String name, double cost, Candidate candidate) {
            this.name = name;
            this.cost = cost;
            this.candidate = candidate;
        }
    }

    private static final class RefContour {
        final Path path;
        final String name;
        final MatOfPoint contour;
        final long lastModifiedMillis;
        final double area;
        final double aspect;

        RefContour(Path path, String name, MatOfPoint contour, long lm, double area, double aspect) {
            this.path = path;
            this.name = name;
            this.contour = contour;
            this.lastModifiedMillis = lm;
            this.area = area;
            this.aspect = aspect;
        }

        void release() {
            if (contour != null) contour.release();
        }
    }
}
