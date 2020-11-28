package spade.filter;

import spade.core.AbstractEdge;
import spade.core.AbstractFilter;
import spade.core.AbstractVertex;
import spade.edge.prov.WasDerivedFrom;
import spade.reporter.StitchReporter;
import spade.vertex.prov.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
		/*String type = incomingVertex.getAnnotation("object_type");
		String sender = incomingVertex.getAnnotation("sender");
		String receiver = incomingVertex.getAnnotation("receiver");
		String seq = incomingVertex.getAnnotation("seq");
		String content = incomingVertex.getAnnotation("content");

		switch (type){
			case "packet":
				break;
			case "packet_content":
				break;
			default:
				putInNextFilter(incomingVertex);
				return;
		}

		if(content == null || seq == null){
			// This packet is missing the info we need to stitch it
			logger.log(Level.WARNING, "WARNING! IP packet detected without packet contents :(");

			putInNextFilter(incomingVertex);
			return;
		}

		String id = sender + receiver + seq + content;

		if(connections.containsKey(id)){
			logger.log(Level.INFO, "SUCCESS! We found something to stitch :)");
			if(StitchReporter.isLaunched()){
				logger.log(Level.WARNING, "WARNING! StitchReporter is not running so PANAMA cant send stitches");
			} else {
				// TODO we need to know which vertex is the sender and which is the receiver to build the edge
				// StitchReporter.getInstance().putEdge(new WasInformedBy())
			}
		} else {
			connections.put(id, incomingVertex);
		}*/
		putInNextFilter(incomingVertex);
	}

	@Override
	public void putEdge(AbstractEdge incomingEdge){
		Packet p = null;
		switch (incomingEdge.getAnnotation("relation_type")){
			case "packet_content":
				AbstractVertex packet = incomingEdge.getChildVertex();
				AbstractVertex packet_content = incomingEdge.getParentVertex();
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
	private void findMatchingPackets(Packet hint){
		Packet toRemove = null;
		for (Packet p:
			 matchablePackets) {
			if(hint.matchesWith(p)){
				// We found a match!!!!!!
				// This is amazing :)))
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

	private void commitStitch(Packet sent, Packet received){
		if(StitchReporter.isLaunched()){
			logger.log(Level.WARNING, "WARNING! StitchReporter is not running so PANAMA cant send stitches");
		} else {
			// TODO we need to know which vertex is the sender and which is the receiver to build the edge
			if(sent.packet instanceof Entity && received.packet instanceof Entity) {
				WasDerivedFrom edge = new WasDerivedFrom((Entity) sent.packet, (Entity) received.packet);
				edge.addAnnotation("relation_type", "transfer_packet");
				edge.addAnnotation("is_panama", "true");
			} else {
				logger.log(Level.WARNING, "WARNING! NetworkFilter is observing that packets are not typed as Entity");
			}
		}
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
			this.packetContent = content;
		}

		public void setPacketContent(String packet_content) {
			this.packetContent = packet_content;
		}

		public void setDirection(PacketDirection direction) {
			this.direction = direction;
		}

		public boolean complete(){
			return packetContent != null && direction != PacketDirection.UNKNOWN;
		}

		public boolean faulty(){
			return complete() && (packet.getAnnotation("sender") == null
					|| packet.getAnnotation("receiver") == null);
		}

		public String getPacketId() {
			return packet.id();
		}

		public void mergeWith(Packet p){
			if(p.getPacketId().equals(getPacketId())){
				if(this.direction == PacketDirection.UNKNOWN){
					this.direction = p.direction;
				} else {
					this.packetContent = p.packetContent;
				}
				if(!p.complete()){ // mutual updates
					p.mergeWith(this);
				}
			}
		}

		public boolean matchesWith(Packet p){
			return complete() && p.complete() && !faulty() && !p.faulty()
					&& packet.getAnnotation("sender").equals(p.packet.getAnnotation("sender"))
					&& packet.getAnnotation("receiver").equals(p.packet.getAnnotation("receiver"))
					&& packetContent.equals(p.packetContent)
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
