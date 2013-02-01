package com.splout.db.qnode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.splout.db.common.ReplicationEntry;
import com.splout.db.common.ReplicationMap;
import com.splout.db.common.Tablespace;
import com.splout.db.hazelcast.DNodeInfo;
import com.splout.db.hazelcast.TablespaceVersion;

/**
 * A specialized {@link QNode} module that takes care of detecting under-replicated replicas and possibly coordinating
 * sending files between DNodes to compensate those.
 * <p>
 * TODO Work in progress: coordinating with Hazelcast, etc. Time to wait before sending an order-> SAFE MODE (QNode),
 * should not start this daemon before X time.
 */
public class ReplicaBalancer {

	private final static Log log = LogFactory.getLog(ReplicaBalancer.class);

	private QNodeHandlerContext context;

	public ReplicaBalancer(QNodeHandlerContext context) {
		this.context = context;
	}

	/**
	 * Bean that represents an action that has to be taken for balancing a partition. The origin node should copy its
	 * partition from the tablespace/version to the final node.
	 * <p>
	 * Note that originNodeId is a DNode if (Thrift) whereas finalNodeHttpAddres is an HTTP end-point (not a DNode Id).
	 */
	@SuppressWarnings("serial")
	public static class BalanceAction implements Serializable {

		private String originNodeId;
		private String finalNodeHttpAddress;
		private String tablespace;
		private int partition;
		private long version;

		// Note that originNodeId is a DNode if (Thrift) whereas finalNodeHttpAddres is an HTTP end-point (not a DNode Id)
		public BalanceAction(String originNode, String finalNodeHttpAddress, String tablespace,
		    int partition, long version) {
			this.originNodeId = originNode;
			this.finalNodeHttpAddress = finalNodeHttpAddress;
			this.tablespace = tablespace;
			this.partition = partition;
			this.version = version;
		}

		public String toString() {
			return originNodeId + " => " + finalNodeHttpAddress + " (" + tablespace + ", " + partition + ", "
			    + version + ")";
		}

		/**
		 * Because of the semantics of this object, we consider equals another one even if the origin node is different. We
		 * only care about the final node and the data that will be transfered.
		 */
		@Override
		public boolean equals(Object obj) {
			BalanceAction action = (BalanceAction) obj;
			return action.getFinalNode().equals(getFinalNode())
			    && action.getTablespace().equals(getTablespace()) && action.getVersion() == getVersion()
			    && action.getPartition() == getPartition();
		}

		@Override
		public int hashCode() {
			return getFinalNode().hashCode() ^ getTablespace().hashCode()
			    ^ Long.valueOf(getVersion()).hashCode() ^ getPartition();
		}

		// ---- Getters ---- //

		public String getOriginNode() {
			return originNodeId;
		}

		public String getFinalNode() {
			return finalNodeHttpAddress;
		}

		public String getTablespace() {
			return tablespace;
		}

		public int getPartition() {
			return partition;
		}

		public long getVersion() {
			return version;
		}
	}

	public List<BalanceAction> scanPartitions() {
		// get list of DNodes
		List<String> aliveDNodes = context.getDNodeList();
		Collections.sort(aliveDNodes);

		// actions that can be taken after analyzing the current replicas / partitions
		List<BalanceAction> balanceActions = new ArrayList<BalanceAction>();

		// we will use round robin for assigning new partitions to DNodes
		// so that if several BalanceAction have to be taken, they will be more or less spread
		int roundRobinCount = 0;

		for(Map.Entry<TablespaceVersion, Tablespace> entry : context.getTablespaceVersionsMap().entrySet()) {
			ReplicationMap replicationMap = entry.getValue().getReplicationMap();
			for(ReplicationEntry rEntry : replicationMap.getReplicationEntries()) {
				// for every missing replica... maybe perform a BalanceAction
				if(rEntry.getNodes().size() > 0) {
					for(int i = rEntry.getNodes().size(); i < rEntry.getExpectedReplicationFactor(); i++) {
						// we found an under-replicated partition! add it as candidate
						// we will copy the partition from the first available node in this partition
						String originNode = rEntry.getNodes().get(0);
						// ... then find the first DNode which doesn't currently hold this partition
						// as candidate to receive it
						for(int k = 0; k < aliveDNodes.size(); k++) {
							int roundRobinIndex = (roundRobinCount + k) % aliveDNodes.size();
							String finalNode = aliveDNodes.get(roundRobinIndex);
							if(rEntry.getNodes().contains(finalNode)) {
								continue;
							}
							// we have a possible balance action
							// note we must exchange the DNode Id by its HTTP end-point to be able to send files there
							String finalHttpAddress = null;
							for(DNodeInfo dnode : context.getCoordinationStructures().getDNodes().values()) {
								if(dnode.getAddress().equals(finalNode)) {
									finalHttpAddress = dnode.getHttpExchangerAddress();
								}
							}
							if(finalHttpAddress == null) {
								// race condition: DNode went down right in the middle - cancel this and try another one
							} else {
								// add balance action and break for() loop
								balanceActions.add(new BalanceAction(originNode, finalHttpAddress, entry.getKey()
								    .getTablespace(), rEntry.getShard(), entry.getKey().getVersion()));
								roundRobinCount++;
								break;
							}
						}
					}
				}
			}
		}

		return balanceActions;
	}
}
