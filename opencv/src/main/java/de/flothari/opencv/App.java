package de.flothari.opencv;

import org.bytedeco.opencv.opencv_core.Mat;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_highgui.imshow;
import static org.bytedeco.opencv.global.opencv_highgui.waitKey;
import static org.bytedeco.opencv.global.opencv_highgui.destroyAllWindows;

public class App
{
	public static void main(String[] args)
	{
		System.out.println("Hello World!");		
		Mat bild = imread("/home/dieter/MeineDaten/Flo/Regalsystem/ChatCPT/Images/A-2-B.jpg");
        
        if (bild.empty()) {
            System.err.println("Bild nicht gefunden!");
            return;
        }

        Mat grau = new Mat();
        cvtColor(bild, grau, COLOR_BGR2GRAY);

        imshow("Original", bild);
        imshow("Graustufen", grau);
        waitKey(0);
        destroyAllWindows();
	}
}
