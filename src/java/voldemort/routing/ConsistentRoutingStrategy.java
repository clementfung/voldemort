/*
 * Copyright 2008-2013 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import kafka.producer.Partitioner;
import kafka.utils.VerifiableProperties;
import org.apache.commons.codec.DecoderException;
import org.apache.log4j.Logger;

import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.store.venice.VeniceMessage;
import voldemort.utils.ByteArray;
import voldemort.utils.ByteUtils;
import voldemort.utils.FnvHashFunction;
import voldemort.utils.HashFunction;

import com.google.common.collect.Sets;

/**
 * A Routing strategy that routes each request to the first N nodes where N is a
 * user defined replication factor.
 * 
 * The mapping is computed by creating partitions of a fixed size, and
 * maintaining a mapping from partition tag to Node. These nodes are mapped onto
 * a ring.
 * 
 * A preference list of nodes to route to is created by taking the partition
 * into which the key hashes, and then taking the next N nodes on the ring.
 * 
 * 
 */
public class ConsistentRoutingStrategy implements RoutingStrategy, Partitioner {

    // the replication factor.
    private final int numReplicas;
    private final Node[] partitionToNode;
    private final HashFunction hash;

    private static final Logger logger = Logger.getLogger(ConsistentRoutingStrategy.class);

    /**
     * Constructor used by the Kafka Producer
     * */
    public ConsistentRoutingStrategy(VerifiableProperties properties) {
        numReplicas = -1;
        partitionToNode = new Node[0];
        hash = new FnvHashFunction();
    }

    public ConsistentRoutingStrategy(Cluster cluster, int numReplicas) {
        this(new FnvHashFunction(), cluster, numReplicas);
    }

    @Override
    public int getNumReplicas() {
        return this.numReplicas;
    }

    public Node[] getPartitionToNode() {
        return partitionToNode;
    }

    public ConsistentRoutingStrategy(HashFunction hash, Cluster cluster, int numReplicas) {
        this.numReplicas = numReplicas;
        this.hash = hash;
        this.partitionToNode = cluster.getPartitionIdToNodeArray();
    }

    public ConsistentRoutingStrategy(HashFunction hash, Collection<Node> nodes, int numReplicas) {
        this.numReplicas = numReplicas;
        this.hash = hash;
        // sanity check that we dont assign the same partition to multiple nodes
        SortedMap<Integer, Node> m = new TreeMap<Integer, Node>();
        for(Node n: nodes) {
            for(Integer partition: n.getPartitionIds()) {
                if(m.containsKey(partition))
                    throw new IllegalArgumentException("Duplicate partition id " + partition
                                                       + " in cluster configuration " + nodes);
                m.put(partition, n);
            }
        }

        this.partitionToNode = new Node[m.size()];
        for(int i = 0; i < m.size(); i++) {
            if(!m.containsKey(i))
                throw new IllegalArgumentException("Invalid configuration, missing partition " + i);
            this.partitionToNode[i] = m.get(i);
        }
    }

    /**
     * A modified version of abs that always returns a non-negative value.
     * Math.abs returns Integer.MIN_VALUE if a == Integer.MIN_VALUE and this
     * method returns Integer.MAX_VALUE in that case.
     */
    private static int abs(int a) {
        if(a >= 0)
            return a;
        else if(a != Integer.MIN_VALUE)
            return -a;
        return Integer.MAX_VALUE;
    }

    @Override
    public List<Node> routeRequest(byte[] key) {
        List<Integer> partitionList = getPartitionList(key);

        if(partitionList.size() == 0)
            return new ArrayList<Node>(0);
        // pull out the nodes corresponding to the target partitions
        List<Node> preferenceList = new ArrayList<Node>(partitionList.size());

        if (logger.isDebugEnabled()) {
            logger.debug("Key " + ByteUtils.toHexString(key) + " mapped to partitions " + partitionList);
        }

        for(int partition: partitionList) {
            preferenceList.add(partitionToNode[partition]);
        }
        if(logger.isDebugEnabled()) {
            List<Integer> nodeIdList = new ArrayList<Integer>();
            for(int partition: partitionList) {
                nodeIdList.add(partitionToNode[partition].getId());
            }
            logger.debug("Key " + ByteUtils.toHexString(key) + " mapped to Nodes " + nodeIdList
                         + " Partitions " + partitionList);
        }
        return preferenceList;
    }

