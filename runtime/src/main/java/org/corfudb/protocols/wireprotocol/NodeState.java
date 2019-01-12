package org.corfudb.protocols.wireprotocol;

import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import org.corfudb.runtime.view.Layout;
import org.corfudb.util.NodeLocator;

import java.util.Collections;
import java.util.Map;

/**
 * Contains a Node's state:
 * Sequencer state - ready/not_ready.
 * connectivity status - Node's connectivity with every other node in the layout.
 * <p>
 * For instance, node a fully connected to all nodes:
 * {"a": {"endpoint": "a", "connectivity":{"a": true, "b": true, "c": true}}}
 * <p>
 * Created by zlokhandwala on 11/2/18.
 */
@Data
@Builder
@AllArgsConstructor
public class NodeState implements ICorfuPayload<NodeState> {

    public static final long INVALID_HEARTBEAT_COUNTER = -1L;

    /**
     * Current node's Endpoint.
     */
    private final String endpoint;

    private final HeartbeatTimestamp heartbeat;

    /**
     * Sequencer metrics of the node.
     */
    private final SequencerMetrics sequencerMetrics;

    private final NodeConnectivityType connectivityType;

    /**
     * Node's view of the cluster.
     */
    private final Map<String, Boolean> connectivity;

    public NodeState(ByteBuf buf) {
        endpoint = ICorfuPayload.fromBuffer(buf, String.class);
        heartbeat = ICorfuPayload.fromBuffer(buf, HeartbeatTimestamp.class);
        sequencerMetrics = ICorfuPayload.fromBuffer(buf, SequencerMetrics.class);
        connectivity = ICorfuPayload.mapFromBuffer(buf, String.class, Boolean.class);
        String typeName = ICorfuPayload.fromBuffer(buf, String.class);
        this.connectivityType = NodeConnectivityType.valueOf(typeName);
    }

    @Override
    public void doSerialize(ByteBuf buf) {
        ICorfuPayload.serialize(buf, endpoint);
        ICorfuPayload.serialize(buf, heartbeat);
        ICorfuPayload.serialize(buf, sequencerMetrics);
        ICorfuPayload.serialize(buf, connectivity);
        ICorfuPayload.serialize(buf, connectivityType.name());
    }

    /**
     * Creates a default NodeState for the given endpoint.
     * This contains default SequencerMetrics and empty connectivityStatus.
     *
     * @param endpoint Endpoint for the NodeState.
     * @return Default NodeState.
     */
    public static NodeState getDefaultNodeState(String endpoint) {
        return new NodeState(
                endpoint,
                new HeartbeatTimestamp(Layout.INVALID_EPOCH, INVALID_HEARTBEAT_COUNTER),
                SequencerMetrics.UNKNOWN,
                NodeConnectivityType.UNKNOWN,
                Collections.emptyMap()
        );
    }

    /**
     * Heartbeat timestamp is a tuple of the heartbeat counter and the epoch.
     * This timestamp is generated by the local node and attached to the NodeState object.
     *
     * Created by zlokhandwala on 11/13/18.
     */
    @ToString
    @EqualsAndHashCode
    @AllArgsConstructor
    public static class HeartbeatTimestamp implements Comparable<HeartbeatTimestamp>, ICorfuPayload<HeartbeatTimestamp> {
        final long epoch;
        final long counter;

        @Override
        public int compareTo(@NonNull HeartbeatTimestamp heartbeatTimestamp) {
            return Long.compare(epoch, heartbeatTimestamp.epoch);
        }

        public HeartbeatTimestamp(ByteBuf buf) {
            epoch = ICorfuPayload.fromBuffer(buf, Long.class);
            counter = ICorfuPayload.fromBuffer(buf, Long.class);

        }

        @Override
        public void doSerialize(ByteBuf buf) {
            ICorfuPayload.serialize(buf, epoch);
            ICorfuPayload.serialize(buf, counter);
        }
    }

    public enum NodeConnectivityType {
        /**
         * Two nodes are connected
         */
        CONNECTED,
        /**
         * We are unable to get node state from the node (link failure between the nodes)
         */
        UNKNOWN
    }
}
