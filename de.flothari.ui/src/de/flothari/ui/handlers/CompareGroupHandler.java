package de.flothari.ui.handlers;

import java.nio.file.Path;

import jakarta.inject.Inject;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import de.flothari.ui.recognition.GroupRecognitionResult;
import de.flothari.ui.services.impl.LocalFileCameraService;
import de.flothari.ui.services.impl.OpenCvRecognitionGroupContourServiceV2;
import de.flothari.ui.services.impl.OpenCvRecognitionGroupContourServiceV3;
//import de.flothari.ui.services.impl.OpenCvRecognitionGroupContourService;
//import de.flothari.ui.services.impl.OpenCvRecognitionGroupService;
//import de.flothari.ui.services.impl.OpenCvRecognitionGroupServiceORG;

public class CompareGroupHandler
{
	@Inject
	private LocalFileCameraService cameraService; // capture Pfad
	@Inject
	private OpenCvRecognitionGroupContourServiceV3 group;
	@Inject
	private UISynchronize ui;

	@Execute
	public void execute(Shell shell)
	{
		new Thread(() ->
		{
			try
			{
				// du hast capture im WorkDir, also:
				//Path capture = new de.flothari.ui.settings.AppSettings().getWorkDir().resolve("capture.png");
				Path capture = cameraService.capture();
				
				GroupRecognitionResult r = group.recognizeGroup(capture);

				ui.asyncExec(() -> MessageDialog.openInformation(shell, "Erkennung",
						"Fertig.\nTreffer: " + r.detections().size() + "\nBild: " + r.annotatedImage().getFileName()
								+ "\nListe: recognition_results.csv / .json"));

			} catch (Exception ex)
			{
				ui.asyncExec(() -> MessageDialog.openError(shell, "Fehler", ex.getMessage()));
			}
		}, "compare-group").start();
	}
}