    @Override
    public List<Integer> getReplicatingPartitionList(int index) {
        List<Node> preferenceList = new ArrayList<Node>(numReplicas);
        List<Integer> replicationPartitionsList = new ArrayList<Integer>(numReplicas);

        if(partitionToNode.length == 0) {
            return new ArrayList<Integer>(0);
        }
        // go over clockwise to find the next 'numReplicas' unique nodes
        // to replicate to
        for(int i = 0; i < partitionToNode.length; i++) {
            // add this one if we haven't already
            if(!preferenceList.contains(partitionToNode[index])) {
                preferenceList.add(partitionToNode[index]);
                replicationPartitionsList.add(index);
            }

            // if we have enough, go home
            if(preferenceList.size() >= numReplicas)
                return replicationPartitionsList;
            // move to next clockwise slot on the ring
            index = (index + 1) % partitionToNode.length;
        }

        // we don't have enough, but that may be okay
        return replicationPartitionsList;
    }

    /**
     * Obtain the master partition for a given key
     * 
     * @param key
     * @return master partition id
     */
    @Override
    public Integer getMasterPartition(byte[] key) {
        return getMasterPartition(key, partitionToNode.length);
    }

    /**
     * A new function created to be used by both clients and Kafka
     * */
    private int getMasterPartition(byte[] key, int numReplicas) {
        return abs(hash.hash(key)) % (Math.max(1, numReplicas));
    }

    /**
     * Obtain the master partition for a given key and number of replicas
     * This class is ONLY used by Kafka producer to determine the partition location
     *
     * @param key
     * @param numReplicas
     * @return master partition id
     */
    @Override
    public int partition(Object key, int numReplicas) {

        // For Voldemort Venice integration, all keys from Kafka should be of type ByteArray
        ByteArray byteKey = ((ByteArray)key);
        ByteArray keyToPartition;

        // One important thing to note here is that Venice keys will be prepended with Magic Bytes
        // and possibly schema info. We want the 'true' key to be used when partitioning the data
        if (byteKey.get()[0] == VeniceMessage.FULL_OPERATION_BYTE) {
            keyToPartition = byteKey.subArray(1);

        } else if (byteKey.get()[1] == VeniceMessage.PARTIAL_OPERATION_BYTE) {
            // TODO: To implement partial puts, remove sub-schema from the key before partitioning
            logger.error("Partial puts are not yet supported. Returning -1.");
            return -1;

        } else {
            logger.error("Found an illegal first byte. Returning -1.");
            return -1;
        }

        int partition = getMasterPartition(keyToPartition.get(), numReplicas);
        if (logger.isDebugEnabled()) {
            logger.debug("Hashing: " + key.toString() + " goes to partition "
                    + partition + " of [0," + (numReplicas - 1) + "]");
        }
        return partition;

    }

    @Override
    public Set<Node> getNodes() {
        Set<Node> s = Sets.newHashSetWithExpectedSize(partitionToNode.length);
        for(Node n: this.partitionToNode)
            s.add(n);
        return s;
    }

    Node getNodeByPartition(int partition) {
        return partitionToNode[partition];
    }

    Set<Integer> getPartitionsByNode(Node n) {
        Set<Integer> tags = new HashSet<Integer>();
        for(int i = 0; i < partitionToNode.length; i++)
            if(partitionToNode[i].equals(n))
                tags.add(i);
        return tags;
    }

    @Override
    public List<Integer> getPartitionList(byte[] key) {
        // hash the key and perform a modulo on the total number of partitions,
        // to get the master partition
        int index = getMasterPartition(key);
        if(logger.isDebugEnabled()) {
            logger.debug("Key " + ByteUtils.toHexString(key) + " primary partition " + index);
        }
        // Now based on the preference list, pick the replicating partitions and
        // return
        return getReplicatingPartitionList(index);
    }

    @Override
    public String getType() {
        return RoutingStrategyType.CONSISTENT_STRATEGY;
    }
}
