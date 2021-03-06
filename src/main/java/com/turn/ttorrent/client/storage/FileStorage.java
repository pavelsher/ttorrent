/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client.storage;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;


/**
 * Single-file torrent byte data storage.
 *
 * <p>
 * This implementation of TorrentByteStorageFile provides a torrent byte data
 * storage relying on a single underlying file and uses a RandomAccessFile
 * FileChannel to expose thread-safe read/write methods.
 * </p>
 *
 * @author mpetazzoni
 */
public class FileStorage implements TorrentByteStorage {

	private static final Logger logger =
		LoggerFactory.getLogger(FileStorage.class);

	private final File target;
	private final File partial;
	private final long offset;
	private final long size;

	private File current;
  private RandomAccessFile raf;
  private Thread closeTask;

  public FileStorage(File file, long size) throws IOException {
		this(file, 0, size);
	}

	public FileStorage(File file, long offset, long size)
		throws IOException {
		this.target = file;
		this.offset = offset;
		this.size = size;

		this.partial = new File(this.target.getAbsolutePath() +
			TorrentByteStorage.PARTIAL_FILE_NAME_SUFFIX);

		if (this.partial.exists()) {
			logger.debug("Partial download found at {}. Continuing...",
				this.partial.getAbsolutePath());
			this.current = this.partial;
		} else if (!this.target.exists()) {
			logger.debug("Downloading new file to {}...",
				this.partial.getAbsolutePath());
			this.current = this.partial;
		} else {
			logger.debug("Using existing file {}.",
				this.target.getAbsolutePath());
			this.current = this.target;
		}

		logger.info("Initialized byte storage file at {} " +
			"({}+{} byte(s)).",
			new Object[] {
				this.current.getAbsolutePath(),
				this.offset,
				this.size,
			});
	}

  private RandomAccessFile openFile() throws IOException {
    try {
      if (this.raf != null) {
        return this.raf;
      }
      this.raf = new RandomAccessFile(this.current, "rw");
      if (this.raf.length() != this.size) {
        this.raf.setLength(this.size);
      }
      return this.raf;
    } finally {
      scheduleFileClose();
    }
  }

  private void scheduleFileClose() {
    if (this.closeTask != null) {
      this.closeTask.interrupt();
    }

    if (this.closeTask == null || !this.closeTask.isAlive()) {
      this.closeTask = new Thread(new Runnable() {
        @Override
        public void run() {
          boolean wait = true;
          while (wait) {
            try {
              Thread.sleep(1000);
              wait = false;
            } catch (InterruptedException e) {
              //
            }
          }

          try {
            close();
          } catch (IOException e) {
            //
          }
        }
      }, "Close file thread: " + this.current.getAbsolutePath());
      this.closeTask.start();
    }
  }

  protected long offset() {
		return this.offset;
	}

	@Override
	public long size() {
		return this.size;
	}

	@Override
	public synchronized int read(ByteBuffer buffer, long offset) throws IOException {
		int requested = buffer.remaining();

		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage read request!");
		}

    RandomAccessFile raf = openFile();
    FileChannel channel = raf.getChannel();
    int bytes = channel.read(buffer, offset);
    if (bytes < requested) {
      throw new IOException("Storage underrun!");
    }
    return bytes;
	}

	@Override
	public synchronized int write(ByteBuffer buffer, long offset) throws IOException {
		int requested = buffer.remaining();

		if (offset + requested > this.size) {
			throw new IllegalArgumentException("Invalid storage write request!");
		}

    RandomAccessFile raf = openFile();
    FileChannel channel = raf.getChannel();
    return channel.write(buffer, offset);
  }

	@Override
	public synchronized void close() throws IOException {
    if (this.raf != null) {
      this.raf.close();
      this.raf = null;
    }
	}

	/** Move the partial file to its final location.
	 */
	@Override
	public synchronized void finish() throws IOException {
		// Nothing more to do if we're already on the target file.
		if (this.isFinished()) {
			return;
		}

    close();

		FileUtils.deleteQuietly(this.target);
		FileUtils.moveFile(this.current, this.target);

		logger.debug("Re-opening torrent byte storage at {}.",
        this.target.getAbsolutePath());

		this.current = this.target;

		FileUtils.deleteQuietly(this.partial);
		logger.info("Moved torrent data from {} to {}.",
			this.partial.getName(),
			this.target.getName());
	}

	@Override
	public synchronized boolean isFinished() {
		return this.current.equals(this.target);
	}
}
