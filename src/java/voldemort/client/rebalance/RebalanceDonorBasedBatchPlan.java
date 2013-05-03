/*
 * Copyright 2013 LinkedIn, Inc
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
package voldemort.client.rebalance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import voldemort.cluster.Cluster;
import voldemort.store.StoreDefinition;

import com.google.common.collect.Lists;

public class RebalanceDonorBasedBatchPlan extends RebalanceTypedBatchPlan {

    public RebalanceDonorBasedBatchPlan(final Cluster currentCluster,
                                        final Cluster targetCluster,
                                        final List<StoreDefinition> storeDefs,
                                        final boolean enabledDeletePartition) {
        super(currentCluster, targetCluster, storeDefs, enabledDeletePartition);

        HashMap<Integer, List<RebalancePartitionsInfo>> donorToBatchPlan = new HashMap<Integer, List<RebalancePartitionsInfo>>();
        for(RebalancePartitionsInfo info: batchPlan) {
            int donorId = info.getDonorId();
            if(!donorToBatchPlan.containsKey(donorId)) {
                donorToBatchPlan.put(donorId, new ArrayList<RebalancePartitionsInfo>());
            }
            donorToBatchPlan.get(donorId).add(info);
        }

        for(int donorId: donorToBatchPlan.keySet()) {
            rebalanceTaskQueue.offer(new RebalanceNodePlan(donorId,
                                                           Lists.newArrayList(donorToBatchPlan.get(donorId)),
                                                           false));
        }
    }
}