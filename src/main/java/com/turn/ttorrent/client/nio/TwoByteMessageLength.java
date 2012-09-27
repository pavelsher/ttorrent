package com.turn.ttorrent.client.nio;

public class TwoByteMessageLength {

	private final int NUM_BYTES = 2;
	private final long MAX_LENGTH = 65535;
	
	/**
	 * @see com.cordinc.faraway.server.network.MessageLength#byteLength()
	 */
	public int byteLength() {
		return NUM_BYTES;
	}
	
	/**
	 * @see com.cordinc.faraway.server.network.MessageLength#maxLength()
	 */
	public long maxLength() {
		return MAX_LENGTH;
	}
	
	/**
	 * @see com.cordinc.faraway.server.network.MessageLength#bytesToLength(byte[])
	 */
	public long bytesToLength(byte[] bytes) {
		if (bytes.length!=NUM_BYTES) {
			throw new IllegalStateException("Wrong number of bytes, must be "+NUM_BYTES);
		}
		return ((long)(bytes[0] & 0xff) << 8) + (long)(bytes[1] & 0xff);
	}
	
	/**
	 * @see com.cordinc.faraway.server.network.MessageLength#lengthToBytes(long)
	 */
	public byte[] lengthToBytes(long len) {
		if (len<0 || len>MAX_LENGTH) {
			throw new IllegalStateException("Illegal size: less than 0 or greater than "+MAX_LENGTH);
		}
		return new byte[] {(byte)((len >>> 8) & 0xff), (byte)(len & 0xff)};
	}
	
}
