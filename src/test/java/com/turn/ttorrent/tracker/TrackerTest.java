package com.turn.ttorrent.tracker;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import junit.framework.TestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;

@Test
public class TrackerTest extends TestCase {
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;

  @Override
  @BeforeMethod
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

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      leech.stop(true);
      seeder.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_seeder() throws IOException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient("file1.jar.torrent");

    try {
      seeder.share();

      new WaitFor() {
        @Override
        protected boolean condition() {
          return TrackerTest.this.tracker.getTrackedTorrents().size() == 1;
        }
      };

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<String,TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertTrue(peers.values().iterator().next().isCompleted()); // seed
      assertEquals(1, trackedTorrent.seeders());
      assertEquals(0, trackedTorrent.leechers());
    } finally {
      seeder.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_leech() throws IOException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient("file1.jar.torrent", downloadDir);

    try {
      leech.download();

      new WaitFor() {
        @Override
        protected boolean condition() {
          return TrackerTest.this.tracker.getTrackedTorrents().size() == 1;
        }
      };

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<String,TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertFalse(peers.values().iterator().next().isCompleted()); // leech
      assertEquals(0, trackedTorrent.seeders());
      assertEquals(1, trackedTorrent.leechers());
    } finally {
      leech.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_seeder_plus_leech() throws IOException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    Client seeder = createClient("file1.jar.torrent");

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient("file1.jar.torrent", downloadDir);

    try {
      seeder.share();
      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      seeder.stop(true);
      leech.stop(true);
    }
  }

/*
  public void utorrent_test_leech() throws IOException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    new WaitFor(3600 * 1000) {
      @Override
      protected boolean condition() {
        return TrackerTest.this.tracker.getTrackedTorrents().size() == 1;
      }
    };
  }
*/

/*
  public void utorrent_test_seed() throws IOException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    new WaitFor(3600 * 1000) {
      @Override
      protected boolean condition() {
        Collection<TrackedTorrent> trackedTorrents = TrackerTest.this.tracker.getTrackedTorrents();
        return trackedTorrents.size() != 0 && trackedTorrents.iterator().next().seeders() == 1;
      }
    };

    TrackedTorrent tt = this.tracker.getTrackedTorrents().iterator().next();
    assertEquals(1, tt.seeders());

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient("file1.jar.torrent", downloadDir);

    try {
      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      leech.stop(true);
    }
  }
*/


  private void waitForFileInDir(final File downloadDir, final String fileName) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        return new File(downloadDir, fileName).isFile();
      }
    };

    assertTrue(new File(downloadDir, fileName).isFile());
  }

  private TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
    return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name), null));
  }


  @Override
  @AfterMethod
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
