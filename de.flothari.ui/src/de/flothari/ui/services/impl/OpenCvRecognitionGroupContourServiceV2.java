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
public class OpenCvRecognitionGroupContourServiceV2 {

    private static volatile boolean OPENCV_LOADED = false;

    // ---------- Tuning ----------
    private final int maxDetectionsTotal = 10;

    /** Feste Breite (px) für Normalisierung. */
    private final int normalizeWidth = 900;

    /** Konturen unterhalb dieser Fläche ignorieren (nach Normalisierung). */
    private final double minContourArea = 1200.0;
    //private final double minContourArea = 2500.0;

    /**
     * matchShapes distance: kleiner = besser. Typische Startwerte:
     * 0.05 .. 0.30 (sehr abhängig von Konturqualität).
     */
    private final double maxShapeDistance = 0.60;

    /** true = Konturen werden hull-basiert stabilisiert (oft gut für weiche Formen). */
    private final boolean useConvexHull = true;

    /** true = Debug-Bilder in WorkDir schreiben */
    private final boolean debugWriteImages = true;

    /** Mehrere Treffer pro Referenz zulassen */
    private final boolean allowMultiplePerReference = true;

    // ---------- Cache ----------
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

        // Normalisieren
        Mat capBgr = resizeToWidth(capBgr0, normalizeWidth);
        if (capBgr != capBgr0) capBgr0.release();

        // Edges
        Mat capEdges = edges(capBgr);

        if (debugWriteImages) {
            Imgcodecs.imwrite(workDir.resolve("debug_capture_edges.png").toString(), capEdges);
        }

        // Capture-Kandidatenkonturen
        List<Candidate> candidates = findCandidates(capEdges, minContourArea);

        // eingefügt
        candidates = suppressNestedCandidates(candidates, 0.90);

        
        if (candidates.isEmpty()) {
            capEdges.release();
            capBgr.release();
            throw new IOException("Keine passenden Konturen im Capture gefunden (minContourArea zu hoch?).");
        }

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

        // Referenzen
        List<Path> refs = listReferenceImages(refDir);
        if (refs.isEmpty()) {
            cleanupCandidates(candidates);
            capEdges.release();
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
            capEdges.release();
            capBgr.release();
            throw new IOException("Referenzkonturen konnten nicht berechnet werden.");
        }

        // Matching: Candidate x Referenz
        Map<Candidate, List<Match>> matchesPerCandidate = new HashMap<>();

        for (Candidate cand : candidates) {
            for (RefContour ref : refContours) {

                double shape = Imgproc.matchShapes(
                        ref.contour, cand.contour,
                        Imgproc.CONTOURS_MATCH_I1, 0);

                double areaSim = Math.min(ref.area, cand.area) / Math.max(ref.area, cand.area);

                double aspSim = 1.0 - Math.min(1.0,
                        Math.abs(ref.aspect - cand.aspect) / Math.max(0.01, ref.aspect));

                double cost = shape
                        + 0.5 * (1.0 - areaSim)
                        + 0.3 * (1.0 - aspSim);

                matchesPerCandidate
                    .computeIfAbsent(cand, k -> new ArrayList<>())
                    .add(new Match(ref.name, cost, cand));
            }
        }
        
        /*
        List<Match> matches = new ArrayList<>();
        for (Candidate cand : candidates) {
            for (RefContour ref : refContours) {

                double shape = Imgproc.matchShapes(ref.contour, cand.contour, Imgproc.CONTOURS_MATCH_I1, 0);

                double areaSim = Math.min(ref.area, cand.area) / Math.max(ref.area, cand.area);

                double aspSim = 1.0 - Math.min(1.0,
                        Math.abs(ref.aspect - cand.aspect) / Math.max(0.01, ref.aspect));

                double cost = shape
                        + 0.5 * (1.0 - areaSim)
                        + 0.3 * (1.0 - aspSim);

                matches.add(new Match(ref.name, cost, cand));
            }
        }
        matches.sort(Comparator.comparingDouble(m -> m.distance));
        

        System.out.println("=== Best matches (top 10) ===");
        for (int i = 0; i < Math.min(10, matches.size()); i++) {
            Match m = matches.get(i);
            System.out.println(
                i + ": ref='" + m.name +
                "' cost=" + String.format(Locale.ROOT, "%.4f", m.distance) +
                " bbox=" + m.candidate.bbox +
                " area=" + String.format(Locale.ROOT, "%.0f", m.candidate.area) +
                " aspect=" + String.format(Locale.ROOT, "%.2f", m.candidate.aspect)
            );
        }
*/
        
        // Greedy Auswahl
        Set<Candidate> usedCandidates = new HashSet<>();
        Set<String> usedRefs = new HashSet<>();
        
        // start
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<DetectedForm> detections = new ArrayList<>();

