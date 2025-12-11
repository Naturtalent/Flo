package de.flothari.opencv;

import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.*;

public class ShapeMatchExample
{
	public static void main(String[] args)
	{
		String imagePath = "/home/dieter/MeineDaten/Flo/Regalsystem/opencv/";

		// Bilder laden
		Mat refGray = opencv_imgcodecs.imread(imagePath+"reference.jpg", opencv_imgcodecs.IMREAD_GRAYSCALE);
		Mat sceneGray = opencv_imgcodecs.imread(imagePath+"scene.jpg", opencv_imgcodecs.IMREAD_GRAYSCALE);

		if (refGray.empty() || sceneGray.empty())
		{
			System.out.println("Fehler beim Laden der Bilder.");
			return;
		}

		// Binarisieren
		Mat refBin = new Mat();
		Mat sceneBin = new Mat();

		opencv_imgproc.threshold(refGray, refBin, 0, 255, opencv_imgproc.THRESH_BINARY | opencv_imgproc.THRESH_OTSU);

		opencv_imgproc.threshold(sceneGray, sceneBin, 0, 255,
				opencv_imgproc.THRESH_BINARY | opencv_imgproc.THRESH_OTSU);

		// ------------------------------
		// 1) Konturen im Referenzbild
		// ------------------------------
		MatVector refContours = new MatVector();
		Mat refHierarchy = new Mat();

		opencv_imgproc.findContours(refBin, refContours, refHierarchy, opencv_imgproc.RETR_EXTERNAL,
				opencv_imgproc.CHAIN_APPROX_SIMPLE);

		if (refContours.size() == 0)
		{
			System.out.println("Keine Konturen im Referenzbild gefunden.");
			return;
		}

		// Größte Kontur bestimmen
		Mat refContour = getLargestContour(refContours);

		Mat refContour2f = new Mat();
		refContour.convertTo(refContour2f, opencv_core.CV_32F);

		// ------------------------------
		// 2) Konturen im Zielbild
		// ------------------------------
		MatVector sceneContours = new MatVector();
		Mat sceneHierarchy = new Mat();

		opencv_imgproc.findContours(sceneBin, sceneContours, sceneHierarchy, opencv_imgproc.RETR_EXTERNAL,
				opencv_imgproc.CHAIN_APPROX_SIMPLE);

		Mat sceneColor = opencv_imgcodecs.imread(imagePath+"scene.jpg", opencv_imgcodecs.IMREAD_COLOR);

		double similarityThreshold = 0.15;
		double bestScore = Double.MAX_VALUE;
		Rect bestRect = null;

		// ------------------------------
		// 3) Formvergleich
		// ------------------------------
		for (long i = 0; i < sceneContours.size(); i++)
		{

			Mat contour = sceneContours.get(i);

			double area = opencv_imgproc.contourArea(contour);
			if (area < 80)
				continue; // Filter für kleine Konturen

			Mat contour2f = new Mat();
			contour.convertTo(contour2f, opencv_core.CV_32F);

			double score = opencv_imgproc.matchShapes(refContour2f, contour2f, opencv_imgproc.CONTOURS_MATCH_I1, 0.0);

			System.out.println("Score: " + score);

			if (score < similarityThreshold)
			{
				Rect bbox = opencv_imgproc.boundingRect(contour);
				
				/*
				opencv_imgproc.rectangle(sceneColor, new Point(bbox.x(), bbox.y()),
						new Point(bbox.x() + bbox.width(), bbox.y() + bbox.height()), new Scalar(0, 0, 255, 0));
						*/
				
				
				/*
				opencv_imgproc.rectangle(
			            sceneColor,
			            bbox,
			            new Scalar(0, 0, 255, 0),  // BGR: Rot
			           15,                         // Linien-Dicke
			            opencv_imgproc.LINE_8,
			            0
			    );
			    */

				
				System.out.println("BBox: x=" + bbox.x() + ", y=" + bbox.y() + 
		                   ", w=" + bbox.width() + ", h=" + bbox.height());
				
				opencv_imgproc.rectangle(
				        sceneColor,
				        new Rect(10, 10, 50, 50),
				        new Scalar(0, 255, 0, 0),
				        3,
				        opencv_imgproc.LINE_8,
				        0
				);


				if (score < bestScore)
				{
					bestScore = score;
					bestRect = bbox;
				}
			}
		}

		opencv_imgcodecs.imwrite(imagePath+"result.png", sceneColor);

		System.out.println("Ergebnis gespeichert in result.png");
		if (bestRect != null)
			System.out.println("Bester Treffer-Score: " + bestScore);
	}

	// Hilfsfunktion: größte Kontur aus MatVector holen
	private static Mat getLargestContour(MatVector contours)
	{

		double maxArea = -1;
		Mat largest = null;

		for (long i = 0; i < contours.size(); i++)
		{
			Mat c = contours.get(i);
			double area = opencv_imgproc.contourArea(c);

			if (area > maxArea)
			{
				maxArea = area;
				largest = c;
			}
		}

		return largest;
	}
}
