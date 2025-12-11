package de.flothari.regalsystem;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ReadShutterEvent
{
	// Größe eines input_event in Linux
	private static final int EVENT_SIZE = 24;
	// timeval: 8 + 8 bytes, type: 2, code: 2, value: 4 = 24
	
	

	public static void main(String[] args)
	{
		// realer 'devicePath auf dem raspi .133
		String devicePath = "/dev/input/event9";
		
		/*
		if (args.length != 1)
		{
			System.out.println("Usage: sudo java ReadInputEvent /dev/input/eventX");
			return;
		}
		

		String devicePath = args[0];
		*/

		System.out.println("Reading input events from: " + devicePath);

		try (FileInputStream fis = new FileInputStream(devicePath))
		{
			byte[] buffer = new byte[EVENT_SIZE];

			while (true)
			{
				int read = fis.read(buffer);
				if (read != EVENT_SIZE)
				{
					continue;
				}

				ByteBuffer bb = ByteBuffer.wrap(buffer);
				bb.order(ByteOrder.LITTLE_ENDIAN);

				long tvSec = bb.getLong(); // seconds
				long tvUsec = bb.getLong(); // microseconds
				short type = bb.getShort();
				short code = bb.getShort();
				int value = bb.getInt();

				System.out.printf("Event: type=%d, code=%d, value=%d  time=%d.%06d%n", type, code, value, tvSec,
						tvUsec);

				// Beispiel-Auswertung: Tastendruck
				if (type == 1)
				{ // EV_KEY
					if (value == 1)
						System.out.println("KEY PRESSED: " + code);
					if (value == 0)
						System.out.println("KEY RELEASED: " + code);
				}
			}

		} catch (IOException e)
		{
			System.err.println("Error reading input device: " + e.getMessage());
		}
	}
}
