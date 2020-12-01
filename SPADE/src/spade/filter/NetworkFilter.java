package spade.filter;

import spade.core.*;
import spade.reporter.StitchReporter;

import java.math.BigInteger;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetworkFilter extends AbstractFilter{
	private final Logger logger = Logger.getLogger(this.getClass().getName());

	private Map<String, Packet> inflightPackets = new HashMap<>();

	private List<Packet> matchablePackets = new ArrayList<>();

	// arguments -> anno_key1=anno_value1 anno_key2=anno_value2 ...
	public boolean initialize(String arguments){
		logger.log(Level.INFO, "panama says hello --> hi there :))))");
		return true;
	}

	@Override
	public void putVertex(AbstractVertex incomingVertex){
		// We only discover vertexes using their edges
		putInNextFilter(incomingVertex);
	}

	@Override
	public void putEdge(AbstractEdge incomingEdge){

		Packet p = null;
		switch (incomingEdge.getAnnotation("relation_type")){
			case "packet_content":
				AbstractVertex packet = incomingEdge.getChildVertex();
				AbstractVertex packet_content = incomingEdge.getParentVertex();
				logger.log(Level.INFO, packet_content.getCopyOfAnnotations().toString());
				p = new Packet(packet, packet_content.getAnnotation("content"));
				break;
			case "send_packet":
				p = new Packet(incomingEdge.getChildVertex(), PacketDirection.SENT);
				break;
			case "receive_packet":
				p = new Packet(incomingEdge.getParentVertex(), PacketDirection.RECEIVED);
				break;
		}

		if(p != null){
			if(inflightPackets.containsKey(p.getPacketId())){
				inflightPackets.get(p.getPacketId()).mergeWith(p);
				if(p.complete()){
					logger.log(Level.INFO, "panama completed one packet! ===> " + p);
					matchablePackets.add(p);
					inflightPackets.remove(p.getPacketId());
				}
			} else {
				inflightPackets.put(p.getPacketId(), p);
			}
			findMatchingPackets(p);
		}

		// We don't care about edges
		putInNextFilter(incomingEdge);
	}

	/**
	 * Finds packets to collapse in the data structure
	 */
	private synchronized void findMatchingPackets(Packet hint){
		Packet toRemove = null;
		for (Packet p:
			 matchablePackets) {
			if(hint.matchesWith(p)){
				// We found a match!!!!!!
				// This is amazing :)))
				logger.log(Level.INFO, "NetworkFilter found a match between " + hint + " and " + p);
				Packet sent = hint.direction == PacketDirection.SENT ? hint : p;
				Packet received = hint.direction == PacketDirection.RECEIVED ? hint : p;
				commitStitch(sent, received);
				toRemove = p;
			}
		}
		if(toRemove != null) {
			matchablePackets.remove(toRemove);
			matchablePackets.remove(hint);
		}
	}

	private synchronized void commitStitch(Packet sent, Packet received){
		if(!StitchReporter.isLaunched()){
			logger.log(Level.WARNING, "WARNING! StitchReporter is not running so PANAMA cant send stitches");
		} else {
			logger.log(Level.INFO, sent.packet.getClass().getCanonicalName() + " and " + received.packet.getClass().getCanonicalName());
			Edge e = new Edge(sent.packet, received.packet);
			e.addAnnotation("relation_type", "transfer_packet");
			e.addAnnotation("is_panama", "true");
			StitchReporter.getInstance().reportStitch(e);
			logger.log(Level.INFO, "NetworkFilter sending stitch");
		}
	}

	public static int hex2decimal(Character c) {
		String digits = "0123456789abcdef";
		return  digits.indexOf(c);
	}


	private enum PacketDirection{
		UNKNOWN, SENT, RECEIVED;
	}
	private class Packet{
		private AbstractVertex packet;
		private String packetContent;
		private PacketDirection direction;

		public Packet(AbstractVertex packet, PacketDirection direction) {
			this.packet = packet;
			this.direction = direction;
			this.packetContent = null;
		}

		public Packet(AbstractVertex packet, String content) {
			this.packet = packet;
			this.direction = PacketDirection.UNKNOWN;
			setPacketContent(content);
		}

		public void setPacketContent(String packet_content) {
			byte[] decoded = Base64.getDecoder().decode(packet_content);
			String hex = String.format("%040x", new BigInteger(1, decoded));
			switch (direction){
				case UNKNOWN:
					this.packetContent = packet_content; // we can't make this work yet
					return;
				case SENT:
					hex = hex.substring(hex.indexOf("45")); // FIXME we are missing ethernet so we just need to try our best here
					break;
				case RECEIVED:
					hex =  hex.substring(hex.indexOf("08004")); // we have ethernet here so we are safe
					hex = hex.substring(hex.indexOf("4"));
					break;
			}
			int headerLength = hex2decimal(hex.charAt(1));
			int packetStartOffset = headerLength * 4 * 2; // the headerlength measures the number of 32 bit words so four bytes in each and * 2 because we are at base 16

			char[] len = hex.substring(4, 8).toCharArray();
			int length = (hex2decimal(len[0]) * (16^3))
					+ (hex2decimal(len[1]) * (16^2))
					+ (hex2decimal(len[2]) * 16)
					+ hex2decimal(len[3]); // sorry for reinventing the wheel


			logger.log(Level.INFO, "We found an internal packet len of:" +  length + " and the packet was " + hex + "and it was " + direction);

			hex = hex.substring(0, length * 2);

			String protocol = hex.substring(9 * 2, (9 * 2) + 2);

			hex = hex.substring(packetStartOffset);

			switch(protocol){
				case "11":
					logger.log(Level.INFO, "UDP packet!");
					char[] udpLen = hex.substring(8, 12).toCharArray();
					int udpLength = (hex2decimal(udpLen[0]) * (16^3))
							+ (hex2decimal(udpLen[1]) * (16^2))
							+ (hex2decimal(udpLen[2]) * 16)
							+ hex2decimal(udpLen[3]); // sorry for reinventing the wheel

					hex = hex.substring(16, udpLength * 2); // get just the UDP content
					break;
			}


			logger.log(Level.INFO, "FINAL! We found an internal packet len of:" +  length + " and the packet was " + hex);
			this.packetContent = hex;
		}

		public void setDirection(PacketDirection direction) {
			boolean wasComplete = complete();
			this.direction = direction;

			if(!wasComplete) {
				setPacketContent(packetContent);
			}
		}

		public boolean complete(){
			return packetContent != null && direction != PacketDirection.UNKNOWN;
		}

		public boolean faulty(){
			return complete() && (packet.getAnnotation("sender") == null
					|| packet.getAnnotation("panama_ipid") == null
					|| packet.getAnnotation("receiver") == null);
		}

		public String getPacketId() {
			return packet.id();
		}

		public void mergeWith(Packet p){
			if(p.getPacketId().equals(getPacketId())){
				if(this.direction == PacketDirection.UNKNOWN){
					setDirection(p.direction);
				} else {
					if(p.complete()) packetContent = p.packetContent;
					else setPacketContent(p.packetContent);
				}
				if(!p.complete()){ // mutual updates
					p.mergeWith(this);
				}
			}
		}

		public boolean matchesWith(Packet p){
			if(p.faulty() || faulty()){
				logger.log(Level.WARNING, "Panama had detected a faulty packet cluster --> this could be caused by a CamFlow inconsistency or more likely that you are not feeding data through PanamaCamFlow");
				return false;
			}
			return complete() && p.complete()
					&& packet.getAnnotation("sender").equals(p.packet.getAnnotation("sender")) // senders must match
					&& packet.getAnnotation("receiver").equals(p.packet.getAnnotation("receiver")) // receivers must match
					&& packetContent.equals(p.packetContent) // packet content must match (or fuzzy match?)
					&& packet.getAnnotation("panama_ipid").equals(p.packet.getAnnotation("panama_ipid")) // packet ids must match
					&& direction != p.direction; // one direction must be recieve and the other send
		}

		@Override
		public String toString() {
			return "Packet{" +
					"sender=" + packet.getAnnotation("sender") +
					", receiver=" + packet.getAnnotation("receiver") +
					", packetContent='" + packetContent + '\'' +
					", direction=" + direction +
					'}';
		}
	}
}
