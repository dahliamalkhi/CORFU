package org.corfudb.infrastructure.logreplication.infrastructure.plugins;

/**
 * A file reader cluster manager that provides the following static topology:
 * - 1 active corfu node
 * - 1 active LR
 * - 1 standby corfu nodes
 * - 2 standby LRs
 */
public class TwoStandbysClusterManager extends FileReaderClusterManager {
    @Override
    String getConfigFilePath() {
        return "corfu_replication_config_two_standbys.properties";
    }
}
