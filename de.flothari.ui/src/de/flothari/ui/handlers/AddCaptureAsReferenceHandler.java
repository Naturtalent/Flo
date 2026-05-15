package de.flothari.ui.handlers;

import java.nio.file.*;
import java.util.List;
import java.util.Locale;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.core.di.annotations.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.services.impl.LocalFileCameraService;
import de.flothari.ui.settings.AppSettings;

public class AddCaptureAsReferenceHandler
{

	@Inject private LocalFileCameraService cameraService;
	
	//private static final List<String> CAPTURE_CANDIDATES = List.of("capture.png", "capture.jpg", "capture.jpeg");

	@Execute
	public void execute(@Optional @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
	{
		if (shell == null)
		{
			shell = org.eclipse.swt.widgets.Display.getDefault().getActiveShell();
		}

		try
		{
			AppSettings s = new AppSettings();
			Path workDir = s.getWorkDir();
			Path capture = cameraService.capture();
						
			if (capture == null)
			{
				MessageDialog.openError(shell, "Referenz hinzufügen",
						"Keine capture-Datei gefunden.\nErwartet im WorkDir:\n" + String.join(", ", cameraService.getCandidates())
								+ "\n\nWorkDir:\n" + workDir);
				return;
			}

			Path refDir = workDir.resolve("ref_bilder");
			Files.createDirectories(refDir);

			String defaultName = stripExt(capture.getFileName().toString());

			InputDialog dlg = new InputDialog(shell, "Neues Referenzbild", "Name für das Referenzbild (ohne Endung):",
					defaultName, input -> validateName(input));

			if (dlg.open() != Window.OK)
			{
				return;
			}

			String baseName = dlg.getValue().trim();
			String ext = getExt(capture.getFileName().toString());
			Path target = refDir.resolve(baseName + "." + ext);

			if (Files.exists(target))
			{
				boolean overwrite = MessageDialog.openQuestion(shell, "Datei existiert",
						"Das Referenzbild existiert bereits:\n" + target.getFileName() + "\n\nÜberschreiben?");
				if (!overwrite)
					return;
			}

			Files.copy(capture, target, StandardCopyOption.REPLACE_EXISTING);

			MessageDialog.openInformation(shell, "Referenz hinzugefügt", "Gespeichert als:\n" + target);

		} catch (Exception ex)
		{
			MessageDialog.openError(shell, "Fehler", ex.getMessage() != null ? ex.getMessage() : ex.toString());
		}
	}

	// -------- helpers --------

	/*
	private static Path findCapture(Path workDir)
	{
		if (workDir == null)
			return null;
		for (String name : CAPTURE_CANDIDATES)
		{
			Path p = workDir.resolve(name);
			if (Files.exists(p) && Files.isRegularFile(p))
				return p;
		}
		return null;
	}
	*/

	private static String validateName(String input)
	{
		if (input == null)
			return "Bitte einen Namen eingeben.";
		String s = input.trim();
		if (s.isEmpty())
			return "Bitte einen Namen eingeben.";

		// Verhindert Ordner-Tricks / ungültige Namen (plattformneutral)
		if (s.contains("/") || s.contains("\\") || s.contains(".."))
			return "Ungültiger Name.";
		if (s.length() > 64)
			return "Name zu lang (max. 64 Zeichen).";

		// erlaubt: Buchstaben, Zahlen, -, _, Leerzeichen
		for (char c : s.toCharArray())
		{
			if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' '))
			{
				return "Nur Buchstaben, Zahlen, Leerzeichen, '-' und '_' erlaubt.";
			}
		}
		return null; // ok
	}

	private static String stripExt(String name)
	{
		int i = name.lastIndexOf('.');
		return (i < 0) ? name : name.substring(0, i);
	}

	private static String getExt(String name)
	{
		int i = name.lastIndexOf('.');
		String ext = (i < 0) ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
		if (!List.of("png", "jpg", "jpeg").contains(ext))
		{
			// sollte hier nie passieren, da capture candidates genau diese sind
			ext = "png";
		}
		return ext;
	}
}
