package com.turn.ttorrent.client.nio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.turn.ttorrent.client.CommunicationListener;
import com.turn.ttorrent.common.Torrent;

public class PeerCommunicationManager extends Thread {
	
	private static final Logger logger =
			LoggerFactory.getLogger(PeerCommunicationManager.class);
	
	public static final int PORT_RANGE_START = 6881;
	public static final int PORT_RANGE_END = 6889;
	private static int DEFAULT_BUFFER_SIZE = 512;
	
	Selector selector; // Main selector that handles all requests
	ServerSocketChannel serverSocketChannel;
	InetSocketAddress address;
	List<ChangeRequest> changeRequests = new LinkedList<ChangeRequest>();
	List<CommunicationListener> listeners = new ArrayList<CommunicationListener>();
	
	private Map pendingData = new HashMap();
	private final Map<SelectionKey, ByteBuffer> readBuffers = new HashMap<SelectionKey, ByteBuffer>();
	
	public String id;
	
	public PeerCommunicationManager(InetAddress address, String id)
			throws IOException {
		this.id = id;
		
		// Bind to the first available port in the range
		// [PORT_RANGE_START; PORT_RANGE_END].
		for (int port = PORT_RANGE_START;
				port <= PORT_RANGE_END;
				port++) {
			InetSocketAddress tryAddress =
				new InetSocketAddress(address, port);

			try {
				this.selector = SelectorProvider.provider().openSelector();
				this.serverSocketChannel = ServerSocketChannel.open();
				this.serverSocketChannel.configureBlocking(false);
				this.serverSocketChannel.socket().bind(tryAddress);
				this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
				
				this.address = tryAddress;
				break;
			} catch (IOException ioe) {
				// Ignore, try next port
				logger.warn("Could not bind to {} !", tryAddress);
			}
		}
	}
	
	public static String socketRepr(Socket s) {
		return new StringBuilder(s.getInetAddress().getHostName())
			.append(":").append(s.getPort()).toString();
	}
	
