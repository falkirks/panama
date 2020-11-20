/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2015 SRI International

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
package spade.transformer;

import spade.client.QueryMetaData;
import spade.core.AbstractEdge;
import spade.core.AbstractTransformer;
import spade.core.Graph;
import spade.reporter.audit.OPMConstants;

//remove memory artifacts along with edges
public class NoMemory extends AbstractTransformer
{

	public Graph transform(Graph graph, QueryMetaData queryMetaData)
	{
		Graph resultGraph = new Graph();
		for(AbstractEdge edge : graph.edgeSet())
		{
			if(getAnnotationSafe(edge.getChildVertex(), OPMConstants.ARTIFACT_SUBTYPE).equals(OPMConstants.SUBTYPE_MEMORY_ADDRESS)
					|| getAnnotationSafe(edge.getParentVertex(),
					OPMConstants.ARTIFACT_SUBTYPE).equals(OPMConstants.SUBTYPE_MEMORY_ADDRESS))
			{
				continue;
			}
			AbstractEdge newEdge = createNewWithoutAnnotations(edge);
			if(newEdge != null && newEdge.getChildVertex() != null && newEdge.getParentVertex() != null)
			{
				resultGraph.putVertex(newEdge.getChildVertex());
				resultGraph.putVertex(newEdge.getParentVertex());
				resultGraph.putEdge(newEdge);	
			}
		}
		return resultGraph;
	}
}
