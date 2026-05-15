package de.flothari.ui.parts;

import java.nio.file.*;
import java.nio.file.Path;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

import de.flothari.ui.services.impl.CameraLiveService;
import de.flothari.ui.settings.AppSettings;

public class CameraComparePartORG {

    @Inject private UISynchronize ui;
    @Inject private CameraLiveService live;

    private Label liveLabel;
    private Label resultLabel;

    private Image liveImage;
    private Image resultImage;


    @PostConstruct
    public void create(Composite parent) {

        parent.setLayout(new FillLayout());

        // Trenner, horizontal nebeneinander
        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);

        // --------- Links: Live ---------
        Group liveGroup = new Group(sash, SWT.NONE);
        liveGroup.setText("Live");
        liveGroup.setLayout(new FillLayout());

        liveLabel = new Label(liveGroup, SWT.BORDER);
        liveLabel.setText("Livebild…");

        // --------- Rechts: Ergebnis ---------
        Group resultGroup = new Group(sash, SWT.NONE);
        resultGroup.setText("Ergebnis");
        resultGroup.setLayout(new GridLayout(1, false));

        resultLabel = new Label(resultGroup, SWT.BORDER);
        resultLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        resultLabel.setText("Noch kein Schnappschuss…");

        Button btnRefresh = new Button(resultGroup, SWT.PUSH);
        btnRefresh.setText("Ergebnis neu laden");
        btnRefresh.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        btnRefresh.addListener(SWT.Selection, e -> refreshResult());

        // Anfangsbreite (z.B. 60/40)
        sash.setWeights(new int[] { 60, 40 });

        // Live-Frames abonnieren
        live.setFrameListener(new CameraLiveService.FrameListener() {
            @Override
            public void onFrame(ImageData imageData) {
                ui.asyncExec(() -> updateLiveImage(imageData));
            }

            @Override
            public void onError(String message) {
                ui.asyncExec(() -> {
                    if (liveLabel != null && !liveLabel.isDisposed()) {
                        liveLabel.setText(message);
                    }
                });
            }
        });
        
        if (!live.isRunning()) {
            live.start();
        }


        // Optional: Ergebnis einmal beim Start laden
        refreshResult();
    }

    private void updateLiveImage(ImageData imageData) {
        if (liveLabel == null || liveLabel.isDisposed()) return;

        disposeLiveImage();

        liveImage = new Image(Display.getDefault(), imageData);
        liveLabel.setText("");
        liveLabel.setImage(liveImage);
    }

    /** Lädt capture.(png|jpg|jpeg) aus dem WorkDir und zeigt es rechts an. */
    public void refreshResult() {
        ui.asyncExec(() -> {
            if (resultLabel == null || resultLabel.isDisposed()) return;

            Path capture = findCaptureFile(new AppSettings().getWorkDir());
            if (capture == null) {
                disposeResultImage();
                resultLabel.setImage(null);
                resultLabel.setText("Keine capture-Datei gefunden (png/jpg/jpeg).");
                return;
            }

            try {
                Image img = new Image(Display.getDefault(), capture.toString());
                disposeResultImage();
                resultImage = img;

                resultLabel.setText("");
                resultLabel.setImage(resultImage);
                
            } catch (Exception ex) {
                disposeResultImage();
                resultLabel.setImage(null);
                resultLabel.setText("Fehler beim Laden: " + ex.getMessage());
            }
        });
    }

    private static Path findCaptureFile(Path workDir) {
        if (workDir == null) return null;
        List<String> names = List.of("capture.png", "capture.jpg", "capture.jpeg");
        for (String n : names) {
            Path p = workDir.resolve(n);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private void disposeLiveImage() {
        if (liveImage != null && !liveImage.isDisposed()) {
            liveImage.dispose();
        }
        liveImage = null;
    }

    private void disposeResultImage() {
        if (resultImage != null && !resultImage.isDisposed()) {
            resultImage.dispose();
        }
        resultImage = null;
    }

    @PreDestroy
    public void dispose() {
        live.setFrameListener(null);

        disposeLiveImage();
        disposeResultImage();
    }
}
