package de.flothari.ui.services;

import de.flothari.ui.domain.Detection;

public interface InventoryService {
    void addDetection(Detection detection) throws Exception;
}
