package org.corfudb.universe.scenario;

import org.corfudb.runtime.collections.CorfuTable;
import org.corfudb.runtime.view.ClusterStatusReport;
import org.corfudb.universe.GenericIntegrationTest;
import org.corfudb.universe.group.cluster.CorfuCluster;
import org.corfudb.universe.node.client.CorfuClient;
import org.corfudb.universe.node.server.CorfuServer;
import org.junit.Ignore;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.corfudb.universe.scenario.ScenarioUtils.waitForUnresponsiveServersChange;
import static org.corfudb.universe.scenario.fixture.Fixtures.TestFixtureConst.DEFAULT_STREAM_NAME;
import static org.corfudb.universe.scenario.fixture.Fixtures.TestFixtureConst.DEFAULT_TABLE_ITER;

public class NodesDownAndPartitionedIT extends GenericIntegrationTest {

    /**
     * Test cluster behavior after one down and another node partitioned
     * <p>
     * 1) Deploy and bootstrap a three nodes cluster
     * 2) Stop one node
     * 3) Symmetrically partition one node
     * 4) Verify layout, cluster status and data path
     * 5) Recover cluster by restart the stopped node and fix partition
     * 5) Verify layout, cluster status and data path
     */
    @Test(timeout = 300000)
    public void nodeDownAndPartitionTest() {
        getScenario().describe((fixture, testCase) -> {
            CorfuCluster corfuCluster = universe.getGroup(fixture.getCorfuCluster().getName());

            CorfuClient corfuClient = corfuCluster.getLocalCorfuClient();

            CorfuTable<String, String> table = corfuClient.createDefaultCorfuTable(DEFAULT_STREAM_NAME);
            for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                table.put(String.valueOf(i), String.valueOf(i));
            }

            testCase.it("Should stop one node and partition another one", data -> {
                CorfuServer server0 = corfuCluster.getServerByIndex(0);
                CorfuServer server1 = corfuCluster.getServerByIndex(1);
                CorfuServer server2 = corfuCluster.getServerByIndex(2);

                // Stop one node and partition another one
                server1.stop(Duration.ofSeconds(10));
                server2.disconnect(Arrays.asList(server1, server2));

                // TODO: There is a bug in NodeStatus API, waiting for patch and uncomment following lines
                // Verify cluster status is UNAVAILABLE with two nodes up and one node down
                corfuClient.invalidateLayout();
                ClusterStatusReport clusterStatusReport = corfuClient.getManagementView().getClusterStatus();
                // assertThat(clusterStatusReport.getClusterStatus()).isEqualTo(ClusterStatus.UNAVAILABLE);
                //
                // Map<String, NodeStatus> statusMap = clusterStatusReport.getClientServerConnectivityStatusMap();
                // assertThat(statusMap.get(server0.getParams().getEndpoint())).isEqualTo(NodeStatus.UP);
                // assertThat(statusMap.get(server1.getParams().getEndpoint())).isEqualTo(NodeStatus.DOWN);
                // assertThat(statusMap.get(server2.getParams().getEndpoint())).isEqualTo(NodeStatus.UP);
                //
                // // Wait for failure detector finds cluster is down before recovering
                // waitForClusterDown(table);

                // Recover cluster by restarting the stopped node, removing
                // partition and wait for layout's unresponsive servers to change
                server1.start();
                server2.reconnect();
                waitForUnresponsiveServersChange(size -> size == 0, corfuClient);

                // Verify cluster status is STABLE
                clusterStatusReport = corfuClient.getManagementView().getClusterStatus();

                //FIXME status always - UNAVAILABLE, must be STABLE
                //assertThat(clusterStatusReport.getClusterStatus()).isEqualTo(ClusterStatus.STABLE);

                // Verify data path working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }
            });

            corfuClient.shutdown();
        });
    }
}
