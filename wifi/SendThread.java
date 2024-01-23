package wifi;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.CRC32;
import rf.RF;

/**
 * This implements the send thread with the ("Brad Danger Mode") 802.11~ finite state diagram
 * 
 * @author Sumneet Brar and Tyler Higashihara
 */
public class SendThread implements Runnable {

  private RF theRF; // our rf layer
  private ArrayBlockingQueue<byte[]> queue; // The ArrayBlockingQueue
  private ArrayBlockingQueue<Short> destQueue; // The ArrayBlockingQueue
  private short ourMAC; // our mac address
  private short destAddress; // destination address
  private byte[] frame; // frame to be sent

  private double SIFS = RF.aSIFSTime; // SIFS time
  private int SLOT_TIME = RF.aSlotTime; // Slot time
  private long DIFS = (long) SIFS + (2 * SLOT_TIME); // DIFS time

  private int currentCW; // current CW value
  private int CWMin = RF.aCWmin; // minimum CW value
  private int CWMax = RF.aCWmax; // maximum CW value

  private final short BROADCAST_ADDRESS = (short) 0xFFFF; // broadcast address
  private final int MAX_NUM_OF_TRANSMISSIONS = RF.dot11RetryLimit; // maximum number of transmissions

  private HashMap<Short, Integer> sequenceNumbers = new HashMap<>(); // sequence numbers for each destination
  private int numOfTransmissions = 0; // number of transmissions
  private int previousSequenceNumber;
  private boolean ACKReceived = false;
  private long offset = SharedState.offset;
  private long timeToBuildPacket;
  private long timeToTransmitPacket;
  private long timeToTransmitACK;
  private long beaconInterval = SharedState.beaconInterval;

  private PrintWriter output; // The output stream we'll write to

  /**
   * Constructor for SendThread
   * 
   * @param theRF       the RF layer
   * @param queue       the ArrayBlockingQueue
   * @param ourMAC      our MAC address
   * @param destAddress destination address
   * 
   */
  public SendThread(RF theRF, ArrayBlockingQueue<byte[]> queue, ArrayBlockingQueue<Short> destQueue, short ourMAC, PrintWriter output) {
    this.theRF = theRF;
    this.queue = queue;
    this.destQueue = destQueue;
    if (ourMAC < 0 || ourMAC > 0xFFFF) {
      SharedState.state = 8;
    }
    else this.ourMAC = ourMAC;
    this.output = output;
  }

  @Override
  public void run() {
    // if the queue is not empty, build the frame
    while (queue.peek() != null && destQueue.peek() != null) {
      try {
        destAddress = destQueue.take();
      } catch (InterruptedException e) {
        SharedState.state = 2;
      }

      numOfTransmissions = 0;
      transmitBeacon();
      frame = buildFrame();

      if(SharedState.debugLevel == -1) output.println("Queuing" + frame.length + " bytes for" + destAddress);
      if(SharedState.debugLevel == -1) output.println("Starting collision window at [0..3");
      
      // if the channel is not in use, wait DIFS and transmit frame
      if (!theRF.inUse() && !ACKReceived) {
        if(SharedState.debugLevel == -1) output.println("Moving to IDLE_DIFS_WAIT with pending DATA");
        try {
          Thread.sleep(DIFS);
        } catch (InterruptedException e) {
          SharedState.state = 2;
        }
        theRF.transmit(frame);
        if (destAddress != BROADCAST_ADDRESS) {
          if(SharedState.debugLevel == -1) output.println("Moving to AWAIT_ACK after sending DATA");
          awaitACK(); // wait for ACK
        }
        else {
          ACKReceived = true;
        }
      }

      // if the channel is in use, set the current CW to the minimum CW value and wait for idle
      if (theRF.inUse() && numOfTransmissions == 0 && !ACKReceived) {
        currentCW = CWMin;
        if(SharedState.debugLevel == -1) output.println("Starting collision window at [0..3");
        waitForIdle();
        waitSlot();
        if (destAddress != BROADCAST_ADDRESS) {
          if(SharedState.debugLevel == -1) output.println("Moving to AWAIT_ACK after sending DATA");
          awaitACK(); // wait for ACK
        }
        else {
          ACKReceived = true;
        }
      }

      if (theRF.inUse() && numOfTransmissions > 0 && !ACKReceived) {
        while (numOfTransmissions < MAX_NUM_OF_TRANSMISSIONS || !ACKReceived) {
          waitForIdle();
          waitSlot();
          if(SharedState.debugLevel == -1) output.println("Moving to AWAIT_ACK after sending DATA");
          awaitACK(); // wait for ACK
        }
      }

      if (ACKReceived) {
        SharedState.state = 4;
      }

      else if (numOfTransmissions == MAX_NUM_OF_TRANSMISSIONS || numOfTransmissions > MAX_NUM_OF_TRANSMISSIONS) {
        SharedState.state = 5;
      }
    }
  }

