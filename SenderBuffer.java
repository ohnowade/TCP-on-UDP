import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class SenderBuffer {
	// maximum number of retransmission
	private static final int MNR = 16;
	
	// the buffer that stores information of all sent segments
	ArrayList<SentSegInfo> sentSegs;
	
	// buffer size
	int buffSize;
	
	// counter for number of duplicate acks
	int numDupAck;
	
	/**
	 * public constructor that creates the buffer with specified size
	 * */ 
	public SenderBuffer(int bufferSize) {
		this.buffSize = bufferSize;
		this.sentSegs = new ArrayList<SentSegInfo>();
		this.numDupAck = 0;
	}
	
	/**
	 * inserts a new sent segment into the buffer
	 * */
	public void insertSeg(int seq, int len, long time, byte[] data) {
		sentSegs.add(new SentSegInfo(seq, len, time, data));
//		printBuffer();
	}
	
	/**
	 * checks if the buffer can hold a new segment with specified length
	 * */
	public boolean isFull(int curLen) {
		int totalSize = 0;
		for (SentSegInfo seg: sentSegs) totalSize += seg.length;
		return totalSize + curLen > buffSize;
	}
	
	/**
	 * checks if there is any unacked segments
	 * */
	public boolean isEmpty() {
		return sentSegs.size() == 0;
	}
	
	/**
	 * updates duplicate ACKs counts, and removes acked segments from buffer;
	 * returns a segment that has received 3 duplicate acks if there is one
	 * */
	public ArrayList<SentSegInfo> handleACK(int ack) {
		// the segments that have been acked
		ArrayList<SentSegInfo> ackedSegs = new ArrayList<SentSegInfo>();
		// the segments that have received 3 duplicate acks
		ArrayList<SentSegInfo> timeOutSegs = new ArrayList<SentSegInfo>();
		
		for (SentSegInfo seg: sentSegs) {
			if (ack >= seg.sequenceNum + seg.length) {
				ackedSegs.add(seg);
			} else if (ack == seg.sequenceNum) {
				// updates duplicate ACKs
				seg.dupAck++;
				
				if (seg.dupAck > 1) numDupAck++;
				
				if (seg.dupAck == 4) {
					// retransmit all unacked segments
//					for (SentSegInfo unAckedSeg: sentSegs) {
//						if (unAckedSeg.sequenceNum >= seg.sequenceNum) {
//							timeOutSegs.add(unAckedSeg);
//							unAckedSeg.dupAck = 0;
//							unAckedSeg.rtCnt++;
//							if (unAckedSeg.rtCnt > MNR) {
//								System.err.printf("Segment (%d: %d) reaches maximum retransmission time\n"
//										, unAckedSeg.sequenceNum, unAckedSeg.sequenceNum+seg.length-1);
//								return null;
//							}
//						}
//					}
					timeOutSegs.add(seg);
					seg.dupAck = 0;
				}
			}
		}
		// remove acked segments
		sentSegs.removeAll(ackedSegs);
//		printBuffer();
		return timeOutSegs;
	}
	
	/**
	 * checks if any of the sent segments times out. Returns all segments that 
	 * need to be retransmitted
	 * */
	public ArrayList<SentSegInfo> checkTimeout(double timeout) {
		ArrayList<SentSegInfo> timeoutSegs = new ArrayList<SentSegInfo>();
		
		for (SentSegInfo seg: sentSegs) {
			long elapsed = System.nanoTime() - seg.timeStamp;
			if (elapsed >= timeout) {
				timeoutSegs.add(seg);
				seg.rtCnt++;
				if (seg.rtCnt > MNR) {
					System.err.printf("Segment (%d: %d) reaches maximum retransmission time\n"
							, seg.sequenceNum, seg.sequenceNum+seg.length-1);
					return null;
				}
				break;
			}
		}
		
		return timeoutSegs;
	}
	
	/**
	 * update the timestamp of a sent segment with sequence number seq
	 * */
	public void updateTimeStamp(long timeStamp, int seq) {
		for (SentSegInfo seg: sentSegs) {
			if (seg.sequenceNum == seq)
				seg.timeStamp = timeStamp;
			break;
		}
	}
	
	/**
	 * get the total number of duplicate ACKs
	 * */
	public int getDupAcks() {
		return this.numDupAck;
	}
	
	public void printBuffer() {
		String out = "Sender Buffer: ";
		for (SentSegInfo seg: sentSegs) {
			out += String.format("(%d: %d) ", seg.sequenceNum, seg.sequenceNum + seg.length - 1);
		}
		System.out.println(out);
	}
}
