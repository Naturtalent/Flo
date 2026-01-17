package de.flothari.regal;

import org.opencv.core.Core;

public class Tests
{
	public static void main(String[] args)
	{
		System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
		System.out.println("OpenCV Version: " + Core.getVersionString());
	}
}
