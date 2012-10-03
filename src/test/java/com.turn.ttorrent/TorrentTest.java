package com.turn.ttorrent;

import com.turn.ttorrent.common.Torrent;
import junit.framework.TestCase;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;

public class TorrentTest extends TestCase {
  @Test
  public void test_create_torrent() throws URISyntaxException, IOException, NoSuchAlgorithmException, InterruptedException {
    URI announceURI = new URI("http://localhost:6969/announceURI");
    String createdBy = "Test";
    Torrent t = Torrent.create(new File("src/test/resources/parentFiles/file1.jar"), announceURI, createdBy);
    assertEquals(createdBy, t.getCreatedBy());
    assertEquals(announceURI, t.getAnnounceList().get(0).get(0));
  }
}
