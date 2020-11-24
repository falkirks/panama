package spade.filter;

import spade.core.AbstractEdge;
import spade.core.AbstractFilter;
import spade.core.AbstractVertex;
import spade.reporter.StitchReporter;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetworkFilter extends AbstractFilter{

	private final Logger logger = Logger.getLogger(this.getClass().getName());

	private Map<String, AbstractVertex> connections = new HashMap<>();

	// arguments -> anno_key1=anno_value1 anno_key2=anno_value2 ...
	public boolean initialize(String arguments){
		return true;
	}

	@Override
	public void putVertex(AbstractVertex incomingVertex){
		String sender = incomingVertex.getAnnotation("cf:sender");
		String receiver = incomingVertex.getAnnotation("cf:receiver");
		String seq = incomingVertex.getAnnotation("cf:seq");
		String content = incomingVertex.getAnnotation("cf:content");

		if(sender == null || receiver == null){
			// This is not an IP vertex, we do nothing here
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
		}
		putInNextFilter(incomingVertex);
	}

	@Override
	public void putEdge(AbstractEdge incomingEdge){
		// We don't care about edges
		putInNextFilter(incomingEdge);
	}
}
