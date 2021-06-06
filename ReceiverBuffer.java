import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

public class ReceiverBuffer {
	// the continuous part of the buffer
	private byte[] continuous;
	// the separate chunks of the buffer
	private ArrayList<byte[]> chunks;
	// the corresponding start and end bytes of each chunk
	private ArrayList<int[]> chunksPointers;
	
	// buffer size
	private int buffSize;
	
	// moving pointers
	private int lastByteRead;
	private int nextByteExpected;
	
	// counter for number of out-of-sequence packets discarded
	int numDiscardPacket;
	
	public ReceiverBuffer(int bufferSize) {
		lastByteRead = 0;
		nextByteExpected = 1;
		this.buffSize = bufferSize;
		this.chunks = new ArrayList<byte[]>();
		this.chunksPointers = new ArrayList<int[]>();
		this.numDiscardPacket = 0;
	}
	
	/**
	 * Write to the buffer some data with start byte and end byte; coalesce the
	 * separated chunks if needed. Return true if successful, false otherwise
	 * */
	public int writeToBuffer(byte[] data, int startByte, int endByte) {
		if (data == null) return nextByteExpected;
//		System.out.printf("Write to buffer byte (%d: %d).\n", startByte, endByte);
		// the length of data
		int dataLen = data.length;
		
		// check if there is enough space to store the new data
		if (endByte > lastByteRead + buffSize) { 
			numDiscardPacket++;
			return nextByteExpected;
		}
		
		if (startByte < nextByteExpected) return nextByteExpected;
		
		// incorrect start byte or end byte
		if (endByte - startByte + 1 != dataLen) return nextByteExpected;
		
		for (int[] pointer: chunksPointers)
			if (pointer[0] == startByte && pointer[1] == endByte) return nextByteExpected;
		
		// if the data is in order with the continuous buffer
		if (startByte == nextByteExpected) {
			nextByteExpected = endByte + 1;
			int cumlen = dataLen; // cumulative length for the new continuous buffer
			int idx = -1; // the index of the chunk to be coalesced into the continuous buffer
			
			for (int i = 0; i < chunksPointers.size(); i++) {
				int[] pointers = chunksPointers.get(i);
				if (pointers[0] == endByte + 1) {
					// found a chunk to coalesce into the continuous buffer
					cumlen += pointers[1] - pointers[0] + 1;
					idx = i;
					nextByteExpected = pointers[1] + 1;
					break;
				}
			}
			
			// create the new continuous buffer
			if (continuous == null) {
				ByteBuffer tempBuff = ByteBuffer.allocate(cumlen);
				tempBuff.put(data);
				if (idx != -1) {
					tempBuff.put(chunks.get(idx));
					chunks.remove(idx);
					chunksPointers.remove(idx);
				}
				continuous = tempBuff.array();
			} else {
				cumlen += continuous.length;
				ByteBuffer tempBuff = ByteBuffer.allocate(cumlen);
				tempBuff.put(continuous);
				tempBuff.put(data);
				if (idx != -1) {
					tempBuff.put(chunks.get(idx));
					chunks.remove(idx);
					chunksPointers.remove(idx);
				}
				continuous = tempBuff.array();
			}			
		} 
		// if the new data is out of order
		else {
			// insert the data into separated chunks with right order
			int i;
			for (i = 0; i < chunksPointers.size(); i++) {
				int[] curPointers = chunksPointers.get(i);
				// coalesce with both side is needed
				if (endByte == curPointers[0] - 1 && i >= 1 
						&& startByte == chunksPointers.get(i-1)[1] + 1) {
					int[] prevPointers = chunksPointers.get(i-1);
					ByteBuffer tempBuff = ByteBuffer.allocate((prevPointers[1] - prevPointers[0] + 1) 
											                     + (endByte - startByte + 1) 
											                     + (curPointers[1] - curPointers[0] + 1));
					tempBuff.put(chunks.get(i-1));
					tempBuff.put(data);
					tempBuff.put(chunks.get(i));
					
					int[] newPointers = new int[] {prevPointers[0], curPointers[1]};
					
					chunksPointers.add(i, newPointers);
					chunksPointers.remove(i-1);
					chunksPointers.remove(i+1);
					
					chunks.add(i, tempBuff.array());
					chunks.remove(i-1);
					chunks.remove(i+1);
					break;
				}
				// coalesce with only current chunk is needed
				else if (endByte == curPointers[0] - 1) {
					ByteBuffer tempBuff = ByteBuffer.allocate((endByte - startByte + 1) 
							+ (curPointers[1] - curPointers[0] + 1));
					tempBuff.put(data);
					tempBuff.put(chunks.get(i));
					
					int[] newPointers = new int[] {startByte, curPointers[1]};
					
					chunksPointers.add(i, newPointers);
					chunksPointers.remove(i+1);
					
					chunks.add(i, tempBuff.array());
					chunks.remove(i+1);
					break;
				}
				// coalesce with only previous chunk is needed
				else if (i >= 1 && startByte == chunksPointers.get(i-1)[1] + 1) {
					int[] prevPointers = chunksPointers.get(i-1);
					
					ByteBuffer tempBuff = ByteBuffer.allocate((prevPointers[1] - prevPointers[0] + 1) 
							+ (endByte - startByte + 1));
					tempBuff.put(chunks.get(i-1));
					tempBuff.put(data);
					
					int[] newPointers = new int[] {prevPointers[0], endByte};
					
					chunksPointers.add(i, newPointers);
					chunksPointers.remove(i-1);
					
					chunks.add(i, tempBuff.array());
					chunks.remove(i-1);
					break;
				}
				// no coalesce is needed
				else if (endByte < curPointers[0] - 1){
					chunksPointers.add(i, new int[] {startByte, endByte});
					chunks.add(i, data);
					break;
				}
			}
			
			if (i == chunksPointers.size()) {
				// check if coalesce with the previous chunk is needed
				if (i >= 1 && startByte == chunksPointers.get(i-1)[1] + 1) {
					int[] prevPointers = chunksPointers.get(i-1);
					
					ByteBuffer tempBuff = ByteBuffer.allocate((prevPointers[1] - prevPointers[0] + 1) 
							+ (endByte - startByte + 1));
					tempBuff.put(chunks.get(i-1));
					tempBuff.put(data);
					
					int[] newPointers = new int[] {prevPointers[0], endByte};
					
					chunksPointers.add(i, newPointers);
					chunksPointers.remove(i-1);
					
					chunks.add(i, tempBuff.array());
					chunks.remove(i-1);
				} 
				// no coalesce is needed, append the data
				else {
					chunksPointers.add(new int[] {startByte, endByte});
					chunks.add(data);
				}
			}
		}
//		printBuffer();
		return nextByteExpected;
	}
	
