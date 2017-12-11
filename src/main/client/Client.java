package main.client;
	
import java.io.*;

import java.net.*;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

import main.util.AckPacket;
import main.util.DataPacket;

public class Client {

	// @@@
	private static final String FILENAME = "src/main/client/info.txt";
	private static final int IP_INDEX = 0;
    private static final int SERVER_PORT_INDEX = 1;
    private static final int CLIENT_PORT_INDEX = 2;
    private static final int FILE_NAME_INDEX = 3;

    private static final int MAX_SIZE = 2048;
    private static final int TIMEOUT = 5 * 1000;

    private int port;
    private int count;
    private int expectedSeqNum;
    private InetAddress IPAddress;
    private ExecutorService executor;
    private DatagramSocket clientSocket;
    private Map<Integer, DataPacket> receivedPackets;
    
    private static List<String> readFile() {
        List<String> lines = new ArrayList<>();
        String readValue = "";

        try {
            BufferedReader br = new BufferedReader(new FileReader(FILENAME));
            while ((readValue = br.readLine()) != null) {
                lines.add(readValue);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return lines;
    }
    
    private void stopAndWait(FileOutputStream fileStream, DatagramPacket receivePacket) throws IOException {
        DataPacket packet = DataPacket.deserialize(receivePacket.getData(), receivePacket.getLength());

        if (packet.getSeqNum() == expectedSeqNum) {
            count++;
            expectedSeqNum += packet.getLength();
            fileStream.write(packet.getData(), 0, packet.getLength());
        }

        byte[] sendData = AckPacket.serialize(new AckPacket(expectedSeqNum));
        clientSocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));
    }

    private synchronized void writePackets(FileOutputStream fileStream) {
        while (receivedPackets.containsKey(expectedSeqNum)) {
            DataPacket packet = receivedPackets.remove(expectedSeqNum);
            expectedSeqNum += packet.getLength();
            count++;

            try {
                fileStream.write(packet.getData(), 0, packet.getLength());
            } catch (IOException e) {
                System.err.println("Failure: " + e.getMessage());
            }
        }
    }

    private void selectiveRepeat(FileOutputStream fileStream, DatagramPacket receivePacket) throws IOException {
        DataPacket packet = DataPacket.deserialize(receivePacket.getData(), receivePacket.getLength());

        receivedPackets.computeIfAbsent(
                packet.getSeqNum(), (k) -> packet.getSeqNum() < expectedSeqNum ? null : packet);

//        System.out.println("Got = " + packet.getSeqNum() + ", expected = " + expectedSeqNum);
        
        byte[] sendData = AckPacket.serialize(new AckPacket(packet.getSeqNum() + packet.getLength()));
        clientSocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));

        executor.submit(() -> writePackets(fileStream));
    }

    private void init() {
        List<String> fields = readFile();
    
        try {
            clientSocket = new DatagramSocket(Integer.valueOf(fields.get(CLIENT_PORT_INDEX)));
            System.out.println("Client running on port " + Integer.valueOf(fields.get(CLIENT_PORT_INDEX)));
        } catch (SocketException e) {
            System.err.println("Failure: " + e.getMessage());
            return;
        }

        try {
            port = Integer.valueOf(fields.get(SERVER_PORT_INDEX));
            IPAddress = InetAddress.getByName(fields.get(IP_INDEX));
        } catch (UnknownHostException e) {
            System.err.println("Failure: " + e.getMessage());
            clientSocket.close();
            return;
        }

        FileOutputStream fileStream = null;
        byte[] receiveData = new byte[MAX_SIZE];
        receivedPackets = new ConcurrentHashMap<>();
        String sentence = "GET " + fields.get(FILE_NAME_INDEX);
        
        executor = Executors.newSingleThreadExecutor();
        
        long start = 0;
        try {
            clientSocket.send(
                    new DatagramPacket(
                            sentence.getBytes(), sentence.getBytes().length, IPAddress, port));

            System.out.println("Sent get request");

            expectedSeqNum = count = 0;
            fileStream = new FileOutputStream(new File("temp_" + fields.get(FILE_NAME_INDEX)));
            
            start = System.currentTimeMillis();
            while (true) {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                clientSocket.setSoTimeout(TIMEOUT);
                clientSocket.receive(receivePacket);

                if (true) {
                    stopAndWait(fileStream, receivePacket);
                } else {
                    selectiveRepeat(fileStream, receivePacket);
                }
            }

        } catch (SocketTimeoutException e) {
            // @@@ 5 seconds of timeout.
            System.out.println("Time elapsed = " + (System.currentTimeMillis() - start - TIMEOUT)/1000.0 + " seconds");
            System.out.println("Received chunks = " + count);
            System.out.println("Finished");
        } catch (IOException e) {
            System.err.println("Failure: " + e.getMessage());
        }

        try {
            fileStream.close();
        } catch (IOException e) {
            System.out.println("No file to close!");
        }
        executor.shutdown();
        clientSocket.close();
    }

    public static void main(String[] args) {
        (new Client()).init();
    }

}