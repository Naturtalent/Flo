package de.flothari.regalsystem;

import uk.co.caprica.vlcj.factory.*;
import uk.co.caprica.vlcj.player.base.*;

public class StartLocalVLCdirekt
{
	public static void main(String[] args)
	{
		MediaPlayerFactory factory = new MediaPlayerFactory();
		MediaPlayer player = factory.mediaPlayers().newMediaPlayer();

		// player.media().play("C:/videos/test.mp4");
		player.media().play("rtp://87.141.215.251@232.0.20.222:10000");
		
		try
		{
			Thread.sleep(2000);
		} catch (Exception e)
		{
		}

		// player.snapshots().save("C:/snaps/snap.png");

		player.release();
		factory.release();
	}
}
