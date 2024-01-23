/**
 * file to test code independently
 * 
 * @author Sumneet, Tyler
 */
public class test {
  public static void main(String[] args) {
    byte b = 00000000;
    b = (byte) (b | (1 << 4));
    System.out.println(b);
  }
}
