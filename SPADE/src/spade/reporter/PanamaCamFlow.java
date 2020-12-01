/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2016 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
package spade.reporter;

import spade.core.AbstractEdge;
import spade.core.AbstractVertex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CamFlow + PANAMA reporter for SPADE
 * 
 * This is a very thin layer on top of CamFlow that just tweaks networking id
 */
public class PanamaCamFlow extends CamFlow{

	private Map<String, List<AlternateIdentifier>> idMapping = new HashMap<>();

	private List<AbstractEdge> waitingEdges = new ArrayList<>();


	private final Logger logger = Logger.getLogger(this.getClass().getName());

	@Override
	protected Logger getLogger(){
		return logger;
	}

	@Override
	public synchronized boolean launch(String arguments){
		getLogger().log(Level.INFO, "launching PanamaCamFlow reporter");
		return super.launch(arguments);
	}

	@Override
	protected void putVertexToBuffer(AbstractVertex vertex) {
		String id = vertex.id();
		if(vertex.getAnnotation("object_type").equals("packet")){
			AlternateIdentifier newId = getAlternateIdForVertex(vertex);
			vertex.setId(newId.toString()); // rewrite the ID to be unique :)
			vertex.addAnnotation("panama_ipid", id); // keep the old id around so we can use it later
			getLogger().log(Level.INFO, "rewrote " + id + " to be " + newId);

			findAndCommitCandidateEdges(); // we may now have edges ready to commit
		}

		super.putVertexToBuffer(vertex);
	}

	@Override
	protected void putEdgeToBuffer(AbstractEdge edge) {
		if(edge.getAnnotation("relation_type").equals("packet_content")
				|| edge.getAnnotation("relation_type").equals("send_packet")
				|| edge.getAnnotation("relation_type").equals("receive_packet")){
			waitingEdges.add(edge); // we need to hold this edge here until we have enough info to rewrite it
			findAndCommitCandidateEdges();
		} else { // this isnt an edge we care about so handle as normal
			super.putEdgeToBuffer(edge);
		}
	}

	private AlternateIdentifier closeEnoughClosest(String jiffies, String id){
		List<AlternateIdentifier> bucket = idMapping.get(id);
		for (AlternateIdentifier aId : bucket){
			if(aId.isCloseTo(jiffies)){
				return aId; //TODO get the best one here
			}
		}
		return null;
	}

	private void findAndCommitCandidateEdges(){
		List<AbstractEdge> toRemove = new ArrayList<>();
		for (AbstractEdge e : waitingEdges){
			switch (e.getAnnotation("relation_type")){
				case "packet_content":
				case "send_packet":
					AlternateIdentifier id = closeEnoughClosest(e.getAnnotation("jiffies"), e.getChildVertex().id());
					if(id != null){
						getLogger().log(Level.INFO, "found an id match for a");
						e.setChildVertex(id.getVertex());
						toRemove.add(e);
						super.putEdgeToBuffer(e);
					}
					break;
				case "receive_packet":
					AlternateIdentifier id2 = closeEnoughClosest(e.getAnnotation("jiffies"), e.getParentVertex().id());
					if(id2 != null){
						getLogger().log(Level.INFO, "found an id match for b");
						e.setParentVertex(id2.getVertex());
						toRemove.add(e);
						super.putEdgeToBuffer(e);
					}
					break;
			}
		}
		waitingEdges.removeAll(toRemove);
	}

	@Override
	protected boolean printStats(boolean force){
		boolean printed = super.printStats(force);
		return printed;
	}

	private boolean jiffiesAreClose(String one, String two){
		double jiffiesA = Double.parseDouble(one);
		double jiffiesB = Double.parseDouble(two);

		return Math.abs(jiffiesA - jiffiesB) < 10; // should be close
	}

	private AlternateIdentifier getAlternateIdForVertex(AbstractVertex v){
		AlternateIdentifier al = new AlternateIdentifier(v);
		if(!idMapping.containsKey(al.getOldId())){
			idMapping.put(al.getOldId(), new ArrayList<>());
		}
		idMapping.get(al.getOldId()).add(al);
		return al;
	}

	private class AlternateIdentifier{
		private AbstractVertex vertex;
		private String oldId;
		private String jiffies;
		private String sender;
		private String receiver;

		public AlternateIdentifier(AbstractVertex vertex) {
			this.vertex = vertex;
			this.oldId = vertex.id();
			this.jiffies = vertex.getAnnotation("jiffies");
			this.sender = vertex.getAnnotation("sender");
			this.receiver = vertex.getAnnotation("receiver");
		}

		public String getNewId(){
			return "PAN" + this.oldId + jiffies + sender + receiver + "AMA";
		}

		public boolean isCloseTo(String jiffies){
			return jiffiesAreClose(jiffies, this.jiffies);
		}

		public AbstractVertex getVertex() {
			return vertex;
		}

		public String getOldId() {
			return oldId;
		}

		public String getJiffies() {
			return jiffies;
		}

		public String getSender() {
			return sender;
		}

		public String getReceiver() {
			return receiver;
		}

		@Override
		public String toString() {
			return getNewId();
		}
	}
}
