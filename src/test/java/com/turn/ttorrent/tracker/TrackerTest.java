package com.turn.ttorrent.tracker;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import junit.framework.TestCase;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@Test
public class TrackerTest extends TestCase {
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;

  @Override
  @BeforeTest
  protected void setUp() throws Exception {
    super.setUp();
    tempFiles = new TempFiles();
    startTracker();
  }

  public void test_announce() throws IOException, NoSuchAlgorithmException {
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    this.tracker.announce(loadTorrent("file1.jar.torrent"));

    assertEquals(1, this.tracker.getTrackedTorrents().size());
  }

  public void test_share_and_download() throws IOException, NoSuchAlgorithmException {
    final TrackedTorrent tt = this.tracker.announce(loadTorrent("file1.jar.torrent"));
    assertEquals(0, tt.getPeers().size());

    Client seeder = createClient("file1.jar.torrent");
    assertEquals(tt.getHexInfoHash(), seeder.getTorrent().getHexInfoHash());

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient("file1.jar.torrent", downloadDir);

    try {
      seeder.share();

      new WaitFor() {
        @Override
        protected boolean condition() {
          return tt.getPeers().size() == 1;
        }
      };

      Map<String,TrackedPeer> peers = tt.getPeers();
      assertEquals(1, peers.size());
      assertTrue(peers.values().iterator().next().isCompleted()); // seed
      assertEquals(1, tt.seeders());

      leech.download();

      new WaitFor() {
        @Override
        protected boolean condition() {
          return new File(downloadDir, "file1.jar").isFile();
        }
      };
    } finally {
      leech.stop(true);
      seeder.stop(true);
    }
  }

  private TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
    return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name), null));
  }


  @Override
  @AfterTest
  protected void tearDown() throws Exception {
    super.tearDown();
    stopTracker();
    tempFiles.cleanup();
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(new InetSocketAddress(6969));
    this.tracker.start();
  }

  private Client createClient(String name) throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    return new Client(InetAddress.getLocalHost(), SharedTorrent.fromFile(torrentFile, parentFiles, false));
  }

  private Client createClient(String name, File destDir) throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    return new Client(InetAddress.getLocalHost(), SharedTorrent.fromFile(torrentFile, destDir, false));
  }

  private void stopTracker() {
    this.tracker.stop();
  }
}
