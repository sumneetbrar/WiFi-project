package wifi;

import java.io.PrintWriter;
import java.util.concurrent.ArrayBlockingQueue;

import rf.RF;

/**
 * Use this layer as a starting point for your project code. See
 * {@link Dot11Interface} for more details on these routines.
 * 
 * @author Sumneet, Tyler
 */
public class LinkLayer implements Dot11Interface {
	private RF theRF; // You'll need one of these eventually
	private short ourMAC; // Our MAC address
	private PrintWriter output; // The output stream we'll write to

	ArrayBlockingQueue<byte[]> senderQueue = new ArrayBlockingQueue<byte[]>(10);
	ArrayBlockingQueue<Short> destQueue = new ArrayBlockingQueue<Short>(10);
	ArrayBlockingQueue<byte[]> receiverQueue = new ArrayBlockingQueue<byte[]>(10);

	private SendThread s = new SendThread(theRF, senderQueue, destQueue, ourMAC, output); // Send thread object
	private ReceiveThread r = new ReceiveThread(receiverQueue, theRF, ourMAC, output); // Receive thread object

	/**
	 * Constructor takes a MAC address and the PrintWriter to which our output will
	 * be written.
	 * 
	 * @param ourMAC MAC address
	 * @param output Output stream associated with GUI
	 */
	public LinkLayer(short ourMAC, PrintWriter output) {
		if (ourMAC < 0 || ourMAC > 0xFFFF)
			SharedState.state = 8;
		else
			this.ourMAC = ourMAC;

		this.output = output;
		theRF = new RF(null, null);
		output.println("LinkLayer initialized with MAC address " + ourMAC);
		output.println("Send command 0 to see a list of supported commands");

		(new Thread(s)).start(); // start the send thread
		(new Thread(r)).start(); // start the receive thread
	}

	/**
	 * Send method takes a destination, a buffer (array) of data, and the number
	 * of bytes to send.
	 */
	public int send(short dest, byte[] data, int len) {
		// if the buffer size is negative, shared state 6 and return -1 for error
		if (data.length < 0) {
			SharedState.state = 6;
			return -1;
		}

		// if the data or destination address is null, shared state 7 and return -1 for
		// error
		else if (data == null || dest == 0) { // is it possible to have a destination address of 0?
			SharedState.state = 7;
			return -1;
		}

		// if there are more than 4 packets in the queue, shared state 10 and do not
		// accept any packets
		else if (senderQueue.size() > 4) {
			SharedState.state = 10;
			return 0; // 0 signifies that we are not accepting any packets
		}

		// if the data is within the acceptable length, attempt to transmit it
		else if (data.length <= (RF.aMPDUMaximumLength - 10)) {
			senderQueue.add(data); // add the data to the queue
			destQueue.add(dest); // add destination to the queue
			if(SharedState.debugLevel == -1) output.println("LinkLayer: Sending " + len + " bytes to " + dest);
			return len; // this is the number of bytes we are accepting to send
		}

		// otherwise, return 0 for dropped packet
		else
			return 0;
	}

	/**
	 * Recv method blocks until data arrives, then writes it an address info into
	 * the Transmission object. See docs for full description.
	 */
	public int recv(Transmission t) {
		try {
			byte[] receivedPacket = receiverQueue.take();
			short destinationAddress = (short) ((receivedPacket[2] << 8) | (receivedPacket[3] & 0xFF));
			short sourceAddress = (short) ((receivedPacket[4] << 8) | (receivedPacket[5] & 0xFF));

			t.setBuf(receivedPacket);
			t.setDestAddr(destinationAddress);
			t.setSourceAddr(sourceAddress);

			return receivedPacket.length;

		} catch (InterruptedException e) {
			SharedState.state = 2;
		}

		return -1;
	}

	/**
	 * Returns a current status code.
	 */
	public int status() {
		return SharedState.state; // current status
	}

	/**
	 * Passes command info to your link layer.
	 */
	public int command(int cmd, int val) {
		if (cmd == 0) {
			// print out a list of commands
			output.println("-------------- Commands and Settings --------------");
			output.println("Cmd #0: Display command options and current settings");
			output.println("Cmd #1: Set debug level. Currently at " + SharedState.debugLevel
					+ "\n        Use -1 for full debug output, 0 for no output");
			output.println(
					"Cmd #2: Use slot selection method. Currently random\n        Use 0 for random slot selection, any other value to use maxCW");
			output.println(
					"Cmd #3: Set beacon interval. Currently at 3 seconds.\n        Value specifies seconds between the start of beacons; -1 disables");
		} else if (cmd == 1) {
			SharedState.debugLevel = val; // update debug level
			if(val == -1) output.println("Setting debug to -1");
		} else if (cmd == 2) {
			SharedState.slotSelection = val; // update slot selection method
		} else {
			SharedState.beaconInterval = val; // update beacon interval
		}
		return 0; // always returns 0
	}

}
