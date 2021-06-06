import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;

public class TCPend {
	
	// parameters of the TCP sender and receiver
	// local port number of the sender/ receiver
	private static int port;
	// IP address of the receiver used by the sender
	private static String remoteIPAddr;
	// port number of the receiver used by the sender
	private static int remotePort;
	// the name of the file to be sent/ receive
	private static String fileName;
	// maximum transmission unit
	private static int mtu;
	// sliding window size
	private static int sws;

	public static void main(String[] args) {
		if (args.length == 12) {
			// sender mode
			// check if arguments are valid
			if (!args[0].equalsIgnoreCase("-p") || !args[2].equalsIgnoreCase("-s") 
					|| !args[4].equalsIgnoreCase("-a") || !args[6].equalsIgnoreCase("-f") 
					|| !args[8].equalsIgnoreCase("-m") || !args[10].equalsIgnoreCase("-c")) {
				System.err.println("Error: missing or additional arguments");
				return;
			}
			
			// read in and parse arguments
			if (!parse_args(args, true)) {
				System.err.println("An error has occurred. Aborted.");
				return;
			}
			
			// create TPC sender
			TCPSender sender;
			try {
				sender = new TCPSender(port, remoteIPAddr, remotePort, fileName, mtu, sws);
			} catch (SocketException e) {
				System.err.println("Error: cannot create socket");
				return;
			} catch (UnknownHostException e) {
				System.err.println("Error: unknown host name");
				return;
			} catch (FileNotFoundException e) {
				System.err.println("Error: file not found");
				return;
			}
			
			System.out.println("TCP sender created.");
			
			try {
				sender.connect();
			} catch (IOException e) {
				System.err.println("Error: active open of sender failed");
				return;
			}
			
			System.out.println("TCP sender connection established. Start running.");
			
			try {
				sender.run();
			} catch (IOException e) {
				System.err.println("Error: failed I/O operations during transferring file");
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			System.out.println("TCP sender file transfer finished.");
			
			try {
				sender.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			System.out.println("TCP sender closed.");
			
		} else if (args.length == 8) {
			// receiver mode
			// check if arguments are valid
			if (!args[0].equalsIgnoreCase("-p") || !args[2].equalsIgnoreCase("-m") 
					|| !args[4].equalsIgnoreCase("-c") || !args[6].equalsIgnoreCase("-f")) {
				System.err.println("Error: missing or additional arguments");
				return;
			}
			
			// read in and parse arguments
			if (!parse_args(args, false)) {
				System.err.println("An error has occurred. Aborted.");
				return;
			}
			
			// create TCP receiver
			TCPReceiver receiver;
			try {
				receiver = new TCPReceiver(port, fileName, mtu, sws);
			} catch (SocketException e) {
				System.err.println("Error: cannot create socket");
				return;
			} catch (FileNotFoundException e) {
				System.err.println("Error: file not found");
				return;
			}
			
			System.out.println("TCP receiver created.");
			
			try {
				receiver.connect();
			} catch (IOException e) {
				System.err.println("Error: passive open of receiver failed");
				return;
			}
			
			System.out.println("TCP receiver connected to sender. Start running.");
			
			try {
				receiver.run();
			} catch (IOException e) {
				System.err.println("Error: failed I/O operations during receiving file");
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			System.out.println("TCP receiver file transfer finished.");
			
			try {
				receiver.close();
			} catch (IOException e) {
				System.err.println("Error: output stream cannot be closed");
				return;
			}
			
			System.out.println("TCP receiver closed.");
		} else {
			System.err.println("Incorrect arguments.");
			return;
		}

	}

	
	/**
	 * The helper methods that parses all the arguments
	 * */
	private static boolean parse_args(String[] args, boolean isSender) {
		if (isSender) {
			try {
				port = Integer.parseInt(args[1]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid port number");
				return false;
			}
			
			remoteIPAddr = args[3];
	
			try {
				remotePort = Integer.parseInt(args[5]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid remote port number");
				return false;
			}
			
			fileName = args[7];
			
			try {
				mtu = Integer.parseInt(args[9]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid maximum transmission unit");
				return false;
			}
			
			try {
				sws = Integer.parseInt(args[11]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid sliding window size");
				return false;
			}
		} else {
			try {
				port = Integer.parseInt(args[1]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid port number");
				return false;
			}
			
			try {
				mtu = Integer.parseInt(args[3]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid maximum transmission unit");
				return false;
			}
			
			try {
				sws = Integer.parseInt(args[5]);
			} catch (NumberFormatException e) {
				System.err.println("Error: invalid sliding window size");
				return false;
			}

			fileName = args[7];
		}
		
		return true;
	}

}
