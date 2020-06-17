package org.corfudb.infrastructure.logreplication.cluster;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.corfudb.infrastructure.logreplication.proto.LogReplicationClusterInfo.TopologyConfigurationMsg;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationClusterInfo.ClusterRole;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationClusterInfo.ClusterConfigurationMsg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class represents a view of a Multi-Cluster/Site Topology,
 * where one cluster is the active/primary and n cluster's are standby's (backups).
 */
@Slf4j
public class TopologyDescriptor {
    final static String DEFAULT_CORFU_PORT = "9000";

    @Setter
    @Getter
    static String corfuPortNum = DEFAULT_CORFU_PORT;

    @Getter
    private long siteConfigID;

    @Getter
    private ClusterDescriptor activeCluster;

    @Getter
    Map<String, ClusterDescriptor> standbyClusters;

    public TopologyDescriptor(TopologyConfigurationMsg topologyConfig) {
        this.siteConfigID = topologyConfig.getTopologyConfigID();
        standbyClusters = new HashMap<>();
        for (ClusterConfigurationMsg clusterConfig : topologyConfig.getClustersList()) {
            ClusterDescriptor siteInfo = new ClusterDescriptor(clusterConfig);
            if (clusterConfig.getRole() == ClusterRole.ACTIVE) {
                activeCluster = siteInfo;
            } else if (clusterConfig.getRole() == ClusterRole.STANDBY) {
                addStandbySite(siteInfo);
            }
        }
    }

    public TopologyDescriptor(long siteConfigID, ClusterDescriptor primarySite, Map<String, ClusterDescriptor> standbySites) {
        this.siteConfigID = siteConfigID;
        this.activeCluster = primarySite;
        this.standbyClusters = standbySites;
    }

    public TopologyConfigurationMsg convertToMessage() {
        ArrayList<ClusterConfigurationMsg> clustersConfigs = new ArrayList<>();
        clustersConfigs.add((activeCluster.convertToMessage()));

        for (ClusterDescriptor siteInfo : standbyClusters.values()) {
            clustersConfigs.add(siteInfo.convertToMessage());
        }

        TopologyConfigurationMsg topologyConfig = TopologyConfigurationMsg.newBuilder()
                .setTopologyConfigID(siteConfigID)
                .addAllClusters(clustersConfigs).build();

        return topologyConfig;
    }

    public NodeDescriptor getNodeInfo(String endpoint) {
        List<ClusterDescriptor> sites = new ArrayList<>(standbyClusters.values());

        sites.add(activeCluster);
        NodeDescriptor nodeInfo = getNodeInfo(sites, endpoint);

        if (nodeInfo == null) {
            log.warn("No Site has node with IP {} ", endpoint);
        }

        return nodeInfo;
    }

    private NodeDescriptor getNodeInfo(List<ClusterDescriptor> sitesInfo, String endpoint) {
        for (ClusterDescriptor site : sitesInfo) {
            for (NodeDescriptor nodeInfo : site.getNodesDescriptors()) {
                if (nodeInfo.getEndpoint().equals(endpoint)) {
                    return nodeInfo;
                }
            }
        }

        log.warn("There is no nodeInfo for ipAddress {} ", endpoint);
        return null;
    }

    public void addStandbySite(ClusterDescriptor siteInfo) {
        standbyClusters.put(siteInfo.getClusterId(), siteInfo);
    }

    public void removeStandbySite(String siteId) {
        standbyClusters.remove(siteId);
    }
}