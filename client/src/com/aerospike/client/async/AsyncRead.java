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
package com.aerospike.client.async;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.cluster.Partition;
import com.aerospike.client.command.Buffer;
import com.aerospike.client.command.Command;
import com.aerospike.client.listener.RecordListener;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.util.ThreadLocalData;

public class AsyncRead extends AsyncSingleCommand {
	private final RecordListener listener;
	protected final Key key;
	protected final Partition partition;
	private final String[] binNames;
	protected Record record;
	
	public AsyncRead(AsyncCluster cluster, Policy policy, RecordListener listener, Key key, String[] binNames) {
		super(cluster, policy);
		this.listener = listener;
		this.key = key;
		this.partition = new Partition(key);
		this.binNames = binNames;
	}

	public AsyncRead(AsyncRead other) {
		super(other);
		this.listener = other.listener;
		this.key = other.key;
		this.partition = other.partition;
		this.binNames = other.binNames;
	}

	@Override
	protected AsyncCommand cloneCommand() {
		return new AsyncRead(this);
	}

	@Override
	protected void writeBuffer() {
		setRead(policy, key, binNames);
	}

	@Override
	protected Node getNode() {
		return getReadNode(cluster, partition, policy.replica);
	}

	@Override
	protected final void parseResult(ByteBuffer byteBuffer) {
		dataBuffer = ThreadLocalData.getBuffer();
		
		if (receiveSize > dataBuffer.length) {
			dataBuffer = ThreadLocalData.resizeBuffer(receiveSize);
		}
		// Copy entire message to dataBuffer.
		byteBuffer.position(0);
		byteBuffer.get(dataBuffer, 0, receiveSize);
			
		int resultCode = dataBuffer[5] & 0xFF;
		int generation = Buffer.bytesToInt(dataBuffer, 6);
		int expiration = Buffer.bytesToInt(dataBuffer, 10);
		int fieldCount = Buffer.bytesToShort(dataBuffer, 18);
		int opCount = Buffer.bytesToShort(dataBuffer, 20);
		dataOffset = Command.MSG_REMAINING_HEADER_SIZE;
		        
        if (resultCode == 0) {
            if (opCount == 0) {
            	// Bin data was not returned.
            	record = new Record(null, generation, expiration);
            }
            else {
            	record = parseRecord(opCount, fieldCount, generation, expiration);
            }
        }
        else {
        	if (resultCode == ResultCode.KEY_NOT_FOUND_ERROR) {
        		record = null;
        	}
        	else {
        		throw new AerospikeException(resultCode);
        	}
        }
	}
		
	private final Record parseRecord(
		int opCount, 
		int fieldCount, 
		int generation,
		int expiration
	) throws AerospikeException {
		// There can be fields in the response (setname etc).
		// But for now, ignore them. Expose them to the API if needed in the future.
		if (fieldCount > 0) {
			// Just skip over all the fields
			for (int i = 0; i < fieldCount; i++) {
				int fieldSize = Buffer.bytesToInt(dataBuffer, dataOffset);
				dataOffset += 4 + fieldSize;
			}
		}
	
		Map<String,Object> bins = null;
		
		for (int i = 0 ; i < opCount; i++) {
			int opSize = Buffer.bytesToInt(dataBuffer, dataOffset);
			byte particleType = dataBuffer[dataOffset+5];
			byte nameSize = dataBuffer[dataOffset+7];
			String name = Buffer.utf8ToString(dataBuffer, dataOffset+8, nameSize);
			dataOffset += 4 + 4 + nameSize;
	
			int particleBytesSize = (int) (opSize - (4 + nameSize));
	        Object value = null;
	        
			value = Buffer.bytesToParticle(particleType, dataBuffer, dataOffset, particleBytesSize);
			dataOffset += particleBytesSize;
	
			if (bins == null) {
				bins = new HashMap<String,Object>();
			}
			addBin(bins, name, value);
	    }	
	    return new Record(bins, generation, expiration);
	}

	protected void addBin(Map<String,Object> bins, String name, Object value) {
		bins.put(name, value);	
	}

	@Override
	protected void onSuccess() {
		if (listener != null) {
			listener.onSuccess(key, record);
		}
	}

	@Override
	protected void onFailure(AerospikeException e) {
		if (listener != null) {
			listener.onFailure(e);
		}
	}
}
