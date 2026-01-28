package de.flothari.ui.parts;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;

import de.flothari.ui.services.impl.CameraLiveService;

public class CameraLivePart {

    @Inject private UISynchronize ui;
    @Inject private CameraLiveService live;

    private Label imageLabel;
    private Image currentImage;

    @PostConstruct
    public void create(Composite parent) {
        parent.setLayout(new FillLayout());
        imageLabel = new Label(parent, SWT.NONE);

        live.setFrameListener(new CameraLiveService.FrameListener() {
            @Override
            public void onFrame(ImageData imageData) {
                ui.asyncExec(() -> updateImage(imageData));
            }

            @Override
            public void onError(String message) {
                ui.asyncExec(() -> {
                    if (imageLabel != null && !imageLabel.isDisposed()) {
                        imageLabel.setText(message);
                    }
                });
            }
        });
    }

    private void updateImage(ImageData imageData) {
        if (imageLabel == null || imageLabel.isDisposed()) return;

        if (currentImage != null && !currentImage.isDisposed()) {
            currentImage.dispose();
        }
        currentImage = new Image(Display.getDefault(), imageData);
        imageLabel.setImage(currentImage);
    }

    @PreDestroy
    public void dispose() {
        live.setFrameListener(null);
        // optional: live.stop();  // nur wenn Livebild immer mit Part schließen soll

        if (currentImage != null && !currentImage.isDisposed()) {
            currentImage.dispose();
        }
    }
}
