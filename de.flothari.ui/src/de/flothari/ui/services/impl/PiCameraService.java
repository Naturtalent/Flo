package de.flothari.ui.services.impl;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;

import jakarta.inject.Singleton;
import org.eclipse.e4.core.di.annotations.Creatable;

import de.flothari.ui.settings.AppSettings;
import de.flothari.ui.services.CameraService;

/**
 * Download capture vom pi und speichert es im lokalen WorkingDir
 * Der Pfad des vom PI geladenen Bildes wird zurueckgegeben.
 */
@Creatable
@Singleton
public class PiCameraService implements CameraService {

    // Default Snapshot-Parameter (kannst du später in Settings packen)
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    // HTTP timeouts
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 8000;

    @Override
    public Path capture() throws Exception {
        AppSettings s = new AppSettings();

        Path workDir = s.getWorkDir();
        Files.createDirectories(workDir);

        Path target = workDir.resolve("capture.png");

        String baseUrl = s.getPiBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Pi Base URL ist nicht gesetzt (Einstellungen).");
        }

        URI uri = URI.create(trimTrailingSlash(baseUrl)
                + "/snapshot?width=" + WIDTH + "&height=" + HEIGHT);

        downloadToFile(uri.toURL(), target);

        // einfache Plausibilitätsprüfung
        if (!Files.exists(target) || Files.size(target) < 1024) {
            throw new IllegalStateException("Snapshot-Datei ist leer oder zu klein: " + target);
        }

        return target;
    }

    // ----------------- intern -----------------

    private static void downloadToFile(URL url, Path target) throws Exception {
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(CONNECT_TIMEOUT_MS);
        con.setReadTimeout(READ_TIMEOUT_MS);

        int code = con.getResponseCode();
        if (code != 200) {
            // Fehlertext lesen (falls JSON)
            String msg;
            try (InputStream err = con.getErrorStream()) {
                msg = (err != null) ? new String(err.readAllBytes()) : "";
            }
            throw new IllegalStateException("Pi Snapshot HTTP " + code + " " + con.getResponseMessage()
                    + (msg.isBlank() ? "" : "\n" + msg));
        }

        try (InputStream in = con.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            con.disconnect();
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
