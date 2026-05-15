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

public class CameraComparePart {

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
        Display.getDefault().asyncExec(() -> {
            if (resultLabel == null || resultLabel.isDisposed()) return;

            Path capture = findCaptureFile(new AppSettings().getWorkDir());
            if (capture == null) {
                disposeResultImage();
                resultLabel.setText("Keine capture-Datei gefunden.");
                resultLabel.setImage(null);
                return;
            }

            try {
                Image original = new Image(Display.getDefault(), capture.toString());

                // verfügbare Fläche im Label (abzüglich Rahmen)
                Rectangle area = resultLabel.getBounds();
                int maxW = Math.max(1, area.width);
                int maxH = Math.max(1, area.height);

                Image fitted = scaleToFit(original, maxW, maxH);

                // original nur entsorgen, wenn wir ein neues Image erzeugt haben
                if (fitted != original) {
                    original.dispose();
                }

                disposeResultImage();
                resultImage = fitted;

                // SWT-Quirk bei dir: erst Text, dann Image
                resultLabel.setText("");
                resultLabel.setImage(resultImage);

                resultLabel.getParent().layout(true, true);

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
    
    private static Image scaleToFit(Image src, int maxW, int maxH) {
        Rectangle b = src.getBounds();
        int sw = b.width;
        int sh = b.height;

        if (sw <= 0 || sh <= 0 || maxW <= 0 || maxH <= 0) {
            return src; // nichts zu tun
        }

        double sx = (double) maxW / (double) sw;
        double sy = (double) maxH / (double) sh;
        double s = Math.min(sx, sy);

        // nicht hochskalieren? (optional)
        // s = Math.min(1.0, s);

        int tw = Math.max(1, (int) Math.round(sw * s));
        int th = Math.max(1, (int) Math.round(sh * s));

        Image scaled = new Image(Display.getDefault(), tw, th);
        GC gc = new GC(scaled);
        try {
            gc.setAntialias(SWT.ON);
            gc.setInterpolation(SWT.HIGH);
            gc.drawImage(src, 0, 0, sw, sh, 0, 0, tw, th);
        } finally {
            gc.dispose();
        }
        return scaled;
    }

}
