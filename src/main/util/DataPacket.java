package main.util;

import java.io.IOException;
import java.io.ByteArrayOutputStream;

import java.util.Arrays;

public class DataPacket {

    private int seqNum;
    private int length;
    private byte[] data;

    private static final int SEQNUM_LENGTH = 10;

    public DataPacket(int seqNum, byte[] data, int length) {
        this.data = data;
        this.length = length;
        this.seqNum = seqNum;
    }

    public int getLength() {
        return length;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public byte[] getData() {
        return data;
    }

    public static byte[] serialize(DataPacket packet) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream( );
        try {
            outputStream.write(String.format("%0" + SEQNUM_LENGTH + "d", packet.getSeqNum()).getBytes());
            outputStream.write(Arrays.copyOfRange(packet.getData(), 0, packet.getLength()));
        } catch (IOException e) {
            System.err.println("Failure!");
        }
        return outputStream.toByteArray();
    }

    public static DataPacket deserialize(byte[] data, int length) {
        return new DataPacket(
                Integer.parseInt(new String(Arrays.copyOfRange(data, 0, SEQNUM_LENGTH))),
                Arrays.copyOfRange(data, SEQNUM_LENGTH, length),
                length - SEQNUM_LENGTH);
    }

}