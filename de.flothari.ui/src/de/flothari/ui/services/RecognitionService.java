package de.flothari.ui.services;

import java.nio.file.Path;
import de.flothari.ui.domain.MatchResult;

/**
 * Interface zum Bildvergleich.
 * Sourcebild wird mit den Referenzbilder verglichen und das 
 * Ergebnis als Matchresult zurueckgegeben.
 * Als Pfad der Referenzbilder wird das Arbeitsverzeichnis
 * (Setting) unterstellt.  
 */
public interface RecognitionService
{
	/**
	 * @param Path zum zuvergleichenden Bild
	 * @return
	 * @throws Exception
	 */
	MatchResult match(Path captureFile) throws Exception;
}
