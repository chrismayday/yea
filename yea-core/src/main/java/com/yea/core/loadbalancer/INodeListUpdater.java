package com.yea.core.loadbalancer;

public interface INodeListUpdater {

    /**
     * an interface for the updateAction that actually executes a server list update
     */
    public interface UpdateAction {
        void doUpdate();
    }


    /**
     * start the serverList updater with the given update action
     * This call should be idempotent.
     *
     * @param updateAction
     */
    void start(UpdateAction updateAction);

    /**
     * stop the serverList updater. This call should be idempotent
     */
    void stop();

    /**
     * @return the last update timestamp as a {@link java.util.Date} string
     */
    String getLastUpdate();

    /**
     * @return the number of ms that has elapsed since last update
     */
    long getDurationSinceLastUpdateMs();

    /**
     * @return the number of update cycles missed, if valid
     */
    int getNumberMissedCycles();

    /**
     * @return the number of threads used, if vaid
     */
    int getCoreThreads();
}
