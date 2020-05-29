package org.corfudb.logreplication.infrastructure;

import lombok.Getter;
import lombok.Setter;
import org.corfudb.logreplication.proto.LogReplicationSiteInfo.SiteConfigurationMsg;


public abstract class CorfuReplicationSiteManagerAdapter {
    @Getter
    @Setter
    CorfuReplicationDiscoveryServiceAdapter corfuReplicationDiscoveryService;

    @Getter
    SiteConfigurationMsg siteConfigMsg;


    public synchronized SiteConfigurationMsg fetchSiteConfig() {
        siteConfigMsg = querySiteConfig();
        return siteConfigMsg;
    }

    /**
     * Will be called when the site change and a new configuration is sent over
     * @param newSiteConfigMsg
     * @return
     */
    synchronized void updateSiteConfig(SiteConfigurationMsg newSiteConfigMsg) {
            if (newSiteConfigMsg.getSiteConfigID() > siteConfigMsg.getSiteConfigID()) {
                siteConfigMsg = newSiteConfigMsg;
                corfuReplicationDiscoveryService.updateSiteConfig(siteConfigMsg);
            }
    }

    public abstract SiteConfigurationMsg querySiteConfig();

    public abstract void start();

    public void prepareSiteRoleChange() {
        corfuReplicationDiscoveryService.prepareSiteRoleChange();
    }

    public int queryReplicationStatus() {
        return corfuReplicationDiscoveryService.queryReplicationStatus();
    }
}