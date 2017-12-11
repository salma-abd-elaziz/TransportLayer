package main.server;

import main.util.AckPacket;
import main.util.DataPacket;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;

import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestHandler {

	// @@@
	private Random r;
	private int bufferSizeLimit;
	private AtomicInteger bufferSize;
	private double plp;

	private int port;
	private boolean done;
	private boolean stopAndWait;
	private InetAddress ip;
	private Set<String> requests;
	private DatagramSocket socket;
	private CountDownLatch received;
	private Queue<Integer> waitedAcks;
	private Map<Integer, CountDownLatch> pendingPackets;

	private static final int MAX_SIZE = 1024;

	RequestHandler(InetAddress ip, int port, boolean stopAndWait, int bufferSizeLimit, int seedValue, double plp) {

		// @@@
		this.r = new Random();
		this.r.setSeed(seedValue);
		this.bufferSizeLimit = bufferSizeLimit;
		this.bufferSize = new AtomicInteger(8);
		this.plp = plp;

		this.ip = ip;
		this.port = port;
		this.done = false;
		this.stopAndWait = stopAndWait;
		this.waitedAcks = new LinkedList<>();
		this.received = new CountDownLatch(1);
		this.pendingPackets = new ConcurrentHashMap<>();
		this.requests = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

		try {
			this.socket = new DatagramSocket();
		} catch (SocketException e) {
			System.err.println("Failure: " + e.getMessage());
		}

		System.out.println("Connection to: " + ip.getHostAddress() + ":" + port + " is initialized.");
	}

	private int stopAndWait(FileInputStream fileStream) throws InterruptedException, IOException {
		boolean receivedPacket;
		byte[] content = new byte[MAX_SIZE];
		int count = 0, length = 0, seqNum = 0;

		while ((length = fileStream.read(content)) != -1) {
			receivedPacket = false;
			received = new CountDownLatch(1);
			byte[] serializedData = DataPacket.serialize(new DataPacket(seqNum, content, length));

			while (!receivedPacket) {
				boolean packetToLose = false;

				if (toLose()) {
					packetToLose = true;
				}

				if (!packetToLose) {
					send(serializedData, serializedData.length);
				}
				receivedPacket = received.await(25, TimeUnit.MILLISECONDS);

				if (receivedPacket) {
					for (String request : requests) {
						requests.remove(request);

						AckPacket ack = AckPacket.deserialize(request.getBytes());
						if (ack.getAckNum() != seqNum + length) {
							receivedPacket = false;
						}
					}
				}
			}

			count++;
			seqNum += length;
		}

		done = true;
		fileStream.close();

		return count;
	}

	private void sendPacket(byte[] serializedData, CountDownLatch acked, int ackNum) {
		boolean receivedPacket = false;

		while (!receivedPacket) {
			boolean packetToLose = false;

			if (toLose()) {
				packetToLose = true;
			}

			if (!packetToLose) {
				send(serializedData, serializedData.length);
			}
			
			try {
				receivedPacket = acked.await(25, TimeUnit.MILLISECONDS);
				if (!receivedPacket) {
					bufferSize.set(1);
				}
			} catch (InterruptedException e) {
				System.err.println("Failure: " + e.getMessage());
			}
		}
	}

	private int selectiveRepeat(FileInputStream fileStream) throws InterruptedException, IOException {
		byte[] content = new byte[MAX_SIZE];
		int count = 0, length = 0, seqNum = 0;

		ExecutorService executor = Executors.newFixedThreadPool(bufferSize.get() + 1);
		ScheduledExecutorService reporterExecutor = Executors.newScheduledThreadPool(1);
		reporterExecutor.scheduleAtFixedRate(() -> System.out.println("Buffer size : " + bufferSize), 0, 100,
				TimeUnit.MILLISECONDS);

		executor.submit(() -> {
			CountDownLatch dummy = new CountDownLatch(1);
			while (true) {
				received.await();

				for (String request : requests) {
					requests.remove(request);

					AckPacket ack = AckPacket.deserialize(request.getBytes());

					pendingPackets.getOrDefault(ack.getAckNum(), dummy).countDown();

					// @@@
					if (bufferSize.get() < bufferSizeLimit) {
						bufferSize.getAndIncrement();
					}
				}

				received = new CountDownLatch(1);
			}
		});

		while ((length = fileStream.read(content)) != -1) {
			byte[] serializedData = DataPacket.serialize(new DataPacket(seqNum, content, length));

			count++;
			seqNum += length;
			int ackNum = seqNum;

			waitedAcks.add(ackNum);
			pendingPackets.put(ackNum, new CountDownLatch(1));
			executor.submit(() -> sendPacket(serializedData, pendingPackets.get(ackNum), ackNum));

			checkDone(false);
		}

		while (!waitedAcks.isEmpty()) {
			checkDone(true);
		}

		done = true;
		executor.shutdown();
		reporterExecutor.shutdown();

		return count;
	}

	private void checkDone(boolean finalCheck) throws InterruptedException {
		if (waitedAcks.size() >= bufferSize.get() || finalCheck) {
			pendingPackets.get(waitedAcks.peek()).await();

			if (!waitedAcks.isEmpty() && pendingPackets.containsKey(waitedAcks.peek())
					&& pendingPackets.get(waitedAcks.peek()).getCount() == 0) {
				pendingPackets.remove(waitedAcks.poll());
			}
		}
	}

	public void handleRequest() {
		try {
			received.await();
		} catch (InterruptedException e) {
			System.err.println("Failure: " + e.getMessage());
			return;
		}

		for (String request : requests) {
			requests.remove(request);
			System.out.println(request);

			File file = new File(request.split(" ")[1]);

			try {
				FileInputStream fileStream = new FileInputStream(file);

				System.out.println(
						"Sent chunks = " + (stopAndWait ? stopAndWait(fileStream) : selectiveRepeat(fileStream)));

				fileStream.close();
			} catch (IOException | InterruptedException e) {
				System.err.println("Failure: " + e.getMessage());
			}
			return;
		}
	}

	private void send(byte[] data, int length) {
		DatagramPacket sendPacket = new DatagramPacket(data, length, ip, port);
		try {
			socket.send(sendPacket);
		} catch (IOException e) {
			System.err.println("Failure: " + e.getMessage());
			return;
		}
	}

	public void addRequest(byte[] data, int size) {
		if (!done) {
			requests.add(new String(data).substring(0, size));
			received.countDown();
		}
	}

	// @@@
	private boolean toLose() {
		return r.nextDouble() < plp;
	}

}