  /**
   * Builds the frame
   */
  private byte[] buildFrame() {
    byte[] data = null;
    try {
      data = queue.take(); // This will block until there's something in the queue
    } catch (InterruptedException e) {
      SharedState.state = 2;
    }

    long frameBuildStartTime = theRF.clock(); // start timer
    // Create a byte array to hold the frame with size 10 + length of data
    byte[] frame = new byte[10 + data.length];

    // Get the next sequence number for the destination, or 0 if there is no entry
    // for the destination yet
    int sequenceNumber = sequenceNumbers.getOrDefault(destAddress, 0);
    sequenceNumbers.put(destAddress, sequenceNumber + 1); // Increment the sequence number for the destination

    frame[0] = (byte) ((sequenceNumber >> 8) & 0x0F); // Get the first 4 bits of sequence number
    frame[1] = (byte) (sequenceNumber & 0xFF); // Get the next 8 bits

    previousSequenceNumber = sequenceNumber; // so we can compare later
    sequenceNumber = (sequenceNumber + 1) % 4096; // Increment sequence number

    frame[2] = (byte) ((destAddress >> 8) & 0xFF); // Get the first 8 bits of the destination address
    frame[3] = (byte) (destAddress & 0xFF); // Get the next 8 bits

    frame[4] = (byte) ((ourMAC >> 8) & 0xFF); // Get the first 8 bits of our MAC address
    frame[5] = (byte) (ourMAC & 0xFF); // Get the next 8 bits

    System.arraycopy(data, 0, frame, 6, data.length); // Copy dataBytes into frame starting at position 6
    long CRC = calculateCRC(data); // Calculate the CRC value for the frame data

    int nextAvailablePosition = 6 + data.length; // Get the next available position in the frame
    frame[nextAvailablePosition] = (byte) (CRC >> 24); // Add the CRC value to the frame
    frame[nextAvailablePosition + 1] = (byte) (CRC >> 16); // Add the CRC value to the frame
    frame[nextAvailablePosition + 2] = (byte) (CRC >> 8); // Add the CRC value to the frame
    frame[nextAvailablePosition + 3] = (byte) (CRC); // Add the CRC value to the frame

    long frameBuildEndTime = theRF.clock();
    timeToBuildPacket = frameBuildEndTime - frameBuildStartTime;

    return frame;
  }

  private long calculateCRC(byte[] data) {
    CRC32 crc = new CRC32();
    crc.update(data);
    return crc.getValue();
  }

  /**
   * Waits DIFS
   */
  private void waitDIFS() {
    try {
      Thread.sleep(DIFS);
      if (theRF.inUse())
        waitForIdle();
      else
        return;
    } catch (InterruptedException e) {
      SharedState.state = 2;
    }
  }

  /**
   * If the channel is not idle, wait for the channel to become idle and then wait
   * DIFS
   */
  private void waitForIdle() {
    while (theRF.inUse()) {
      try {
        Thread.sleep(50000);
      } catch (InterruptedException e) {
        SharedState.state = 2;
      }
    }

    waitDIFS();
  }

