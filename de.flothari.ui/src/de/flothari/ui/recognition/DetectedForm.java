package de.flothari.ui.recognition;

import org.opencv.core.Rect;

/** Ein erkannter Treffer im Bild. */
public record DetectedForm(
        String name,
        Rect bbox,
        int inliers,
        double score
) {}
