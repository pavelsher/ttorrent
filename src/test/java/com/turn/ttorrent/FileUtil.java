package com.turn.ttorrent;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class FileUtil {

  public static File getTempDirectory() {
    return new File(System.getProperty("java.io.tmpdir"));
  }

  public static void delete(File file) {
    for (int i=0; i<10; i++) {
      if (file.delete()) break;
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        //
      }
    }
  }

  public static void writeFile(File file, String content) throws IOException {
    FileWriter fw = null;
    try {
      fw = new FileWriter(file);
      fw.write(content);
    } finally {
      close(fw);
    }
  }

  public static void close(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        //
      }
    }
  }
}