  /**
   * Waits for 1 slot
   */
  private void waitSlot() {
    // need to wait 1 slot
    // while we do that, decrement count

    Random rand = new Random();
    int slot;
    if(SharedState.slotSelection == 0) {
      slot = rand.nextInt(0, currentCW);
      if(SharedState.debugLevel == -1) output.println("Using a random Collision Window value each time");
    }
    else {
      slot = CWMax;
      if(SharedState.debugLevel == -1) output.println("Using the max Collision Window value each time");
    }

    while (slot > 1) {
      if (!theRF.inUse()) {
        try {
          Thread.sleep(SLOT_TIME);
        } catch (InterruptedException e) {
          SharedState.state = 2;
        }
        slot--;
      } else {
          waitForIdle();
        }
    }

    theRF.transmit(frame);
  }

  /**
   * Starts a timer on the packet just sent. If an ACK is received before timeout,
   * all is good. Otheriwse, we need to retransmit the packet.
   */
  private void awaitACK() {
    // start timer
    double timeout = SIFS + timeToTransmitACK + 10; // + ack value + fudge factor
    long startTime = theRF.clock(); // start timer

    // Wait for ACK within range of timer
    while ((theRF.clock() - startTime) < timeout) {
      // Check if an ACK has been received
      if (SharedState.receiveThreadReceivedAnACK && SharedState.sequenceNumber == previousSequenceNumber) {
        if(SharedState.sourceAddress == destAddress){
          currentCW = CWMin; // ACK received, reset CW and move on
          return;
        }
        else {
          SharedState.state = 8;
        }
      }
    }

    long endTime = theRF.clock() - startTime;
    if(SharedState.debugLevel == -1) output.println("ACK timer expired at " + theRF.clock() + "(" + endTime + "ms early)");
    
    numOfTransmissions++; // increment number of transmissions
    if (numOfTransmissions == 1)
      frame[0] = (byte) (frame[0] | (1 << 3));

    currentCW = currentCW * 2;
    if(currentCW > CWMax) currentCW = CWMax;
  }

  // Clock sync stuff below - it gets messy from down here

  public void calcACKSendTime() {
    long startTime = theRF.clock();
    theRF.transmit(buildACK());
    long endTime = theRF.clock();
    timeToTransmitACK = endTime - startTime;
  }

  public byte[] buildACK() {
    byte[] frame = new byte[10];

    int sequenceNumber = sequenceNumbers.getOrDefault(destAddress, 0);
    frame[0] = (byte) ((2 << 4) | ((sequenceNumber >> 8) & 0x0F));
    frame[1] = (byte) (sequenceNumber & 0xFF); // Get the next 8 bits

    frame[2] = (byte) ((BROADCAST_ADDRESS >> 8) & 0xFF); // Get the first 8 bits of the destination address
    frame[3] = (byte) (BROADCAST_ADDRESS & 0xFF); // Get the next 8 bits

    frame[4] = (byte) ((ourMAC >> 8) & 0xFF); // Get the first 8 bits of our MAC address
    frame[5] = (byte) (ourMAC & 0xFF); // Get the next 8 bits

    byte[] header = Arrays.copyOfRange(frame, 0, 6); // Get the header of the frame
    long CRC = calculateCRC(header); // Calculate the CRC value for the frame header

    frame[6] = (byte) (CRC >> 24); // Add the CRC value to the frame
    frame[7] = (byte) (CRC >> 16); // Add the CRC value to the frame
    frame[8] = (byte) (CRC >> 8); // Add the CRC value to the frame
    frame[9] = (byte) (CRC); // Add the CRC value to the frame

    return frame;
  }

  public long localTime() {
    return theRF.clock() + offset;
  }

  public void transmitBeacon() {
    while(true) {
      try {
        Thread.sleep(beaconInterval);
        theRF.transmit(buildBeacon());
      } catch (InterruptedException e) {
        SharedState.state = 2;
      }
    }
  }

