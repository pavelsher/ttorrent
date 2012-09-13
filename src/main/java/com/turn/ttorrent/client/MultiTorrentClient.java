package com.turn.ttorrent.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.Client.ClientState;
import com.turn.ttorrent.client.announce.AnnounceException;
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.announce.MultiTorrentAnnounce;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage;

public class MultiTorrentClient implements 
	Runnable, AnnounceResponseListener, IncomingConnectionListener, PeerActivityListener {
	
	private static final Logger logger =
			LoggerFactory.getLogger(MultiTorrentClient.class);
	
	private static final String BITTORRENT_ID_PREFIX = "-TO0042-";
	
	/** Peers unchoking frequency, in seconds. Current BitTorrent specification
	 * recommends 10 seconds to avoid choking fibrilation. */
	private static final int UNCHOKING_FREQUENCY = 3;

	/** Optimistic unchokes are done every 2 loop iterations, i.e. every
	 * 2*UNCHOKING_FREQUENCY seconds. */
	private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;
	
	private static final int RATE_COMPUTATION_ITERATIONS = 2;
	public static final int MAX_DOWNLOADERS_UNCHOKE = 4;
	private static final int VOLUNTARY_OUTBOUND_CONNECTIONS = 20;
	
	private MultiTorrentConnectionHandler service;
	private MultiTorrentAnnounce announce;
	private Peer self;
	
	Thread thread;
	
	private ConcurrentMap<String, ClientSharedTorrent> torrents = new ConcurrentHashMap<String, ClientSharedTorrent>();
	
	public MultiTorrentClient(InetAddress address)
			throws UnknownHostException, IOException {
		
		String id = MultiTorrentClient.BITTORRENT_ID_PREFIX + UUID.randomUUID()
				.toString().split("-")[4];

		// Initialize the incoming connection handler and register ourselves to
		// it.
		this.service = new MultiTorrentConnectionHandler(id, address);
		this.service.register(this);
		
		this.self = new Peer(
			this.service.getSocketAddress()
				.getAddress().getHostAddress(),
			(short)this.service.getSocketAddress().getPort(),
			ByteBuffer.wrap(id.getBytes(Torrent.BYTE_ENCODING)));
		
		// Initialize the announce request thread, and register ourselves to it
		// as well.
		this.announce = new MultiTorrentAnnounce(this.self);
		this.announce.register(this);
		
		start();
	}
	
	public void addTorrent(ClientSharedTorrent torrent) throws UnknownHostException, UnknownServiceException {
		this.torrents.put(torrent.getHexInfoHash(), torrent);
		this.announce.addTorrent(torrent);
		this.service.addTorrent(torrent);
	}
	
	public void start() {
		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-client(" +
				this.self.getShortHexPeerId() + ")");
			this.thread.start();
		}
	}
	
	public void share(String identifier) throws IOException {
		ClientSharedTorrent torrent = torrents.get(identifier);
		if (torrent != null) {
			torrent.share();
		}
	}

	@Override
	public void handleNewPeerConnection(Socket s, byte[] peerId, String hexInfoHash) {
		ClientSharedTorrent torrent = this.torrents.get(hexInfoHash);
		
		if (torrent == null) {
			logger.error("Peer connection received for a non-existent torrent {}", hexInfoHash);
			return;
		}
		
		Peer search = new Peer(
			s.getInetAddress().getHostAddress(),
			s.getPort(),
			(peerId != null
				? ByteBuffer.wrap(peerId)
				: (ByteBuffer)null));
	
		logger.info("Handling new peer connection with {}...", search);
		SharingPeer peer = torrent.getOrCreatePeer(search);
	
		try {
			synchronized (peer) {
				if (peer.isBound()) {
					logger.info("Already connected with {}, closing link.",
						peer);
					s.close();
					return;
				}
	
				peer.register(this);
				peer.bind(s);
			}
	
			torrent.getConnected().put(peer.getHexPeerId(), peer);
			peer.register(torrent);
			logger.debug("New peer connection with {} [{}/{}].",
				new Object[] {
					peer,
					torrent.getConnected().size(),
					torrent.getPeers().size()
				});
		} catch (Exception e) {
			torrent.getConnected().remove(peer.getHexPeerId());
			logger.warn("Could not handle new peer connection " +
					"with {}: {}", peer, e.getMessage());
		}
	}

	@Override
	public void handleFailedConnection(SharingPeer peer, Throwable cause) {
		ClientSharedTorrent torrent = this.torrents.get(peer.getTorrentHexInfoHash());
		
		if (torrent == null) {
			logger.error("Piece completed for unknown torrent {} (this shouldn't happen)", peer.getTorrentHexInfoHash());
			return;
		}
		
		logger.info("Could not connect to {}: {}.", peer, cause.getMessage());
		torrent.getPeers().remove(peer.getHostIdentifier());
		if (peer.hasPeerId()) {
			torrent.getPeers().remove(peer.getHexPeerId());
		}
	}

	@Override
	public void handlePieceCompleted(SharingPeer peer, Piece piece)
			throws IOException {
		ClientSharedTorrent torrent = this.torrents.get(peer.getTorrentHexInfoHash());
		
		if (torrent == null) {
			logger.error("Piece completed for unknown torrent {} (this shouldn't happen)", peer.getTorrentHexInfoHash());
			return;
		}
		
		synchronized (torrent) {
			if (piece.isValid()) {
				// Make sure the piece is marked as completed in the torrent
				// Note: this is required because the order the
				// PeerActivityListeners are called is not defined, and we
				// might be called before the torrent's piece completion
				// handler is.
				torrent.markCompleted(piece);
				logger.debug("Completed download of {}, now has {}/{} pieces.",
					new Object[] {
						piece,
						torrent.getCompletedPieces().cardinality(),
						torrent.getPieceCount()
					});

				// Send a HAVE message to all connected peers
				PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
				for (SharingPeer remote : torrent.getConnected().values()) {
					remote.send(have);
				}

				// Force notify after each piece is completed to propagate download
				// completion information (or new seeding state)
				torrent.change();
			}

			if (torrent.isComplete()) {
				logger.info("Last piece validated and completed, " +
						"download is complete.");
				
				torrent.finish();

				try {
					this.announce.getTrackerClient()
						.announce(TrackerMessage
							.AnnounceRequestMessage
							.RequestEvent.COMPLETED, true);
				} catch (AnnounceException ae) {
					logger.warn("Error announcing completion event to " +
						"tracker: {}", ae.getMessage());
				}

				torrent.seed();
			}
		}
	}

	@Override
	public void handlePeerDisconnected(SharingPeer peer) {
		ClientSharedTorrent torrent = this.torrents.get(peer.getTorrentHexInfoHash());
		
		if (torrent == null) {
			logger.error("Peer disconnected from a torrent we don't know about {} (this shouldn't happen)", peer.getTorrentHexInfoHash());
			return;
		}
		
		if (torrent.getConnected().remove(peer.hasPeerId()
					? peer.getHexPeerId()
					: peer.getHostIdentifier()) != null) {
			logger.debug("Peer {} disconnected, [{}/{}].",
				new Object[] {
					peer,
					torrent.getConnected().size(),
					torrent.getPeers().size()
				});
		}
	
		peer.reset();
	}

	@Override
	public void handleIOException(SharingPeer peer, IOException ioe) {
		System.out.println("HANDLE IO EXCEPTION");
	}

	@Override
	public void handleAnnounceResponse(int interval, int complete,
			int incomplete) {
		this.announce.setInterval(interval);
	}

	@Override
	public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {
		ClientSharedTorrent torrent = this.torrents.get(hexInfoHash);
		
		if (torrent == null) {
			logger.error("Discovered peers for a non-existent torrent {}", hexInfoHash);
			return;
		}
		
		if (peers == null || peers.isEmpty()) {
			// No peers returned by the tracker. Apparently we're alone on
			// this one for now.
			return;
		}

		logger.info("Got {} peer(s) in tracker response, initiating " +
			"connections...", peers.size());

		if (!this.service.isAlive()) {
			logger.warn("Connection handler service is not available.");
			return;
		}

		for (Peer peer : peers) {
			SharingPeer match = torrent.getOrCreatePeer(peer);

			synchronized (match) {
				// Attempt to connect to the peer if and only if:
				//   - We're not already connected to it;
				//   - We're not a seeder (we leave the responsibility
				//	   of connecting to peers that need to download
				//     something), or we are a seeder but we're still
				//     willing to initiate some out bound connections.
				if (match.isBound() ||
					(torrent.isComplete() && torrent.getConnected().size() >=
						MultiTorrentClient.VOLUNTARY_OUTBOUND_CONNECTIONS)) {
					return;
				}

				this.service.connect(match, torrent.getInfoHash());
			}
		}
	}

	@Override
	public void run() {
		this.announce.start();
		this.service.start();

		int optimisticIterations = 0;
		int rateComputationIterations = 0;
		
		while (!this.torrents.isEmpty()) {
			optimisticIterations =
					(optimisticIterations == 0 ?
							MultiTorrentClient.OPTIMISTIC_UNCHOKE_ITERATIONS :
								optimisticIterations - 1);

			rateComputationIterations =
					(rateComputationIterations == 0 ?
							MultiTorrentClient.RATE_COMPUTATION_ITERATIONS :
								rateComputationIterations - 1);
			
			for (ClientSharedTorrent torrent : this.torrents.values()) {
				if (!ClientState.SHARING.equals(torrent.getClientState()) && !ClientState.SEEDING.equals(torrent.getClientState())) {
					continue;
				}
				try {
					torrent.unchokePeers(optimisticIterations == 0);
					torrent.info();
					if (rateComputationIterations == 0) {
						torrent.resetPeerRates();
					}
				} catch (Exception e) {
					logger.error("An exception occurred during the BitTorrent " +
							"client main loop execution!", e);
				}
			}
			
			try {
				Thread.sleep(MultiTorrentClient.UNCHOKING_FREQUENCY*1000);
			} catch (InterruptedException ie) {
				logger.trace("BitTorrent main loop interrupted.");
			}
		}
	}
	

	/** PeerActivityListener handler(s). **************************************/

	@Override
	public void handlePeerChoked(SharingPeer peer) { /* Do nothing */ }

	@Override
	public void handlePeerReady(SharingPeer peer) { /* Do nothing */ }

	@Override
	public void handlePieceAvailability(SharingPeer peer,
			Piece piece) { /* Do nothing */ }

	@Override
	public void handleBitfieldAvailability(SharingPeer peer,
			BitSet availablePieces) { /* Do nothing */ }

	@Override
	public void handlePieceSent(SharingPeer peer,
			Piece piece) { /* Do nothing */ }
	
}
