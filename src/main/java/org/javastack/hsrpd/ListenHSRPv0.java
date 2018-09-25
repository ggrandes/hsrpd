package org.javastack.hsrpd;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.log4j.Logger;

/**
 * @version 2009.10.03
 */
public class ListenHSRPv0 {
	private static final Logger log = Logger.getLogger("HSRP");
	private static final String HSRP_DIR_DEFAULT = System.getProperty("java.io.tmpdir", "/tmp/");
	private static final String HSRP_STATUS = "hsrpd.status";
	private static final String HSRP_SEMAPHORE = "hsrp.";
	private static final int HSRP_ENTRIES_MAX = 256;

	private static String HSRP_DIR = HSRP_DIR_DEFAULT;

	private static LinkedHashMap<String, StructHSRP> hsrpTable = new LinkedHashMap<String, StructHSRP>() {
		private static final long serialVersionUID = 1L;

		// This method is called just after a new entry has been added
		public boolean removeEldestEntry(Map.Entry<String, StructHSRP> eldest) {
			if (size() > HSRP_ENTRIES_MAX) {
				return true;
			}
			return false;
		}
	};

	// Hace el join a un grupo de multicast
	private static MulticastSocket join(String groupName, int port) {
		log.info("Join Multicast[UDP](" + groupName + ":" + port + ")");
		try {
			final MulticastSocket msocket = new MulticastSocket(port);
			final InetAddress group = InetAddress.getByName(groupName);
			msocket.joinGroup(group);
			return msocket;
		} catch (Exception e) {
			log.error("Join Multicast[UDP](" + groupName + ":" + port + ") Exception: " + e, e);
		}
		return null;
	}

	// Lee un paquete UDP multicast
	private static DatagramPacket read(MulticastSocket msocket, byte[] inbuf) {
		try {
			final DatagramPacket packet = new DatagramPacket(inbuf, inbuf.length);
			// Wait for packet
			msocket.receive(packet);
			// Data is now in inbuf
			// int numBytesReceived = packet.getLength();
			return packet;
		} catch (IOException e) {
		}
		return null;
	}

	// Imprime estadisticas
	private static void printStats() throws Exception {
		final StringBuilder sb = new StringBuilder();
		final long now = System.currentTimeMillis();
		synchronized (hsrpTable) {
			for (final StructHSRP v : hsrpTable.values()) {
				final long ts = (v.ts / 1000L);
				final long nows = (now / 1000L);
				final boolean dead = ((ts + v.holdtime()) < nows);
				sb.append(ts).append("\tdead=").append(dead).append("\t").append(v.toString()).append("\n");
				if (dead) {
					final File s1 = new File(HSRP_DIR, HSRP_SEMAPHORE + v.virtIP());
					if (s1.delete()) {
						log.warn("CHANGE STATE (DOWN) VIRTIP=" + v.virtIP());
					}
				}
			}
		}
		final File file = new File(HSRP_DIR, HSRP_STATUS);
		FileOutputStream fos = null;
		PrintStream ps = null;
		try {
			fos = new FileOutputStream(file);
			ps = new PrintStream(fos);
			ps.print(sb.toString());
			ps.flush();
		} finally {
			closeQuiet(ps);
			closeQuiet(fos);
		}
		file.deleteOnExit();
	}

	private static final void closeQuiet(final Closeable c) {
		if (c != null) {
			try {
				c.close();
			} catch (Exception ign) {
			}
		}
	}