	public void run() {
		while(true) {
			try {
				// Look for pending requests to change a key or socket
				synchronized (this.changeRequests) {
					Iterator changes = this.changeRequests.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = (ChangeRequest) changes.next();
						switch (change.type) {
						case ChangeRequest.CHANGEOPS:
							SelectionKey key = change.socket.keyFor(this.selector);
							key.interestOps(change.ops);
							break;
						case ChangeRequest.REGISTER:
							change.socket.register(this.selector, change.ops, change.additionalData);
							break;
						}
					}
					
					this.changeRequests.clear();
				}
				
				this.selector.select(); // Blocking select call
				
				// We found keys ready for selection
				Iterator selectedKeys = this.selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey key = (SelectionKey) selectedKeys.next();
					selectedKeys.remove();
					
					if (!key.isValid()) {
						continue;
					}
					
					// Determine what to do with the socket channel
					if (key.isConnectable()) {
						this.finishConnection(key);
					} else if (key.isAcceptable()) {
						this.accept(key);
					} else if (key.isReadable()) {
						this.read(key);
					} else if (key.isWritable()) {
						this.write(key);
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void write(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		
		synchronized (this.pendingData) {
			List queue= (List) this.pendingData.get(socketChannel);
			
			while (!queue.isEmpty()) {
				byte[] data = (byte[]) queue.get(0);
				short len = (short) data.length;
				
				// Create 2-bytes to prepend the message with indicating the length
				byte[] lengthBytes = new TwoByteMessageLength().lengthToBytes(len);
				
				// Allocate a byte buffer of the message length, plus the length of the length prefix
				ByteBuffer buf = ByteBuffer.allocate(len+lengthBytes.length);
				buf.position(0);
				buf.put(lengthBytes);
				buf.put(data);
				buf.flip();
				
				if (buf != null) {
					int bytesWritten;
					SocketChannel channel = (SocketChannel) key.channel();
					synchronized (channel) {
						bytesWritten = channel.write(buf);
					}
					
					if (bytesWritten == -1) {
						System.out.println("No bytes written");
					}
					
				}
				
				if (buf.remaining() > 0) {
					break;
				}
				
				queue.remove(0);
			}
			
			if (queue.isEmpty()) {
				// Set this key back to read after we're done writing
				key.interestOps(SelectionKey.OP_READ);
			}
		}
	}

	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		
		ByteBuffer readBuffer = readBuffers.get(key); 
    	if (readBuffer==null) {
    		// Create a read buffer for this key at the default buffer size
    		readBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE); 
    		readBuffers.put(key, readBuffer); 
    	}
    	
		int numRead;
		try {
			numRead = socketChannel.read(readBuffer);
		} catch (IOException e) {
			key.cancel();
			socketChannel.close();
			return;
		}
		
		if (numRead == -1) {
			key.channel().close();
			key.cancel();
			return;
		}
		
		readBuffer.flip();
		List<ByteBuffer> result = new ArrayList<ByteBuffer>();
		
		// We read data from the socket, now see if we can parse one or more useful messages out of it
		ByteBuffer msg = readMessage(key, readBuffer);
		while (msg != null) {
			result.add(msg);
			msg = readMessage(key, readBuffer);
		}
		
		// Call back to the listeners with the resulting messages
		fireNewDataListeners(socketChannel, result);
	}
	
	private ByteBuffer readMessage(SelectionKey key, ByteBuffer readBuffer) {
		int bytesToRead;
		
		// The length of the expected length prefix
		TwoByteMessageLength messageLength = new TwoByteMessageLength();
		
		// Need at least enough to read the message length
		if (readBuffer.remaining() >= messageLength.byteLength()) {
			byte[] lengthBytes = new byte[messageLength.byteLength()];
			readBuffer.get(lengthBytes);
			bytesToRead = (int)messageLength.bytesToLength(lengthBytes); // This is the message size
			if (readBuffer.limit() - readBuffer.position() < bytesToRead) {
				// Not enough data - prepare for writing again
				if (readBuffer.limit() == readBuffer.capacity()) {
					// Message may be longer than buffer => resize buffer to message size
					int oldCapacity = readBuffer.capacity();
					ByteBuffer tmp = ByteBuffer.allocate(bytesToRead+messageLength.byteLength());
					readBuffer.position(0);
					tmp.put(readBuffer);
					readBuffer = tmp;
					readBuffer.position(oldCapacity);
					readBuffer.limit(readBuffer.capacity());
					readBuffers.put(key, readBuffer);
					return null;
				} else {
					// Reset for writing
					readBuffer.position(readBuffer.limit());
					readBuffer.limit(readBuffer.capacity());
					return null;
				}
			}
		} else {
			// Not enough data - prepare for writing again
			readBuffer.position(readBuffer.limit());
			readBuffer.limit(readBuffer.capacity());
			return null;
		}
		
		byte[] resultMessage = new byte[bytesToRead];
		readBuffer.get(resultMessage, 0, bytesToRead);
		
		// Remove read message from buffer
		int remaining = readBuffer.remaining();
		readBuffer.limit(readBuffer.capacity());
		readBuffer.compact();
		readBuffer.position(0);
		readBuffer.limit(remaining);
		return ByteBuffer.wrap(resultMessage);
	}

	private void accept(SelectionKey key) throws IOException {
		// Accept a new connection - set up the resulting socket channel for read
		ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
		SocketChannel socketChannel = serverSocketChannel.accept();
		socketChannel.configureBlocking(false);
		socketChannel.register(this.selector, SelectionKey.OP_READ);
	}

	private void finishConnection(SelectionKey key) {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		  
	    // Finish the connection. If the connection operation failed
	    // this will raise an IOException.
	    try {
	      socketChannel.finishConnect();
	    } catch (IOException e) {
	      // Cancel the channel's registration with our selector
	      key.cancel();
	      return;
	    }
	    
	    // Let our listeners know we've completed a connection
	    fireNewConnectionListeners(socketChannel, (String) key.attachment());
	}
	
	private void fireNewConnectionListeners(SocketChannel socketChannel, String hexInfoHash) {
		for (CommunicationListener listener : this.listeners) {
			listener.handleNewConnection(socketChannel, hexInfoHash);
		}
	}
	
	private void fireNewDataListeners(SocketChannel socketChannel, List<ByteBuffer> data) {
		for (CommunicationListener listener : this.listeners) {
			listener.handleNewData(socketChannel, data);
		}
	}
	
	public void register(CommunicationListener listener) {
		this.listeners.add(listener);
	}
	
	public InetSocketAddress getAddress() {
		return this.address;
	}

	public SocketChannel connect(InetAddress address, int port, byte[] infoHash) throws IOException {
		logger.info("Initiating connection with {}", address.toString() + ":" + port);
		SocketChannel socketChannel = SocketChannel.open();
		socketChannel.configureBlocking(false);
		socketChannel.connect(new InetSocketAddress(address, port));
		
		// We can't directly change the key, so set up a change request
		synchronized(this.changeRequests) {
			this.changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT, Torrent.byteArrayToHexString(infoHash)));
		}
		
		this.selector.wakeup();
		
		return socketChannel;
	}
	
	public void send(SocketChannel socketChannel, byte[] data) {
		// We can't directly set a socket to write, so set up a change request
		synchronized(this.changeRequests) {
			this.changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
			
			// Put the data to be written in the pending data list
			synchronized (this.pendingData) {
				List queue = (List) this.pendingData.get(socketChannel);
				if (queue == null) {
					queue = new ArrayList();
					this.pendingData.put(socketChannel, queue);
				}
				queue.add(data);
			}
		}
		
		this.selector.wakeup();
	}
}
