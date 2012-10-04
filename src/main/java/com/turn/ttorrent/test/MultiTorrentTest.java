package com.turn.ttorrent.test;

import com.turn.ttorrent.client.ClientSharedTorrent;
import com.turn.ttorrent.client.MultiTorrentClient;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;


public class MultiTorrentTest {
	
	private static String ROOT_FILE_DIR = "/Users/cshoff/Desktop/torrent/";
	
	private static String CLIENT_TYPE = "MULTI";

	/**
	 * @param args
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 */
	public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
		Tracker tracker = new Tracker(new InetSocketAddress(6969));
		tracker.announce(TrackedTorrent.load(new File(ROOT_FILE_DIR + "file1.torrent")));
		tracker.announce(TrackedTorrent.load(new File(ROOT_FILE_DIR + "file2.torrent")));
		tracker.announce(TrackedTorrent.load(new File(ROOT_FILE_DIR + "file3.torrent")));
		
		tracker.start();
		
		if ("SINGLE".equals(CLIENT_TYPE)) {
			SharedTorrent torrent1 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file1.torrent"), 
					new File(ROOT_FILE_DIR + "client1"), false);
			SharedTorrent torrent2 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file2.torrent"), 
					new File(ROOT_FILE_DIR + "client1"), false);
			SharedTorrent torrent3 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client1"), false);
			
			SharedTorrent torrent1_2 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file1.torrent"), 
					new File(ROOT_FILE_DIR + "client2"), false);
			SharedTorrent torrent2_2 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file2.torrent"), 
					new File(ROOT_FILE_DIR + "client2"), false);
			SharedTorrent torrent3_2 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client2"), false);
			
			SharedTorrent torrent1_3 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client3"), false);
			SharedTorrent torrent2_3 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client3"), false);
			SharedTorrent torrent3_3 = SharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client3"), false);
			
/*
			Client client1 = new Client(InetAddress.getLocalHost(), torrent1);
			client1.share();
			
			Client client2 = new Client(InetAddress.getLocalHost(), torrent1_2);
			client2.share();
			
			Client client3 = new Client(InetAddress.getLocalHost(), torrent1_3);
			client3.share();
			
			Client client4 = new Client(InetAddress.getLocalHost(), torrent2);
			client4.share();
			
			Client client5 = new Client(InetAddress.getLocalHost(), torrent2_2);
			client5.share();
			
			Client client6 = new Client(InetAddress.getLocalHost(), torrent2_3);
			client6.share();
			
			Client client7 = new Client(InetAddress.getLocalHost(), torrent3);
			client7.share();
			
			Client client8 = new Client(InetAddress.getLocalHost(), torrent3_2);
			client8.share();
			
			Client client9 = new Client(InetAddress.getLocalHost(), torrent3_3);
			client9.share();
*/

		} else {
			ClientSharedTorrent torrent1 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file1.torrent"), 
					new File(ROOT_FILE_DIR + "client1"), false);
			ClientSharedTorrent torrent2 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file2.torrent"), 
					new File(ROOT_FILE_DIR + "client1"), false);
			ClientSharedTorrent torrent3 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client1"), false);
			/*
			ClientSharedTorrent torrent1_2 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file1.torrent"), 
					new File(ROOT_FILE_DIR + "client2"));
			ClientSharedTorrent torrent2_2 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file2.torrent"), 
					new File(ROOT_FILE_DIR + "client2"));
			ClientSharedTorrent torrent3_2 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client2"));
			
			ClientSharedTorrent torrent1_3 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file1.torrent"), 
					new File(ROOT_FILE_DIR + "client3"));
			ClientSharedTorrent torrent2_3 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file2.torrent"), 
					new File(ROOT_FILE_DIR + "client3"));
			ClientSharedTorrent torrent3_3 = ClientSharedTorrent.fromFile(
					new File(ROOT_FILE_DIR + "file3.torrent"), 
					new File(ROOT_FILE_DIR + "client3"));
			*/
			MultiTorrentClient client1 = new MultiTorrentClient(InetAddress.getLocalHost());
			client1.addTorrent(torrent1);
			client1.addTorrent(torrent2);
			client1.addTorrent(torrent3);
			client1.share(torrent1.getHexInfoHash());
			client1.share(torrent2.getHexInfoHash());
			client1.share(torrent3.getHexInfoHash());
			/*
			MultiTorrentClient client2 = new MultiTorrentClient(InetAddress.getLocalHost());
			client2.addTorrent(torrent1_2);
			client2.addTorrent(torrent2_2);
			client2.addTorrent(torrent3_2);
			client2.share(torrent1_2.getHexInfoHash());
			client2.share(torrent2_2.getHexInfoHash());
			client2.share(torrent3_2.getHexInfoHash());
			
			MultiTorrentClient client3 = new MultiTorrentClient(InetAddress.getLocalHost());
			client3.addTorrent(torrent1_3);
			client3.addTorrent(torrent2_3);
			client3.addTorrent(torrent3_3);
			client3.share(torrent1_3.getHexInfoHash());
			client3.share(torrent2_3.getHexInfoHash());
			client3.share(torrent3_3.getHexInfoHash());
			*/
		}
		
		
		
		/*Client client2 = new Client(
				InetAddress.getLocalHost(),
				SharedTorrent.fromFile(
						new File(ROOT_FILE_DIR + "file1.torrent"), 
						new File(ROOT_FILE_DIR + "client2"))
				);
		client2.download();*/
	}

}