	/**
	 * Retrieve all the continuous data
	 * */
	public byte[] readFromBuffer() {
		if (lastByteRead == nextByteExpected - 1) return null;
//		System.out.printf("Read from buffer byte (%d: %d).\n", lastByteRead+1, nextByteExpected-1);
		byte[] rtBuff = Arrays.copyOf(continuous, nextByteExpected - lastByteRead - 1);
		lastByteRead = nextByteExpected - 1;
		continuous = null;
//		printBuffer();
		return rtBuff;
	}
	
	/**
	 * Checks if the buffer can hold data with passed-in length
	 * */
	public boolean isFull(int len) {
		return nextByteExpected - lastByteRead - 1 + len > buffSize;
	}
	
	/**
	 * Gets the number of out-of-sequence packets discarded
	 * */
	public int getNumDscPckt() {
		return this.numDiscardPacket;
	}
	
	private void printBuffer() {
		String pos = "";
		for (int b = lastByteRead + 1; b < nextByteExpected; b++) pos += b + space(b);
		if (chunksPointers.size() == 0) {
			for (int b = nextByteExpected; b < lastByteRead + 1 + buffSize; b++) pos += space(-1);
		} else {
			int curpos = nextByteExpected;
			for (int[] pointer: chunksPointers) {
				for (int b = curpos; b < pointer[0]; b++) pos += space(-1);
				for (int b = pointer[0]; b < pointer[1] + 1; b++) pos += b + space(b);
				curpos = pointer[1] + 1;
			}
			for (int b = curpos; b < lastByteRead + 1 + buffSize; b++) pos += space(-1);
		}
		System.out.println(pos);
		for (int i = 0; i < buffSize; i++) System.out.print(i + space(i));
		System.out.println("");
	}
	
	private String space(int b) {
		if (b < 0) return "    ";
		else if (b < 10) return "   ";
		else if (b < 100) return "  ";
		else if (b < 1000) return " ";
		else return "";
	}

}
