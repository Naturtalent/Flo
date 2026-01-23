
package de.flothari.ui.parts;

import jakarta.inject.Inject;
import jakarta.annotation.PostConstruct;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.e4.core.commands.ECommandService;
import org.eclipse.e4.core.commands.EHandlerService;

public class RegalPart
{
	@Inject
	public RegalPart()
	{

	}
	
	@Inject
	ECommandService commandService;

	@Inject
	EHandlerService handlerService;

	@PostConstruct
	public void postConstruct(Composite parent)
	{
		//parent.setLayout(new org.eclipse.swt.layout.FillLayout());
		parent.setLayout(new FillLayout());

        Button button = new Button(parent, SWT.PUSH);
        button.setText("Klick mich");
        
      

        button.addListener(SWT.Selection, e -> {
            handlerService.executeHandler(
                commandService.createCommand(
                    "de.flothari.ui.command.vlcstartcommand", null
                )
            );
        });
	}

}