  public long buildTimeStamp() {
    // calculate time to build a packet and calculate time to send a packet
    measureBeaconTime();
    buildBeacon2();
    long time = localTime() + timeToBuildPacket + timeToTransmitPacket; 
    return time;
  }

  private void measureBeaconTime() {
    long startTime = theRF.clock();
    for(int i = 0; i < 10; i++) {
      theRF.transmit(buildBeacon());
    }
    long endTime = theRF.clock();
    timeToTransmitPacket = (endTime - startTime) / 10;
  }

  private byte[] buildBeacon() {
    byte[] frame = new byte[18];

    long startTime = theRF.clock();
    int sequenceNumber = sequenceNumbers.getOrDefault(destAddress, 0);
    frame[0] = (byte) ((4 << 4) | ((sequenceNumber >> 8) & 0x0F));
    frame[1] = (byte) (sequenceNumber & 0xFF); // Get the next 8 bits

    frame[2] = (byte) ((BROADCAST_ADDRESS >> 8) & 0xFF); // Get the first 8 bits of the destination address
    frame[3] = (byte) (BROADCAST_ADDRESS & 0xFF); // Get the next 8 bits

    frame[4] = (byte) ((ourMAC >> 8) & 0xFF); // Get the first 8 bits of our MAC address
    frame[5] = (byte) (ourMAC & 0xFF); // Get the next 8 bits

    long timestamp = buildTimeStamp();
    frame[6] = (byte) ((timestamp >> 56) & 0xFF);
    frame[7] = (byte) ((timestamp >> 48) & 0xFF);
    frame[8] = (byte) ((timestamp >> 40) & 0xFF);
    frame[9] = (byte) ((timestamp >> 32) & 0xFF);
    frame[10] = (byte) ((timestamp >> 24) & 0xFF);
    frame[11] = (byte) ((timestamp >> 16) & 0xFF);
    frame[12] = (byte) ((timestamp >> 8) & 0xFF);
    frame[13] = (byte) (timestamp & 0xFF);

    byte[] header = Arrays.copyOfRange(frame, 6, 13); // Get the header of the frame
    long CRC = calculateCRC(header); // Calculate the CRC value for the frame header

    frame[14] = (byte) (CRC >> 24); // Add the CRC value to the frame
    frame[15] = (byte) (CRC >> 16); // Add the CRC value to the frame
    frame[16] = (byte) (CRC >> 8); // Add the CRC value to the frame
    frame[17] = (byte) (CRC); // Add the CRC value to the frame

    long endTime = theRF.clock();
    timeToBuildPacket = endTime - startTime;

    return frame;
  }

  private void buildBeacon2() {
    byte[] frame = new byte[18];

    long startTime = theRF.clock();
    int sequenceNumber = sequenceNumbers.getOrDefault(destAddress, 0);
    frame[0] = (byte) ((4 << 4) | ((sequenceNumber >> 8) & 0x0F));
    frame[1] = (byte) (sequenceNumber & 0xFF); // Get the next 8 bits

    frame[2] = (byte) ((BROADCAST_ADDRESS >> 8) & 0xFF); // Get the first 8 bits of the destination address
    frame[3] = (byte) (BROADCAST_ADDRESS & 0xFF); // Get the next 8 bits

    frame[4] = (byte) ((ourMAC >> 8) & 0xFF); // Get the first 8 bits of our MAC address
    frame[5] = (byte) (ourMAC & 0xFF); // Get the next 8 bits

    byte[] header = Arrays.copyOfRange(frame, 0, 6); // Get the header of the frame
    long CRC = calculateCRC(header); // Calculate the CRC value for the frame header

    frame[6] = (byte) (CRC >> 24); // Add the CRC value to the frame
    frame[7] = (byte) (CRC >> 16); // Add the CRC value to the frame
    frame[8] = (byte) (CRC >> 8); // Add the CRC value to the frame
    frame[9] = (byte) (CRC); // Add the CRC value to the frame

    long endTime = theRF.clock();
    timeToBuildPacket = endTime - startTime;
  }

}
