package main.server;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class Server {

	private static final int MAX_SIZE = 2048;
	private static final int THREADS_NUMBER = 128;
	private static final boolean STOP_WAIT = true;
	// @@@
	private static final String FILENAME = "src/main/server/info.txt";
	private static final int PORT_INDEX = 0;
	private static final int WINDOW_SIZE_INDEX = 1;
	private static final int SEED_VALUE_INDEX = 2;
	private static final int PLP_INDEX = 3;

	private static List<String> readFile() {
		List<String> lines = new ArrayList<>();

		try {
			String readValue;
			BufferedReader br = new BufferedReader(new FileReader(FILENAME));
			while ((readValue = br.readLine()) != null) {
				lines.add(readValue);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return lines;
	}

	private void init() {
		List<String> fields = readFile();
		DatagramSocket serverSocket = null;

		try {
			serverSocket = new DatagramSocket(Integer.valueOf(fields.get(PORT_INDEX)));
			System.out.println("Server running on port " + Integer.valueOf(fields.get(PORT_INDEX)));
		} catch (SocketException e) {
			System.err.println("Failure: " + e.getMessage());
			return;
		}

		DatagramPacket receivePacket;
		byte[] receiveData = new byte[MAX_SIZE];
		Map<String, RequestHandler> handlers = new HashMap<>();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS_NUMBER);

		while (true) {
			receivePacket = new DatagramPacket(receiveData, receiveData.length);

			try {
				serverSocket.receive(receivePacket);
			} catch (IOException e) {
				System.err.println("Failure: " + e.getMessage());
				continue;
			}

			// TODO: parallelize.
			int port = receivePacket.getPort();
			InetAddress IPAddress = receivePacket.getAddress();

			String key = IPAddress.getHostAddress() + ":" + port;

			handlers.computeIfAbsent(key, (k) -> {
				double plp = Double.valueOf(fields.get(PLP_INDEX));
				int seedValue = Integer.valueOf(fields.get(SEED_VALUE_INDEX));
				int bufferSizeLimit = Integer.valueOf(fields.get(WINDOW_SIZE_INDEX));

				RequestHandler handler = new RequestHandler(
						IPAddress, port, !STOP_WAIT, bufferSizeLimit, seedValue, plp);

				executor.execute(() -> {
					handler.handleRequest();
					handlers.remove(k);
				});

				return handler;
			});

			handlers.get(key).addRequest(receivePacket.getData(), receivePacket.getLength());
		}
	}

	public static void main(String[] args) {
		(new Server()).init();
	}

}