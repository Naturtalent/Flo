package de.flothari.ui.services.impl;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.domain.Detection;
import de.flothari.ui.services.InventoryService;

@Creatable
@Singleton
public class InMemoryInventoryService implements InventoryService {

    @Override
    public void addDetection(Detection detection) throws Exception {
        // vorerst nur merken
        System.out.println("Detection gespeichert: " + detection.getReferenceName());
    }
}
