import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TCPReceiver {
	// TCP header length
	private static final int HEADER_LEN = 24;
	// maximum number of retransmission
	private static final int MNR = 16;

	// maximum transmission unit in bytes
	private int mtu;

	// the unreliable UDP receiver socket
	private DatagramSocket socket;

	// the IP address of the sender
	private InetAddress senderIP;
	// the port used by the sender
	private int senderPort;

	// the receiver buffer
	private ReceiverBuffer recBuff;

	// current sequence number
	private int seq;

	// the output stream that writes received byte to the specified file
	private OutputStream out;

	// statistics counters
	// amount of data received
	private int sizeDataReceived;
	// number of packets sent
	private int numPacketSent;
	// number of packets received
	private int numPacketReceived;
	// number of out-of-sequence packets discarded
	private int numDiscardPacket;
	// number of packets discarded due to incorrect checksum
	private int numIcrtChecksum;
	// number of retransmissions
	private int numRetransmission;

	// concurrency tools
	private final Lock lock = new ReentrantLock();
	private final Condition empty = lock.newCondition();
	private final Condition fill = lock.newCondition();
	private boolean done = false;

	// runnable through which thread puts data received to the receiver buffer
	private Runnable receiveSegment = () -> {
		while(true) {
			byte[] buf = new byte[HEADER_LEN + mtu];
			DatagramPacket packet = new DatagramPacket(buf, HEADER_LEN + mtu);
			try {
				socket.receive(packet);
			} catch (IOException e1) {
				e1.printStackTrace();
				return;
			}
//			printSegInfo(packet.getData());
			numPacketReceived++;

			// check if checksum is correct
			if (computeCheckSum(packet.getData()) != getCheckSum(packet.getData())) {
				System.out.println("Wrong checksum!");
				numIcrtChecksum++;
				// get current ack through buffer
				lock.lock();
				int ack = recBuff.writeToBuffer(null, -1, -1);
				try {
					sendTCPSeg(ack, false, true, false);
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}
				lock.unlock();
			}

			// received FIN from the sender, end connection
			if (isFIN(packet.getData())) {
				try {
					// send FIN and ACK
					sendTCPSeg(getSeq(packet.getData())+1, false, true, true);
					byte[] finBuff = new byte[HEADER_LEN];
					DatagramPacket finPacket = new DatagramPacket(finBuff, HEADER_LEN);
					socket.setSoTimeout(5000);
					try {
						socket.receive(finPacket);
					} catch (SocketTimeoutException e) {
						// timeout, re-send FIN and ACK
						numRetransmission++;
						seq++;
						done = true;
						lock.lock();
						fill.signal();
						lock.unlock();
						return;
					}
//					printSegInfo(finPacket.getData());
					numPacketReceived++;

					// check if checksum is correct
					if (computeCheckSum(finPacket.getData()) != getCheckSum(finPacket.getData())) {
						numIcrtChecksum++;
					}
					seq++;
					done = true;
					lock.lock();
					fill.signal();
					lock.unlock();
					return;			
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}
			}

			byte[] data = getData(packet.getData());
			int dataLen = getLen(packet.getData());
			int startByte = getSeq(packet.getData());
			int endByte = startByte + dataLen - 1;

			lock.lock();

			// wait until the buffer could hold the data
			while (recBuff.isFull(dataLen)) {
				//					System.out.println("Receiver buffer is full!");
				try {
					empty.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
					return;
				}
			}
			// write to the buffer and retrieve the ACK number
			int ack = recBuff.writeToBuffer(data, startByte, endByte);
			// send ACK to the sender
			try {
				sendTCPSeg(ack, false, true, false);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}

			fill.signal();
			lock.unlock();
		}
	};

	// runnable through which thread reads data from the buffer and writes to file
	private Runnable writeToFile = () -> {
		while (true) {
			lock.lock();

			// try to get data from buffer. If there is nothing to be read, wait for more data
			byte[] data = null;
			while((data = recBuff.readFromBuffer()) == null) {
				// check if the transmission is done
				if (done) {
					lock.unlock();
					return;
				}
				try {
					fill.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
					empty.signal();
					lock.unlock();
					return;
				}
			}

			// write the data read from buffer to file
			try {
				out.write(data);
				sizeDataReceived += data.length;
			} catch (IOException e) {
				e.printStackTrace();
				empty.signal();
				lock.unlock();
				return;
			}

			empty.signal();
			lock.unlock();
		}
	};

	/***
	 * Public constructor that assigns class members with passed-in arguments, opens the file with specified file name
	 * @throws SocketException 
	 * @throws FileNotFoundException 
	 */
	public TCPReceiver(int port, String fileName, int mtu, int sws) throws SocketException, FileNotFoundException {
		this.mtu = mtu;
		this.socket = new DatagramSocket(port);
		this.seq = 0;
		this.out = new FileOutputStream(new File(fileName));
		this.recBuff = new ReceiverBuffer(sws*mtu);
		// initialize statistic counters
		this.sizeDataReceived = 0;
		this.numPacketReceived = 0;
		this.numPacketSent = 0;
		this.numDiscardPacket = 0;
		this.numIcrtChecksum = 0; 
		this.numRetransmission = 0;
	}

	/**
	 * Waits for the sender to send SYN, and responds with SYN+ACK, implementing the 
	 * three-way handshaking
	 * */
	public void connect() throws IOException {
		outer: while (true) {
			byte[] buffer = new byte[HEADER_LEN];
			DatagramPacket packet = new DatagramPacket(buffer, HEADER_LEN);
			socket.receive(packet);
//			printSegInfo(packet.getData());
			numPacketReceived++;

			// check if checksum is correct
			if (computeCheckSum(packet.getData()) != getCheckSum(packet.getData())) {
				System.out.println("Wrong checksum in SYN state");
				numIcrtChecksum++;
				continue;
			}

			// check if the sender sends SYN
			if (isSYN(packet.getData())) {
				this.senderIP = packet.getAddress();
				this.senderPort = packet.getPort();
				sendTCPSeg(1, true, true, false);
				// keep sending SYN and ACK if did not receive ACK from sender
				socket.setSoTimeout(5000);
				// retransmit no more than 16 times
				int rtCnt = 0;
				while (true) {
					try {
						socket.receive(packet);
					} catch (SocketTimeoutException e) {
						// re-send SYN
						numRetransmission++;
						rtCnt++;
						if (rtCnt > MNR) {
							System.out.println("SYN state reaches maximum number of retransmission.");
							System.exit(-1);
						}
						sendTCPSeg(1, true, true, false);
						continue;
					}
//					printSegInfo(packet.getData());
					numPacketReceived++;

					// check if checksum is correct
					if (computeCheckSum(packet.getData()) != getCheckSum(packet.getData())) {
						System.out.println("Wrong checksum in SYN state");
						numIcrtChecksum++;
						// r-send first SYN
						numRetransmission++;
						rtCnt++;
						if (rtCnt > MNR) {
							System.out.println("SYN state reaches maximum retransmission number.");
							System.exit(-1);
						}
						sendTCPSeg(1, true, true, false);
						continue;
					}

					if (hasACK(packet.getData()) && getAck(packet.getData()) == seq+1) {
						// received the ACK, and the connection is established
						seq++;
						break outer;
					} else {
						// if the ACK is wrong, re-send SYN and ACK
						numRetransmission++;
						rtCnt++;
						if (rtCnt > MNR) {
							System.out.println("SYN state reaches maximum number of retransmission.");
							System.exit(-1);
						}
						sendTCPSeg(1, true, true, false);
					}
				}
			}
		}
	socket.setSoTimeout(0);
	}

	/**
	 * Start running the socket to receive the file
	 * @throws IOException 
	 * @throws InterruptedException 
	 * */
	public void run() throws IOException, InterruptedException {
		Thread segReceiver = new Thread(receiveSegment);
		Thread fileWriter = new Thread(writeToFile);

		segReceiver.start();
		fileWriter.start();

		segReceiver.join();
		fileWriter.join();
	}

	/**
	 * Close the connection
	 * @throws IOException 
	 * */
	public void close() throws IOException {
		this.numDiscardPacket = recBuff.getNumDscPckt();
		out.close();
		socket.close();
		this.printStat();
	}

	/**
	 * Sends out a TCP segment with TCP header
	 * @throws IOException 
	 * */
	private void sendTCPSeg(int ack, boolean SYN, boolean ACK, boolean FIN) throws IOException {
		this.numPacketSent++;
		int dataLen = 0;
		// The message to be printed
		String outmsg = "rcv ";

		ByteBuffer buffer = ByteBuffer.allocate(24);
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
		// assume for now checksum is all 0s
		buffer.putShort((short)0x0);

		// compute checksum
		short checksum = computeCheckSum(buffer.array());
		buffer.putShort(22, checksum);

		// send out the segment
		socket.send(new DatagramPacket(buffer.array(), buffer.capacity(), senderIP, senderPort));
		System.out.println(outmsg);
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
		System.out.printf("Amount of Data received: %d bytes.\n", this.sizeDataReceived);
		System.out.printf("Number of packets sent: %d.\n", this.numPacketSent);
		System.out.printf("Number of packets received: %d.\n", this.numPacketReceived);
		System.out.printf("Number of out-of-sequence packets discarded: %d.\n", this.numDiscardPacket);
		System.out.printf("Number of packets discarded due to incorrect checksum: %d.\n", this.numIcrtChecksum);
		System.out.printf("Number of retransimissions: %d.\n", this.numRetransmission);
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
	 * get the data part of the TCP segment
	 * */
	private byte[] getData(byte[] header) {
		int dataLen = getLen(header);
		if (dataLen == 0) {
			return null;
		} else {
			byte[] data = new byte[dataLen];
			ByteBuffer buff = ByteBuffer.wrap(header);
			buff.position(24);
			buff.get(data, 0, dataLen);
			return data;
		}
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
		String out = "receiver receives ";
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
