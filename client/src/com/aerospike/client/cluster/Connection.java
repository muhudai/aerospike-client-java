/*
 * Copyright 2012-2016 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.cluster;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.security.cert.X509Certificate;

import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.security.auth.x500.X500Principal;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Log;
import com.aerospike.client.policy.TlsPolicy;
import com.aerospike.client.util.Util;

/**
 * Socket connection wrapper.
 */
public final class Connection implements Closeable {
	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final long maxSocketIdleMillis;
	private volatile long lastUsed;
	
	public Connection(InetSocketAddress address, int timeoutMillis) throws AerospikeException.Connection {
		this(null, null, address, timeoutMillis, 55000);
	}

	public Connection(TlsPolicy policy, String tlsName, InetSocketAddress address, int timeoutMillis, int maxSocketIdleMillis) throws AerospikeException.Connection {
		this.maxSocketIdleMillis = maxSocketIdleMillis;

		try {
			if (policy == null) {
				socket = new Socket();
			
				try {
					socket.setTcpNoDelay(true);
					
					if (timeoutMillis > 0) {
						socket.setSoTimeout(timeoutMillis);
					}
					else {				
						// Do not wait indefinitely on connection if no timeout is specified.
						// Retry functionality will attempt to reconnect later.
						timeoutMillis = 2000;
					}
					socket.connect(address, timeoutMillis);
					in = socket.getInputStream();
					out = socket.getOutputStream();
					lastUsed = System.currentTimeMillis();
				}
				catch (Exception e) {
					// socket.close() will close input/output streams according to doc.
					socket.close();
					throw e;
				}
			}
			else {				
	            SSLSocketFactory sslsocketfactory = (SSLSocketFactory)SSLSocketFactory.getDefault();
	            SSLSocket sslSocket = (SSLSocket)sslsocketfactory.createSocket(address.getAddress(), address.getPort());
				socket = sslSocket;
				
				try {
					socket.setTcpNoDelay(true);
					
					if (timeoutMillis > 0) {
						socket.setSoTimeout(timeoutMillis);
					}
					else {				
						// Do not wait indefinitely on connection if no timeout is specified.
						// Retry functionality will attempt to reconnect later.
						timeoutMillis = 2000;
					}
				
					if (policy.protocols != null) {
						sslSocket.setEnabledProtocols(policy.protocols);
					}
					
					if (policy.ciphers != null) {
						sslSocket.setEnabledCipherSuites(policy.ciphers);
					}
					
					if (! policy.encryptOnly) {
						validateServerCertificateName(sslSocket, tlsName);
					}
					
					in = socket.getInputStream();
					out = socket.getOutputStream();
					lastUsed = System.currentTimeMillis();
				}
				catch (Exception e) {
					// socket.close() will close input/output streams according to doc.
					socket.close();
					throw e;
				}
			}
		}
		catch (AerospikeException.Connection ae) {
			throw ae;
		}
		catch (Exception e) {
			throw new AerospikeException.Connection(e);
		}
	}
	
	private static void validateServerCertificateName(SSLSocket sslSocket, String tlsName) throws Exception {		
		if (tlsName == null) {
			throw new AerospikeException.Connection("Invalid server TLS name: null");							
		}
		
		sslSocket.setUseClientMode(true);
		sslSocket.startHandshake();
		
		X509Certificate cert = (X509Certificate)sslSocket.getSession().getPeerCertificates()[0];
		String subject = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
		LdapName ldapName = new LdapName(subject);
		
		for (Rdn rdn : ldapName.getRdns()) {
			Attribute cn = rdn.toAttributes().get("CN");
		    
			if (cn != null) {
				String certName = (String)cn.get();
				if (! certName.equals(tlsName)) {
					throw new AerospikeException.Connection("Invalid server TLS name: " + certName);
				}
				return;
			}
		}
		throw new AerospikeException.Connection("Invalid server TLS name: null");
	}
	
	public void write(byte[] buffer, int length) throws IOException {
		// Never write more than 8 KB at a time.  Apparently, the jni socket write does an extra 
		// malloc and free if buffer size > 8 KB.
		final int max = length;
		int pos = 0;
		int len;
		
		while (pos < max) {
			len = max - pos;
			
			if (len > 8192)
				len = 8192;
			
			out.write(buffer, pos, len);
			pos += len;
		}
	}
	
	public void readFully(byte[] buffer, int length) throws IOException {
		int pos = 0;
	
		while (pos < length) {
			int count = in.read(buffer, pos, length - pos);
		    
			if (count < 0)
				throw new EOFException();
			
			pos += count;
		}
	}

	/**
	 * Is socket connected and used within specified limits.
	 */
	public boolean isValid() {
		return (System.currentTimeMillis() - lastUsed) <= maxSocketIdleMillis;
	}
	
	/**
	 * Is socket closed from client perspective only.
	 */
	public boolean isClosed() {
		return lastUsed == 0;
	}

	public void setTimeout(int timeout) throws SocketException {
		socket.setSoTimeout(timeout);
	}
	
	public InputStream getInputStream() {
		return in;
	}
			
	public void updateLastUsed() {
		lastUsed = System.currentTimeMillis();
	}
	
	/**
	 * Close socket and associated streams.
	 */
	public void close() {
		lastUsed = 0;
		
		try {
			in.close();
			out.close();			
			socket.close();
		}
		catch (Exception e) {
			if (Log.debugEnabled()) {
				Log.debug("Error closing socket: " + Util.getErrorMessage(e));
			}
		}
	}
}
