package wifi;

/**
 * A shared state file that lets our send and thread receive threads
 * get access to any updates. 
 * 
 * @author Sumneet Brar and Tyler Higashihara
 */

public class SharedState {
  // sharing news of an ACK
  public static volatile boolean receiveThreadReceivedAnACK = false;
  public static volatile int sequenceNumber;
  public static volatile short sourceAddress; // source address of the ACK

  // command method
  public static volatile int debugLevel;
  public static volatile int slotSelection;
  public static volatile long beaconInterval;

  // status method
  public static volatile int state;

  // offset
  public static volatile long offset;
  
}
