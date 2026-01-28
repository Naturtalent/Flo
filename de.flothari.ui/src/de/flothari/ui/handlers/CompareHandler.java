package de.flothari.ui.handlers;

import java.nio.file.Path;
import java.time.Instant;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.domain.Detection;
import de.flothari.ui.domain.MatchResult;
import de.flothari.ui.services.CameraService;
import de.flothari.ui.services.InventoryService;
import de.flothari.ui.services.RecognitionService;

public class CompareHandler
{

	@Inject
	private CameraService camera; // z.B. PiCameraService
	@Inject
	private RecognitionService recognition; // OpenCvRecognitionService
	@Inject
	private InventoryService inventory; // InMemoryInventoryService (später SQLite)
	@Inject
	private UISynchronize ui;
	

	@Inject @Named(IServiceConstants.ACTIVE_SHELL) Shell shell;
	
	@Execute
	public void execute()
	{
		Thread worker = new Thread(() ->
		{
			try
			{
				// 1) Snapshot holen (oder lokale capture.* nehmen)
				Path captureFile = camera.capture();

				// 2) Erkennen / matchen
				MatchResult result = recognition.match(captureFile);

				// 3) Ergebnis verarbeiten
				if (result != null && result.isHit())
				{

					inventory.addDetection(new Detection(Instant.now(), result.getReferenceName(), result.getScore()));

					ui.asyncExec(() -> MessageDialog.openInformation(shell, "Treffer",
							"Erkannt: " + result.getReferenceName() + "\nScore: "
									+ String.format(java.util.Locale.ROOT, "%.3f", result.getScore())
									+ "\nGood Matches: " + result.getGoodMatches() + "\n\nCapture: "
									+ captureFile.getFileName()));
				} else
				{
					ui.asyncExec(() -> MessageDialog.openInformation(shell, "Kein Treffer",
							"Keine ausreichende Übereinstimmung gefunden." + "\n\nCapture: "
									+ captureFile.getFileName()));
				}

			} catch (Exception ex)
			{
				ui.asyncExec(() -> MessageDialog.openError(shell, "Vergleich fehlgeschlagen", buildErrorMessage(ex)));
			}
		}, "compare-worker");

		worker.setDaemon(true);
		worker.start();
	}

	private static String buildErrorMessage(Exception ex)
	{
		String msg = ex.getMessage();
		if (msg == null || msg.isBlank())
			msg = ex.toString();
		return msg;
	}
}
