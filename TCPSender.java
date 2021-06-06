import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TCPSender {
	// alpha used in computing estimated RTT
	private static final double alpha = 0.875;
	// beta used in computing deviation
	private static final double beta = 0.75;
	// TCP header length
	private static final int HEADER_LEN = 24;
	// maximum number of retransmission
	private static final int MNR = 16;
	
	// the IP address of the receiver
	private InetAddress remoteIP;
	
	// the port at which the receiver is running
	private int remotePort;
	
	// maximum transmission unit in bytes
	private int mtu;

	// the unreliable UDP sender socket
	private DatagramSocket socket;
	
	// the input stream that reads in the specified file
	private InputStream in;
	
	// the sender buffer
	private SenderBuffer sendBuff;
	
	// pointer
	private int lastByteSent;
	
	// current Acknowledgement number for data received
	private int ack;
	
	// timeout computation
	// estimated RTT
	private double ERTT;
	// estimated deviation
	private double EDEV;
	// timeout
	private double TO;
	
	// statistics counters
	// amount of data transferred
	private int sizeDataSent;
	// number of packets sent
	private int numPacketSent;
	// number of packets received
	private int numPacketReceived;
	// number of packets discarded due to incorrect checksum
	private int numIcrtChecksum;
	// number of retransmissions
	private int numRetransmission;
	// number of duplicate acknowledgement
	private int numDupAcks;
	
	
	// concurrency tools
	private final Lock lock = new ReentrantLock();
	private final Condition empty = lock.newCondition();
	private final Condition getAck = lock.newCondition();
	private boolean done = false;
	
	// Runnable through which thread keeps sending data from the buffer
	private Runnable sendSegment = () -> {
		while (true) {
			// read from file
			byte[] data = new byte[mtu];
			int count;
			try {
				count = in.read(data);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
			// check if reaches the end of the file
			if (count < 0) {
//				System.out.println("Reached end of the file!");
				lock.lock();
				done = true;
				lock.unlock();
				return;
			} else if (count < mtu) {
				data = Arrays.copyOfRange(data, 0, count);
			}
			
			lock.lock();
			
			// wait for segments to be acked if the buffer is full
			while (sendBuff.isFull(data.length)) {
				try {
//					System.out.println("Sender buffer full!");
					empty.await();
				} catch (InterruptedException e) {
					lock.unlock();
					e.printStackTrace();
					return;
				}
			}
			
			// send the TCP segment
			long sentTime;
			try {
				sentTime = sendTCPSeg(data, lastByteSent+1, false, true, false);
			} catch (IOException e) {
				lock.unlock();
				e.printStackTrace();
				return;
			}
			// add the sent segment to buffer
			sendBuff.insertSeg(lastByteSent+1, data.length, sentTime, data);
			// update pointer
			lastByteSent += data.length;
			// update counter
			sizeDataSent += data.length;
			
			lock.unlock();
		}
	};
	
	// Runnnable through which thread keeps track of SYN, FIN, and ACK from the receiver
	private Runnable handleAck = () -> {
		while(true) {
//			System.out.printf("Current Timeout: %f\n", this.TO/1000000);
			try {
				socket.setSoTimeout((int)(this.TO/1000000));
			} catch (SocketException e1) {
				e1.printStackTrace();
				return;
			}
			
			lock.lock();
			
//			getAck.signal();
			
			// check if transmission is completed
			if (sendBuff.isEmpty() && done) {
				numDupAcks = sendBuff.getDupAcks();
				getAck.signal();
				lock.unlock();
				return;
			} 
			
			lock.unlock();
			
			// receiving ACKs from the receiver
			byte[] ackBuf = new byte[HEADER_LEN];
			DatagramPacket ackPacket = new DatagramPacket(ackBuf, HEADER_LEN);
			try {
				socket.receive(ackPacket);
				numPacketReceived++;
			} 
			catch (SocketTimeoutException e) {
				// timeout occurs, check for segments that need to be re-sent
//				System.out.println("Sender receive time out");
				lock.lock();
				TO = 2*TO;
				getAck.signal();
				lock.unlock();
				continue;
			} 
			catch (IOException e) {
				e.printStackTrace();
				return;
			}
//			printSegInfo(ackPacket.getData());
			
			if (computeCheckSum(ackPacket.getData()) != getCheckSum(ackPacket.getData())) {
				System.out.println("Wrong checksum!");
				numIcrtChecksum++;
				continue;
			}
			
			// handle SYN+ACK from the receiver
			if (isSYN(ackPacket.getData()) && hasACK(ackPacket.getData())) {
				try {
					ack = getSeq(ackPacket.getData()) + 1;
					sendTCPSeg(null, 0, false, true, false);
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}
				continue;
			}
			
			// retrieve the ack number of the received segment
			int curAck = getAck(ackPacket.getData());
			// update timeout
			computeTimeout(getTime(ackPacket.getData()));
			
			lock.lock();
			
			ArrayList<SentSegInfo> timeoutSegs = sendBuff.handleACK(curAck);
			// check if any of the segments have been retransmitted for more than 16 times
			if (timeoutSegs == null) {
				System.err.println("Error: reached maximum retransmission time.");
				System.exit(-1);
			}
			// re-send the timeout segments
			for (SentSegInfo seg: timeoutSegs) {
				long timeStamp;
				try {
//					System.out.println("3 duplicates resend:");
					numRetransmission++;
					timeStamp = sendTCPSeg(seg.data, seg.sequenceNum, false, true, false);
				} catch (IOException e) {
					lock.unlock();
					e.printStackTrace();
					return;
				}
				sendBuff.updateTimeStamp(timeStamp, seg.sequenceNum);
			}
			
			empty.signal();
			lock.unlock();
		}
	};
	
	// Runnable through which thread checks and re-send timeout segments
	Runnable handleTimeout = () -> {
		while (true) {
			lock.lock();
			
			// check if transmission is completed
			if (sendBuff.isEmpty() && done) {
				numDupAcks = sendBuff.getDupAcks();
				lock.unlock();
				return;
			}
			
			// check if any segment reaches timeout and needs to be retransmitted
			ArrayList<SentSegInfo> timeoutSegs = sendBuff.checkTimeout(this.TO);
			// check if any of the segments have been retransmitted for more than 16 times
			if (timeoutSegs == null) {
				System.err.println("Error: reached maximum retransmission time.");
				System.exit(-1);
			}
			// re-send the timeout segments
			for (SentSegInfo seg: timeoutSegs) {
				long timeStamp;
				try {
//					System.out.println("Timeout resend:");
					numRetransmission++;
					timeStamp = sendTCPSeg(seg.data, seg.sequenceNum, false, true, false);
				} catch (IOException e1) {
					lock.unlock();
					e1.printStackTrace();
					return;
				}
				sendBuff.updateTimeStamp(timeStamp, seg.sequenceNum);
				try {
					getAck.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
					return;
				}
			}
			
			empty.signal();
			lock.unlock();
		}
	};
	
	/**
	 * Public constructor that assigns class members with passed-in arguments, open the file with the specified
	 * file name, create the socket, and connects to the remote IP with the specified port number
	 * @throws SocketException 
	 * @throws UnknownHostException 
	 * @throws FileNotFoundException 
	 * 
	 * */
	public TCPSender(int port, String IPaddr, int remotePort, String fileName, int mtu, int sws) 
			throws SocketException, UnknownHostException, FileNotFoundException			
	{
		// assign arguments to members
		this.mtu = mtu;
		this.remotePort = remotePort;
		this.remoteIP = InetAddress.getByName(IPaddr);
		// initialize input stream, socket, and buffer
		this.in = new FileInputStream(new File(fileName));
		this.socket = new DatagramSocket(port);	
		this.sendBuff = new SenderBuffer(sws*mtu);
		// initialize buffer pointers
		this.lastByteSent = -1;
		this.ack = 0;
		// initialize timeout-related info
		this.ERTT = 0;
		this.EDEV = 0;
		this.TO = TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
		// initialize the statistics counter
		this.sizeDataSent = 0;
		this.numPacketSent = 0;
		this.numPacketReceived = 0;
		this.numRetransmission = 0;
		this.numIcrtChecksum = 0;
		this.numDupAcks = 0;
	}	
	
	/**
	 * Establish connection through three-way handshaking 
	 * @throws IOException 
	 * */
	public void connect() throws IOException
	{
		socket.connect(remoteIP, remotePort);
		
		// send first SYN
		sendTCPSeg(null, 0, true, true, false);
		
		// the response from the receiver
		byte[] rbuff = new byte[HEADER_LEN];
		DatagramPacket rsPacket = new DatagramPacket(rbuff, rbuff.length);
		// wait for the ack
		socket.setSoTimeout((int) (this.TO/1000000));
		// retransmit no more than 16 times
		int rtCnt = 0;
		while(true) {
			try {
				socket.receive(rsPacket);
			} catch (SocketTimeoutException e) {
				// re-send the SYN
				numRetransmission++;
				rtCnt++;
				if (rtCnt > MNR) {
					System.out.println("SYN state reaches maximum retransmission number.");
					System.exit(-1);
				}
				sendTCPSeg(null, 0, true, true, false);
			}
			
			numPacketReceived++;
//			printSegInfo(rsPacket.getData());
			
			// check if checksum is correct
			if (computeCheckSum(rsPacket.getData()) != getCheckSum(rsPacket.getData())) {
				System.out.println("Wrong checksum in SYN state");
				numIcrtChecksum++;
				// r-send first SYN
				numRetransmission++;
				rtCnt++;
				if (rtCnt > MNR) {
					System.out.println("SYN state reaches maximum retransmission number.");
					System.exit(-1);
				}
				sendTCPSeg(null, 0, true, true, false);
				continue;
			}
			
			if (isSYN(rsPacket.getData()) && hasACK(rsPacket.getData())) {
				ack = getSeq(rsPacket.getData()) + 1;
				break;
			} else {
				// r-send first SYN
				numRetransmission++;
				rtCnt++;
				if (rtCnt > MNR) {
					System.out.println("SYN state reaches maximum retransmission number.");
					System.exit(-1);
				}
				sendTCPSeg(null, 0, true, true, false);
			}
		}
		// received the SYN and ACK, then send an ACK and establish the connection
		socket.setSoTimeout(0);
		sendTCPSeg(null, 0, false, true, false);
		lastByteSent = 0;
	}
	
	/**
	 * Start sending the file to the receiver
	 * @throws IOException 
	 * @throws InterruptedException 
	 * */
	public void run() throws IOException, InterruptedException {		
		Thread segSender = new Thread(sendSegment);
		Thread ackHandler = new Thread(handleAck);
		Thread timeoutHandler = new Thread(handleTimeout);
		
		timeoutHandler.start();
		ackHandler.start();
		segSender.start();
		
		segSender.join();
		ackHandler.join();
		timeoutHandler.join();
	}
	
	/**
	 * Close the connection
	 * @throws IOException 
	 * */
	public void close() throws IOException {
		// send FIN and wait for ACK
		sendTCPSeg(null, lastByteSent+1, false, true, true);
		
		byte[] finBuff = new byte[HEADER_LEN];
		DatagramPacket finPacket = new DatagramPacket(finBuff, HEADER_LEN);
		socket.setSoTimeout((int)(this.TO/1000000));
		// retransmit no more than 16 times
		int rtCnt = 0;
		while(true) {
			try {
				socket.receive(finPacket);
				numPacketReceived++;
//				printSegInfo(finPacket.getData());
				
				// check if checksum is correct
				if (computeCheckSum(finPacket.getData()) != getCheckSum(finPacket.getData())) {
					System.out.println("Wrong checksum in SYN state");
					numIcrtChecksum++;
					// r-send FIN
					numRetransmission++;
					rtCnt++;
					if (rtCnt > MNR) {
						System.out.println("SYN state reaches maximum retransmission number.");
						System.exit(-1);
					}
					sendTCPSeg(null, lastByteSent+1, false, true, false);
					continue;
				}
				
				if (isFIN(finPacket.getData()) && hasACK(finPacket.getData()) 
						&& getAck(finPacket.getData()) == lastByteSent + 2) {
					// receive FIN and ACK from receiver, send ACK
					ack = getSeq(finPacket.getData()) + 1;
					sendTCPSeg(null, lastByteSent+1, false, true, false);
					break;
				} else {
					// r-send the Fin
					numRetransmission++;
					rtCnt++;
					if (rtCnt > MNR) {
						System.out.println("FIN state reaches maximum retransmission number.");
						System.exit(-1);
					}
					sendTCPSeg(null, lastByteSent+1, false, true, false);
				}
			} catch (SocketTimeoutException e) {
				// re-send the FIN
				numRetransmission++;
				rtCnt++;
				if (rtCnt > MNR) {
					System.out.println("FIN state reaches maximum retransmission number.");
					System.exit(-1);
				}
				sendTCPSeg(null, lastByteSent+1, false, true, true);
			}
		}
		
		in.close();
		socket.close();
		printStat();
	}
	
	/**
	 * Sends out a TCP segment with TCP header and passed in data, with sequence number seq.
	 * Returns the timestamp of the data sent.
	 * @throws IOException 
	 * */
	private long sendTCPSeg(byte[] data, int seq, boolean SYN, boolean ACK, boolean FIN) throws IOException {
		this.numPacketSent++;
		// The message to be printed
		String outmsg = "snd ";
		int dataLen;
		// the actual data plus header to be sent out
		if (data == null) 
			// when no data is passed in, it is either SYN or FIN, and set data length to be zero byte
			dataLen = 0;
		else
			dataLen = data.length;
		
		ByteBuffer buffer = ByteBuffer.allocate(HEADER_LEN + dataLen);
		buffer.putInt(seq);
		buffer.putInt(ack);
		long timeStamp = System.nanoTime();
		buffer.putLong(timeStamp);
		
		// format the printout message
		outmsg += timeStamp + " ";
		if (SYN) outmsg += "S ";
		else outmsg += "- ";
		if (ACK) outmsg += "A ";
		else outmsg += "- ";
		if (FIN) outmsg += "F ";
		else outmsg += "- ";
		if (dataLen != 0) outmsg += "D ";
		else outmsg += "- ";
		outmsg += seq + " " + dataLen + " " + ack;
		
		dataLen = dataLen << 3;
		// set up flags
		if (SYN) dataLen = dataLen | 0x4;
		if (ACK) dataLen = dataLen | 0x2;
		if (FIN) dataLen = dataLen | 0x1;
		buffer.putInt(dataLen);
		buffer.putShort((short)0);
		// assume for now checksum is all 1s
		buffer.putShort((short)0x0);
		// put data in the segment if there are data
		if (data != null)
			buffer.put(data);
		
		// compute and put checksum
		short checksum = computeCheckSum(buffer.array());
		buffer.putShort(22, checksum);
		
		// send out the segment
		socket.send(new DatagramPacket(buffer.array(), buffer.capacity(), remoteIP, remotePort));
		System.out.println(outmsg);
		return timeStamp;
	}
	
	/**
	 * Computes and update current timeout information
	 * */
	private void computeTimeout(long sentTime) {
		if (ERTT == 0) {
			ERTT = System.nanoTime() - sentTime;
			TO = 2 * ERTT;
			return;
		}
		double SRTT = (double)(System.nanoTime() - sentTime);
		double SDEV = Math.abs(SRTT - ERTT);
		ERTT = alpha * ERTT + (1 - alpha) * SRTT;
		EDEV = beta * EDEV + (1 - beta) * SDEV;
		TO = ERTT + 4 * EDEV;
	}
	
	
	/**
	 * Computes checksum
	 * */
	private static short computeCheckSum(byte[] data) {
		ByteBuffer buff = ByteBuffer.wrap(data);
		int accumulation = 0;
		while (true) {
			try {
				short curSeg;
				if (buff.position() == 22) {
					curSeg = (short) 0x0;
					buff.position(24);
				}
				else {
					curSeg = buff.getShort();
				}
				accumulation += 0xffff & curSeg;
				accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
			} catch (BufferUnderflowException e) {
				try {
					short curSeg = (short) (buff.get() << 8);
					accumulation += 0xffff & curSeg;
					accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
				} catch (BufferUnderflowException e1) {
					// reach end of buffer
					break;
				}
			}
		}
		return (short)(~accumulation & 0xffff);
	}
	
	/**
	 * Prints out the statistics
	 * */
	private void printStat() {
		System.out.printf("Amount of Data transferred: %d bytes.\n", this.sizeDataSent);
		System.out.printf("Number of packets sent: %d.\n", this.numPacketSent);
		System.out.printf("Number of packets received: %d.\n", this.numPacketReceived);
		System.out.printf("Number of packets discarded due to incorrect checksum: %d.\n", this.numIcrtChecksum);
		System.out.printf("Number of retransimissions: %d.\n", this.numRetransmission);
		System.out.printf("Number of duplicate acknowledgements: %d.\n", this.numDupAcks);
	}
	
	// Methods to parse the TCP header of the received segment

	/**
	 * Retrieve the sequence number of the received segment
	 * */
	private int getSeq(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		return buff.getInt();
	}
	
	/**
	 * Retrieve the acknowledgement of the received segment
	 * */
	private int getAck(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		return buff.getInt(4);
	}
	
	/**
	 * Retrieve the TimeStamp of the received segment
	 * */
	private long getTime(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		return buff.getLong(8);
	}
	
	/**
	 * Retrieve the data length of the received segment
	 * */
	private int getLen(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		int len = buff.getInt(16);
		return len >> 3;
	}
	
	/**
	 * Retrieve the checksum of the received segment
	 * */
	private short getCheckSum(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		return buff.getShort(22);
	}
	
	/**
	 * Check if the SYN flag is set
	 * */
	private boolean isSYN(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		int len = buff.getInt(16);
		return (len & 0x4) == 4;
	}
	
	/**
	 * Check if the ACK flag is set
	 * */
	private boolean hasACK(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		int len = buff.getInt(16);
		return (len & 0x2) == 2;
	}
	
	/**
	 * Check if the FIN flag is set
	 * */
	private boolean isFIN(byte[] header) {
		ByteBuffer buff = ByteBuffer.wrap(header);
		int len = buff.getInt(16);
		return (len & 0x1) == 1;
	}
	
	/**
	 * Prints out the information of the received segment
	 * */
	private void printSegInfo(byte[] seg) {
		String out = "Sender receives ";
		out += getTime(seg) + " ";
		if (isSYN(seg)) out += "S ";
		else out += "- ";
		if (hasACK(seg)) out += "A ";
		else out += "- ";
		if (isFIN(seg)) out += "F ";
		else out += "- ";
		out += getSeq(seg) + " " + getLen(seg) + " " + getAck(seg);
		System.out.println(out);
	}
}
