package de.flothari.ui.services;

import de.flothari.ui.domain.Detection;

/*
 * Interface zum Inventarisieren
 * ein erkanntes Bild im Regal einsortieren
 */
public interface InventoryService
{
	void addDetection(Detection detection) throws Exception;
}
