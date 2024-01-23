package wifi;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.CRC32;

import rf.RF;

/**
 * * This implements the receive thread with the 802.11~
 * finite state diagram
 * 
 * @author Sumneet Brar and Tyler Higashihara
 */
public class ReceiveThread implements Runnable {

  private RF theRF;
  private ArrayBlockingQueue<byte[]> queue;
  private short ourMAC;

  private int sequenceNumber;
  private int previousSequenceNumber = -1;

  private short sourceAddress;
  private short ackDestinationAddress;

  private final double SIFS = RF.aSIFSTime; // SIFS time
  private PrintWriter output; // The output stream we'll write to

  public ReceiveThread(ArrayBlockingQueue<byte[]> queue, RF theRF, short ourMAC, PrintWriter output) {
    this.queue = queue;
    this.theRF = theRF;
    if (ourMAC < 0 || ourMAC > 0xFFFF) {
      SharedState.state = 8;
    } else
      this.ourMAC = ourMAC;
    this.output = output;
  }

  @Override
  public void run() {
    byte[] receivedPacket = theRF.receive();
    long startTime = theRF.clock();

    // detemine if the packet is an ACK or a data frame
    int controlField = (receivedPacket[0] >> 5) & 0x07;

    if (queue.size() <= 4 || controlField == 010) {

      sourceAddress = (short) ((receivedPacket[4] << 8) | (receivedPacket[5] & 0xFF));
      if (sourceAddress < 0 || sourceAddress > 0xFFFF) {
        SharedState.state = 8;
      } else
        SharedState.sourceAddress = sourceAddress;

      sequenceNumber = ((receivedPacket[0] & 0x0F) << 8) | (receivedPacket[1] & 0xFF);
      ackDestinationAddress = (short) ((receivedPacket[4] << 8) | (receivedPacket[5] & 0xFF));

      // address selectivity
      short destinationAddress = (short) ((receivedPacket[2] << 8) | (receivedPacket[3] & 0xFF));

      // if the packet is intended for us or it's a broadcast, add it to the queue.
      // otherwise, ignore it
      if (destinationAddress == ourMAC || destinationAddress == 0xFFFF) {

        // if it is an ACK, build an ACK and share it with the send thread
        if (controlField == 001) {
          SharedState.receiveThreadReceivedAnACK = true;
          SharedState.sequenceNumber = sequenceNumber;
        }

        // if it is a data frame, add it to the queue
        else if (controlField == 000) {
          // check packet for damage
          int dataStart = 6; // start of the data
          int dataEnd = receivedPacket.length - 4;

          byte[] dataBytes = Arrays.copyOfRange(receivedPacket, dataStart, dataEnd + 1);
          String recievedData = new String(dataBytes, StandardCharsets.UTF_8);

          // check CRC
          long CRC = calculateCRC(recievedData);
          long receivedCRC = (receivedPacket[receivedPacket.length - 4] << 24)
              | ((receivedPacket[receivedPacket.length - 3] & 0xFF) << 16)
              | ((receivedPacket[receivedPacket.length - 2] & 0xFF) << 8)
              | (receivedPacket[receivedPacket.length - 1] & 0xFF);

          if (CRC != receivedCRC) {} // packet was damaged, do not accept - wait for retransmision          
          else if (sequenceNumber != previousSequenceNumber)
            if (sequenceNumber != (previousSequenceNumber + 1)) {
              output.println("LinkLayer: There is a gap in the sequence numbers.");
              queue.add(receivedPacket);
              // should we extrapolate data and send that or the whole packet?
            } else {
              queue.add(receivedPacket);
              previousSequenceNumber = sequenceNumber;
            }
          // if it is not a broadcast, send an ACK back to the sender
          if (destinationAddress == ourMAC)
            sendACK(buildACK()); // method chaining!
        }

        else if (controlField == 010) {
          long timestamp = 0;
          for (int i = 0; i < 8; i++) {
            timestamp = (timestamp << 8) | (receivedPacket[6 + i] & 0xFF);
          }
          long endTime = theRF.clock();
          long timeElapsed = endTime - startTime;
          timestamp = timestamp + timeElapsed;

          // compare final result to our local time
          long localTime = theRF.clock();
          if (timestamp > localTime) {
            SharedState.offset = timestamp - localTime;
          }
        }
      }
    }
  }

  public byte[] buildACK() {
    byte[] frame = new byte[10];

    frame[0] = (byte) ((2 << 4) | ((sequenceNumber >> 8) & 0x0F));
    frame[1] = (byte) (sequenceNumber & 0xFF); // Get the next 8 bits

    frame[2] = (byte) ((ackDestinationAddress >> 8) & 0xFF); // Get the first 8 bits of the destination address
    frame[3] = (byte) (ackDestinationAddress & 0xFF); // Get the next 8 bits

    frame[4] = (byte) ((ourMAC >> 8) & 0xFF); // Get the first 8 bits of our MAC address
    frame[5] = (byte) (ourMAC & 0xFF); // Get the next 8 bits

    byte[] header = Arrays.copyOfRange(frame, 0, 6); // Get the header of the frame
    long CRC = calculateCRC(new String(header, StandardCharsets.UTF_8)); // Calculate the CRC value for the frame header

    frame[6] = (byte) (CRC >> 24); // Add the CRC value to the frame
    frame[7] = (byte) (CRC >> 16); // Add the CRC value to the frame
    frame[8] = (byte) (CRC >> 8); // Add the CRC value to the frame
    frame[9] = (byte) (CRC); // Add the CRC value to the frame

    return frame;
  }

  private long calculateCRC(String data) {
    CRC32 crc = new CRC32();
    crc.update(data.getBytes());
    return crc.getValue();
  }

  public void sendACK(byte[] frame) {
    try {
      Thread.sleep((long) SIFS);
      theRF.transmit(frame); // should encounter no collisions
    } catch (InterruptedException e) {
      SharedState.state = 2;
    }
  }

}
