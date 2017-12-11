package main.util;

public class AckPacket {

    int ackNum;

    public AckPacket(int ackNum) {
        this.ackNum = ackNum;
    }

    public int getAckNum() {
        return ackNum;
    }

    public static byte[] serialize(AckPacket packet) {
        return Integer.toString(packet.ackNum).getBytes();
    }

    public static AckPacket deserialize(byte[] data) {
        return new AckPacket(Integer.parseInt(new String(data)));
    }

}