        for (Map.Entry<Candidate, List<Match>> e : matchesPerCandidate.entrySet()) {
            Candidate cand = e.getKey();
            List<Match> ms = e.getValue();

            ms.sort(Comparator.comparingDouble(m -> m.distance));

            
            System.out.println("Candidate " + cand.bbox + " best matches:");
            for (int i = 0; i < Math.min(5, ms.size()); i++) {
                Match m = ms.get(i);
                System.out.println("  " + i + ": " + m.name + " cost=" + m.distance);
            }
            
            
            Match best = ms.get(0);

            // Schwelle anwenden
            if (best.distance > maxShapeDistance) {
                continue;
            }

            double score = 1.0 / (1.0 + best.distance);

            detections.add(new DetectedForm(
                    best.name,
                    cand.bbox,
                    0,
                    score
            ));

            counts.put(best.name, counts.getOrDefault(best.name, 0) + 1);
        }
        // end
        
        /*
        for (Match m : matches) {
            if (detections.size() >= maxDetectionsTotal) break;
            if (m.distance > maxShapeDistance) break;
            if (usedCandidates.contains(m.candidate)) continue;
            if (!allowMultiplePerReference && usedRefs.contains(m.name)) continue;

            usedCandidates.add(m.candidate);
            usedRefs.add(m.name);

            double score = 1.0 / (1.0 + m.distance);
            detections.add(new DetectedForm(m.name, m.candidate.bbox, 0, score));
            counts.put(m.name, counts.getOrDefault(m.name, 0) + 1);
        }
        */
        

        // Annotieren
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
        capEdges.release();
        capBgr.release();

        return new GroupRecognitionResult(annotatedOut, counts, detections);
    }

    // ---------- Candidates ----------
    private List<Candidate> findCandidates(Mat edges, double minArea) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
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

    // ---------- Reference Cache ----------
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

            Mat refEdges = edges(refBgr);

            String name = stripExt(ref.getFileName().toString());

            if (debugWriteImages) {
                Imgcodecs.imwrite(workDir.resolve("debug_ref_" + safe(name) + "_edges.png").toString(), refEdges);
            }

            // größte Kontur
            MatOfPoint best = largestContour(refEdges, minContourArea * 0.6); // Referenzen dürfen kleiner sein
            refEdges.release();
            refBgr.release();

            if (best == null) return null;

            MatOfPoint norm = normalizeContour(best);
            best.release();

            // eingefügt start
            double a = Imgproc.contourArea(norm);
            Rect r = Imgproc.boundingRect(norm);
            double aspect = r.height > 0 ? (double) r.width / (double) r.height : 0.0;

            RefContour entry = new RefContour(ref, name, norm, lm, a, aspect);
            // eingefügt ende
            //RefContour entry = new RefContour(ref, name, norm, lm);
            
            
            RefContour old = refCache.put(ref, entry);
            if (old != null) old.release();

            return entry;

        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Image Ops ----------
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

    /**
     * Robuste Kanten: Blur -> Canny -> Dilate (Kanten schließen)
     */
    /*
    private static Mat edges(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);

        Mat blur = new Mat();
        Imgproc.GaussianBlur(gray, blur, new Size(5, 5), 0);

        Mat e = new Mat();
        Imgproc.Canny(blur, e, 60, 140);

        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        Imgproc.dilate(e, e, k);

        k.release();
        gray.release();
        blur.release();
        return e;
    }
    */
    
    private static Mat edges(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);

        Mat blur = new Mat();
        Imgproc.GaussianBlur(gray, blur, new Size(5, 5), 0);

        Mat e = new Mat();
        Imgproc.Canny(blur, e, 60, 140);

        // 1) erst dilate (Kanten "dicker")
        Mat k1 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        Imgproc.dilate(e, e, k1);

        // 2) dann CLOSE (Lücken schließen)
        Mat k2 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(7, 7));
        Imgproc.morphologyEx(e, e, Imgproc.MORPH_CLOSE, k2);

        k1.release();
        k2.release();
        gray.release();
        blur.release();
        return e;
    }


    private static MatOfPoint largestContour(Mat edges, double minArea) {
        List<MatOfPoint> cs = new ArrayList<>();
        Mat hier = new Mat();
        Imgproc.findContours(edges, cs, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
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
        return best; // caller releases
    }

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

    // ---------- Drawing ----------
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

    // ---------- Saving ----------
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

    // ---------- Utils ----------
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
        synchronized (OpenCvRecognitionGroupContourServiceV2.class) {
            if (OPENCV_LOADED) return;
            System.loadLibrary("opencv_java460");
            OPENCV_LOADED = true;
        }
    }

    // ---------- Types ----------
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
        final double distance;
        final Candidate candidate;

        Match(String name, double distance, Candidate candidate) {
            this.name = name;
            this.distance = distance;
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
        void release() { if (contour != null) contour.release(); }
    }

    private static List<Candidate> suppressNestedCandidates(List<Candidate> in, double containmentThreshold) {
        // sortiere groß -> klein
        List<Candidate> list = new ArrayList<>(in);
        list.sort((a, b) -> Double.compare(b.area, a.area));

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

}
