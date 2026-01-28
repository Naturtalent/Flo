package de.flothari.ui.services;

import java.nio.file.Path;
import de.flothari.ui.domain.MatchResult;

public interface RecognitionService {
    MatchResult match(Path captureFile) throws Exception;
}
