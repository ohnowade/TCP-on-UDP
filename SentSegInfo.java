
public class SentSegInfo {
	// the sequence number of the segment
	public int sequenceNum;
	// the length of data
	public int length;
	// the time stamp at which the segment was sent
	public long timeStamp;
	// the actual data
	public byte[] data;
	// the number of duplicate acks received for this segment
	public int dupAck;
	// retransmission times
	public int rtCnt;
	
	public SentSegInfo(int seq, int len, long time, byte[] data) {
		this.sequenceNum = seq;
		this.length = len;
		this.timeStamp = time;
		this.data = data;
		this.dupAck = 0;
		this.rtCnt = 0;
	}
}
