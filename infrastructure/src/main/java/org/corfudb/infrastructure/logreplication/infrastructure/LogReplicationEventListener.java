package org.corfudb.infrastructure.logreplication.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata;
import org.corfudb.infrastructure.logreplication.proto.LogReplicationMetadata.ReplicationEvent;

import org.corfudb.infrastructure.logreplication.replication.fsm.LogReplicationEvent;
import org.corfudb.protocols.wireprotocol.logreplication.LogReplicationEntry;
import org.corfudb.runtime.collections.CorfuStreamEntries;
import org.corfudb.runtime.collections.CorfuStreamEntry;
import org.corfudb.runtime.collections.StreamListener;

import java.util.List;

@Slf4j
public class LogReplicationEventListener implements StreamListener {
    CorfuReplicationDiscoveryService discoveryService;

    public  LogReplicationEventListener(CorfuReplicationDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    public void start() {
        discoveryService.getLogReplicationMetadataManager().subscribeReplicationEventTable(this);
    }

    @Override
    public void onNext(CorfuStreamEntries results) {
        /**
         * If the current node is not a leader, ignore the notifications.
         */
        if (!discoveryService.getIsLeader().get()) {
            log.info("The onNext call with {} will be skipped as the current node {}  in the cluster {} is not the leader.",
                    results, discoveryService.getLocalNodeDescriptor(), discoveryService.getLocalClusterDescriptor());
            return;
        }

        log.info("LogReplicationEventListener onNext {} will be processed at node {} in the cluster {}",
                results, discoveryService.getLocalNodeDescriptor(), discoveryService.getLocalClusterDescriptor());

        /**
         * If the current node is the leader, it generates a discovery event and put it into the discovery service event queue.
         */
        for (List<CorfuStreamEntry> entryList : results.getEntries().values()) {
            for (CorfuStreamEntry entry : entryList) {
                ReplicationEvent event = (ReplicationEvent) entry.getPayload();
                log.info("ReplicationEventListener at node {} put an event {} to its local discoveryServiceQueue", discoveryService.getLocalNodeDescriptor(), event);
                discoveryService.input(new DiscoveryServiceEvent(DiscoveryServiceEvent.DiscoveryServiceEventType.ENFORCE_SNAPSHOT_SYNC, event.getClusterId()));
            }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        log.error("onError for CorfuReplicationDiscoveryServeiceLisener");
    }
}
