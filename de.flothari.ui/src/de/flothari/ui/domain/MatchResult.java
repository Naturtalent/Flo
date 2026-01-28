package de.flothari.ui.domain;

import java.nio.file.Path;

public class MatchResult {
    private final boolean hit;
    private final String referenceName;   // z.B. Dateiname ohne Endung
    private final double score;
    private final int goodMatches;
    private final Path referenceFile;

    public MatchResult(boolean hit, String referenceName, double score, int goodMatches, Path referenceFile) {
        this.hit = hit;
        this.referenceName = referenceName;
        this.score = score;
        this.goodMatches = goodMatches;
        this.referenceFile = referenceFile;
    }

    public boolean isHit() { return hit; }
    public String getReferenceName() { return referenceName; }
    public double getScore() { return score; }
    public int getGoodMatches() { return goodMatches; }
    public Path getReferenceFile() { return referenceFile; }
}
