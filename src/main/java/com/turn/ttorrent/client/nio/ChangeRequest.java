package com.turn.ttorrent.client.nio;

import java.nio.channels.SocketChannel;

public class ChangeRequest {
	public static final int REGISTER = 1;
	public static final int CHANGEOPS = 2;
	public static final int ATTACH = 3;
	
	public SocketChannel socket;
	public int type;
	public int ops;
	public Object additionalData;
	
	public ChangeRequest(SocketChannel socket, int type, int ops) {
		this(socket, type, ops, null);
	}
	
	public ChangeRequest(SocketChannel socket, int type, int ops, Object additionalData) {
		this.socket = socket;
		this.type = type;
		this.ops = ops;
		this.additionalData = additionalData;
	}
}
