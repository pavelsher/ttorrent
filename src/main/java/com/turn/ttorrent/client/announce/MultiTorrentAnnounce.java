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
package com.turn.ttorrent.client.announce;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.ClientSharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;

/**
 * BitTorrent announce sub-system.
 *
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker(s) to get peers
 * and to report certain events.
 * </p>
 *
 * <p>
 * This Announce class implements a periodic announce request thread that will
 * notify announce request event listeners for each tracker response.
 * </p>
 *
 * @author mpetazzoni
 * @see com.turn.ttorrent.common.protocol.TrackerMessage
 */
public class MultiTorrentAnnounce implements Runnable {

	protected static final Logger logger =
		LoggerFactory.getLogger(MultiTorrentAnnounce.class);

	private final Peer peer;

	/** Announce thread and control. */
	private Thread thread;
	private boolean stop;
	private boolean forceStop;

	/** Announce interval. */
	private int interval;

	private MultiTorrentTrackerClient trackerClient;

	/**
	 * Initialize the base announce class members for the announcer.
	 *
	 * @param peer Our peer specification.
	 * @param type A string representing the announce type (used in the thread
	 * name).
	 * @throws UnknownServiceException 
	 * @throws UnknownHostException 
	 */
	public MultiTorrentAnnounce(Peer peer) throws UnknownHostException, UnknownServiceException {
		this.peer = peer;
		this.thread = null;
		this.trackerClient = this.createTrackerClient(peer);
	}
	
	/**
	 * Add a torrent to our announce loop.
	 * 
	 * @param torrent The torret we're announcing about
	 * @throws UnknownServiceException 
	 * @throws UnknownHostException 
	 */
	public void addTorrent(ClientSharedTorrent torrent) throws UnknownHostException, UnknownServiceException {
		
		/*
		 * Because we're not using them currently and for ease of use, I dropped the multi-tracker support.
		 * Having to maintain them with the latest torrent list would've been a pain. Might add it back in.
		 */
		if (this.trackerClient.getTrackerURI() == null) {
			URI tracker = torrent.getAnnounceList().get(0).get(0);
			this.trackerClient.setTrackerURI(tracker);
		}
		
		this.trackerClient.addTorrent(torrent);
		
		logger.info("Initialized announce sub-system with {} trackers on {}.",
				new Object[] { torrent.getTrackerCount(), torrent });
	}

	/**
	 * Register a new announce response listener.
	 *
	 * @param listener The listener to register on this announcer events.
	 */
	public void register(AnnounceResponseListener listener) {
		this.trackerClient.register(listener);
	}

	/**
	 * Start the announce request thread.
	 */
	public void start() {
		this.stop = false;
		this.forceStop = false;

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-announce(" +
				this.peer.getShortHexPeerId() + ")");
			this.thread.start();
		}
	}

	/**
	 * Set the announce interval.
	 */
	public void setInterval(int interval) {
		if (interval <= 0) {
			this.stop(true);
			return;
		}

		if (this.interval == interval) {
			return;
		}

		logger.info("Setting announce interval to {}s per tracker request.",
			interval);
		this.interval = interval;
	}

	/**
	 * Stop the announce thread.
	 *
	 * <p>
	 * One last 'stopped' announce event might be sent to the tracker to
	 * announce we're going away, depending on the implementation.
	 * </p>
	 */
	public void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();

			if (this.trackerClient != null) {
				this.trackerClient.close();
			}

			try {
				this.thread.join();
			} catch (InterruptedException ie) {
				// Ignore
			}
		}

		this.thread = null;
	}

	/**
	 * Main announce loop.
	 *
	 * <p>
	 * The announce thread starts by making the initial 'started' announce
	 * request to register on the tracker and get the announce interval value.
	 * Subsequent announce requests are ordinary, event-less, periodic requests
	 * for peers.
	 * </p>
	 *
	 * <p>
	 * Unless forcefully stopped, the announce thread will terminate by sending
	 * a 'stopped' announce request before stopping.
	 * </p>
	 */
	@Override
	public void run() {
		logger.info("Starting announce loop...");

		// Set an initial announce interval to 5 seconds. This will be updated
		// in real-time by the tracker's responses to our announce requests.
		this.interval = 5;

		AnnounceRequestMessage.RequestEvent event =
			AnnounceRequestMessage.RequestEvent.STARTED;

		while (!this.stop) {
			try {
				this.trackerClient.announce(event, false);
				event = AnnounceRequestMessage.RequestEvent.NONE;
			} catch (AnnounceException ae) {
				logger.warn(ae.getMessage());
			}

			try {
				Thread.sleep(this.interval * 1000);
			} catch (InterruptedException ie) {
				// Ignore
			}
		}

		logger.info("Exited announce loop.");

		if (!this.forceStop) {
			// Send the final 'stopped' event to the tracker after a little
			// while.
			event = AnnounceRequestMessage.RequestEvent.STOPPED;
			try {
				Thread.sleep(500);
			} catch (InterruptedException ie) {
				// Ignore
			}

			try {
				this.trackerClient.announce(event, true);
			} catch (AnnounceException ae) {
				logger.warn(ae.getMessage());
			}
		}
	}

	/**
	 * Create a {@link TrackerClient} annoucing to the given tracker address.
	 *
	 * @param torrent The torrent the tracker client will be announcing for.
	 * @param peer The peer the tracker client will announce on behalf of.
	 * @param tracker The tracker address as a {@link URI}.
	 * @throws UnknownHostException If the tracker address is invalid.
	 * @throws UnknownServiceException If the tracker protocol is not supported.
	 */
	private MultiTorrentTrackerClient createTrackerClient(Peer peer) throws UnknownHostException, UnknownServiceException {
		String scheme = "http";

		if ("http".equals(scheme) || "https".equals(scheme)) {
			return new MultiTorrentHTTPTrackerClient(peer);
		}/* else if ("udp".equals(scheme)) {
			return new UDPTrackerClient(torrent, peer, tracker);
		}*/

		throw new UnknownServiceException(
			"Unsupported announce scheme: " + scheme + "!");
	}

	/**
	 * Stop the announce thread.
	 *
	 * @param hard Whether to force stop the announce thread or not, i.e. not
	 * send the final 'stopped' announce request or not.
	 */
	private void stop(boolean hard) {
		this.forceStop = hard;
		this.stop();
	}

	public MultiTorrentTrackerClient getTrackerClient() {
		return trackerClient;
	}
}