	/**
	 * Simple Runner
	 */
	public static void main(final String[] args) throws Exception {
		if (args.length >= 1) {
			HSRP_DIR = args[0];
		}
		Thread.currentThread().setName("HSRPD");
		log.info("Starting...");
		log.info("Directory: " + HSRP_DIR);
		//
		final Thread nTh = new Thread() {
			public void run() {
				log.info("Starting stats thread");
				while (true) {
					try {
						try {
							Thread.sleep(1000);
						} catch (Exception ign) {
						}
						printStats();
					} catch (Exception e) {
						log.error("Exception: " + e, e);
						try {
							Thread.sleep(1000);
						} catch (Exception ign) {
						}
					}
				}
			}
		};
		nTh.setDaemon(true);
		nTh.setName("STATS");
		nTh.start();
		//
		final MulticastSocket msocket = join("224.0.0.2", 1985);
		// msocket.setSoTimeout(1000);
		log.info("Reading...");
		while (true) {
			final byte[] buf = new byte[20];
			try {
				final DatagramPacket packet = read(msocket, buf);
				if (packet == null) {
					// log.info("Skipping...");
					try {
						Thread.sleep(1000);
					} catch (Exception ign) {
					}
					continue;
				}
				final int buflen = packet.getLength();
				if (buflen != 20)
					continue; // HSRPv0 (rfc2281) = 20 bytes (non-standard opcode=3, 16 bytes ignored)
				final StructHSRP hsrp = new StructHSRP(buf, packet.getAddress().getHostAddress());
				if ((hsrp.version() == 0) &&  // v0
						(hsrp.opcode() == 0) &&  // 0-Hello
						(hsrp.state() == 16)) { // 16-Active
					final File s1 = new File(HSRP_DIR, HSRP_SEMAPHORE + hsrp.virtIP());
					if (s1.createNewFile()) {
						log.warn("CHANGE STATE (UP) VIRTIP=" + hsrp.virtIP() + " SRC=" + hsrp.getSrc());
					} else {
						s1.setLastModified(System.currentTimeMillis());
					}
					s1.deleteOnExit();
					synchronized (hsrpTable) {
						hsrpTable.put(hsrp.virtIP() + ":" + hsrp.state(), hsrp);
					}
				}
				if (hsrp.opcode() == 0) { // 0 - Hello
					log.trace(hsrp.toString());
				} else { // 1-Coup / 2-Resign
					log.warn(hsrp.toString());
				}
			} catch (Exception e) {
				log.error("Exception: " + e, e);
				try {
					Thread.sleep(1000);
				} catch (Exception ign) {
				}
			}
			try {
				Thread.yield();
			} catch (Exception e) {
			}
		}
	}

	private static String padr(String txt, int len) {
		return txt + "                                     ".substring(0, len - txt.length());
	}

	/**
	 * HSRPv0: http://www.ietf.org/rfc/rfc2281.txt
	 * Non-RFC: https://github.com/boundary/wireshark/blob/master/epan/dissectors/packet-hsrp.c
	 *
	 * @formatter:off
	 *
	 * Each row are 32 bits (4 octets)
	 *
	 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 * |   Version     |   Op Code     |     State     |   Hellotime   | 0-3
	 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 * |   Holdtime    |   Priority    |     Group     |   Reserved    | 4-7
	 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 * |                      Authentication Data                      | 8-11
	 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 * |                      Authentication Data                      | 12-15
	 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 * |                      Virtual IPv4 Address                     | 16-19
	 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *
	 * @formatter:on
	 *
	 * The standby protocol runs on top of UDP, and uses port number 1985.
	 * Packets are sent to multicast address 224.0.0.2 with TTL 1.
	 */
	static class StructHSRP {
		private byte[] buf;
		private String virtip;
		public final long ts;
		public final String src;

		public int version() {
			return buf[0] & 0xFF;
		}

		public int opcode() {
			return buf[1] & 0xFF;
		}

		public int state() {
			return buf[2] & 0xFF;
		}

		public int hellotime() {
			// HSRP_DEFAULT_HELLOTIME 3
			return buf[3] & 0xFF;
		}

		public int holdtime() {
			// HSRP_DEFAULT_HOLDTIME 10
			return buf[4] & 0xFF;
		}

		public int priority() {
			return buf[5] & 0xFF;
		}

		public int group() {
			return buf[6] & 0xFF;
		}

		public int reserved() {
			return buf[7] & 0xFF;
		}

		private byte[] getBufIP() {
			final byte[] ip = new byte[4];
			System.arraycopy(buf, 16, ip, 0, 4);
			return ip;
		}

		public String virtIP() {
			return virtip;
		}

		public String stateString() {
			switch (state()) {
				case 0:
					return "Initial";
				case 1:
					return "Learn";
				case 2:
					return "Listen";
				case 4:
					return "Speak";
				case 8:
					return "Standby";
				case 16:
					return "Active";
				default:
					return "Unknown";
			}
		}

		public StructHSRP(final byte[] buf, final String src) {
			this.buf = buf;
			this.src = src;
			this.ts = System.currentTimeMillis();
			try {
				this.virtip = InetAddress.getByAddress(getBufIP()).getHostAddress();
			} catch (UnknownHostException uhe) {
				this.virtip = "0.0.0.0";
			}
		}

		public String getSrc() {
			return src;
		}

		public String toString() {
			final StringBuilder sb = new StringBuilder();
			sb.append("v").append(version());
			sb.append(" op=").append(opcode());
			sb.append(" grp=").append(group());
			sb.append(" hello=").append(hellotime());
			sb.append(" hold=").append(holdtime());
			sb.append(" prio=").append(priority());
			if (priority() < 100) {
				sb.append(" ");
			}
			if (priority() < 10) {
				sb.append(" ");
			}
			sb.append(" ").append(padr(stateString(), 7));
			sb.append(" virt=").append(padr(virtIP(), 15));
			sb.append(" src=").append(getSrc());
			return sb.toString();
		}
	}
}
