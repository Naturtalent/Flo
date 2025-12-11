package de.flothari.regalsystem;

import org.apache.commons.net.telnet.TelnetClient;

import java.io.InputStream;
import java.io.PrintStream;

/*
 * Verbindung mit Gabi's Laptop herstellen und VLC oeffnen
 */
public class TestTelnetServeer
{
	public static void main(String[] args) throws Exception
	{
		TelnetClient telnet = new TelnetClient();
		//telnet.connect("hostname", 23);
		telnet.connect("192.168.178.69", 9999);
		
		InputStream in = telnet.getInputStream();
		//System.out.println("inputSream: "+in.read());
		
		PrintStream out = new PrintStream(telnet.getOutputStream());
		
		waitFor(in, "Password:");
		out.println("secret");
		

		out.println("add rtp://87.141.215.251@232.0.20.222:10000");
		out.flush();

		// Antwort lesen
		int ch;
		while ((ch = in.read()) != -1)
		{
			
			System.out.print((char) ch);
		}

		telnet.disconnect();
	}
	
	private static void waitFor(InputStream in, String... prompts) throws Exception
	{
		StringBuilder sb = new StringBuilder();
		int ch;
		while ((ch = in.read()) != -1)
		{
			sb.append((char) ch);
			for (String prompt : prompts)
			{
				if (sb.toString().contains(prompt))
				{
					return;
				}
			}
		}
	}

}
