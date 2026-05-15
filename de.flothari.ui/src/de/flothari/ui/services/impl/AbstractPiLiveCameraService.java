package de.flothari.ui.services.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractPiLiveCameraService
{

	protected final String piUser;
	protected final String piHost;
	protected final String remoteWorkDir;

	protected AbstractPiLiveCameraService(String piUser, String piHost, String remoteWorkDir)
	{
		this.piUser = piUser;
		this.piHost = piHost;
		this.remoteWorkDir = remoteWorkDir;
	}
	
	protected void sshExec(String remoteCommand) throws Exception
	{
		List<String> cmd = new ArrayList<>();
		cmd.add("ssh");
		cmd.add(piUser + "@" + piHost);
		cmd.add(remoteCommand);
	
		Process p = new ProcessBuilder(cmd).start();	
		int exit = p.waitFor();

		if (exit != 0)
		{
			String err = readAll(p.getErrorStream());
			throw new IllegalStateException("SSH command failed: " + err);
		}		
	}
	
	protected String sshExecAndRead(String remoteCommand) throws Exception
	{
	
		List<String> cmd = new ArrayList<>();
		cmd.add("ssh");
		cmd.add(piUser + "@" + piHost);
		cmd.add(remoteCommand);

		Process p = new ProcessBuilder(cmd).start();
		String out = readAll(p.getInputStream());
		String err = readAll(p.getErrorStream());

		int exit = p.waitFor();
		if (exit != 0)
		{
			throw new IllegalStateException("SSH command failed: " + err);
		}
		return out.trim();
	}


	protected void startRemoteScript(String scriptName) throws Exception
	{	
		/*
		String cmd = "cd " + shellQuote(remoteWorkDir) + " && " + "nohup python3 -u " + shellQuote(scriptName)
		+ " > /tmp/" + scriptName + ".log 2>&1 &";
		*/
		
		// Start Skript
		String startScriptName = "cam-start.sh";		
		sshExec(shellQuote(remoteWorkDir+startScriptName));
	}

	protected void stopRemoteScript(String scriptName) throws Exception
	{
		String cmd = "pkill -f " + shellQuote(scriptName) + " || true";
		sshExec(cmd);
	}

	protected boolean isRemoteScriptRunning(String scriptName) throws Exception
	{
		String cmd = "pgrep -f " + shellQuote(scriptName) + " >/dev/null && echo RUNNING || echo STOPPED";
		String out = sshExecAndRead(cmd);
		return "RUNNING".equals(out);
	}

	private static String readAll(java.io.InputStream in) throws Exception
	{
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			StringBuilder sb = new StringBuilder();
			String line;
			
			while ((line = br.readLine()) != null)
			{
				sb.append(line).append('\n');
			}
			return sb.toString();
		}
	}
	
	private static String shellQuote(String s)
	{
		return "'" + s.replace("'", "'\"'\"'") + "'";
	}
}
