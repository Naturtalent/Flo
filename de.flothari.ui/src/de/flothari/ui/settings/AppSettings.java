package de.flothari.ui.settings;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

public class AppSettings {

    // Wichtig: muss dein Plugin-ID / Bundle-SymbolicName sein
    private static final String NODE = "de.flothari.ui";

    private static final String KEY_AUDIO_DEVICE_NAME = "vlc.audioDeviceName";
    private static final String KEY_WORK_DIR          = "app.workDir";

    private final IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(NODE);

    public String getAudioDeviceName() {
        return prefs.get(KEY_AUDIO_DEVICE_NAME, "");
    }

    public void setAudioDeviceName(String value) {
        prefs.put(KEY_AUDIO_DEVICE_NAME, value != null ? value.trim() : "");
        flushQuietly();
    }

    /** Verzeichnis, in dem capture.png + ref_bilder liegen sollen. */
    public Path getWorkDir() {
        String def = System.getProperty("user.dir"); // Default: App-Startverzeichnis
        String s = prefs.get(KEY_WORK_DIR, def);
        return Paths.get(s);
    }

    public void setWorkDir(Path dir) {
        if (dir == null) return;
        prefs.put(KEY_WORK_DIR, dir.toAbsolutePath().toString());
        flushQuietly();
    }

    private void flushQuietly() {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            // In der Praxis: loggen (Error Log), aber nicht die UI blockieren
            e.printStackTrace();
        }
    }
}
