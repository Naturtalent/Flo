package de.flothari.ui.recognition;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Ergebnis der Gruppen-Erkennung inkl. annotiertem Bild + Trefferliste. */
public record GroupRecognitionResult(
        Path annotatedImage,
        Map<String, Integer> counts,
        List<DetectedForm> detections
) {}
