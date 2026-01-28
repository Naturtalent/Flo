package de.flothari.ui.services;

import jakarta.inject.Singleton;

import org.eclipse.e4.core.contexts.ContextFunction;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.services.impl.PiCameraService;

@Creatable
@Singleton
public class CameraServiceProvider extends ContextFunction {

    @Override
    public Object compute(IEclipseContext context) {
        return context.get(PiCameraService.class);
    }
}
