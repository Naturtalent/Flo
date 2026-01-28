package de.flothari.ui.domain;

import java.time.Instant;

public class Detection {
    private final Instant timestamp;
    private final String referenceName;
    private final double score;

    public Detection(Instant timestamp, String referenceName, double score) {
        this.timestamp = timestamp;
        this.referenceName = referenceName;
        this.score = score;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getReferenceName() { return referenceName; }
    public double getScore() { return score; }
}
