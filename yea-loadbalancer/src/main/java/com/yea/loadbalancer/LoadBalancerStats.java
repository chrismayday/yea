package com.yea.loadbalancer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import com.yea.core.loadbalancer.BalancingNode;

public class LoadBalancerStats {
    
    private static final String PREFIX = "LBStats_";
    
    String name;
    
     Map<BalancingNode,ServerStats> serverStatsMap = new ConcurrentHashMap<BalancingNode,ServerStats>();
    volatile Map<String, ZoneStats> zoneStatsMap = new ConcurrentHashMap<String, ZoneStats>();
    volatile Map<String, Collection<? extends BalancingNode>> upServerListZoneMap = new ConcurrentHashMap<String, Collection<? extends BalancingNode>>();
    
    private volatile DynamicIntProperty connectionFailureThreshold;
        
    private volatile DynamicIntProperty circuitTrippedTimeoutFactor;

    private volatile DynamicIntProperty maxCircuitTrippedTimeout;

    private static final DynamicIntProperty SERVERSTATS_EXPIRE_MINUTES = 
        DynamicPropertyFactory.getInstance().getIntProperty("niws.loadbalancer.serverStats.expire.minutes", 30);
    
    private final LoadingCache<BalancingNode, ServerStats> serverStatsCache = 
        CacheBuilder.newBuilder()
            .expireAfterAccess(SERVERSTATS_EXPIRE_MINUTES.get(), TimeUnit.MINUTES)
            .removalListener(new RemovalListener<BalancingNode, ServerStats>() {
                @Override
                public void onRemoval(RemovalNotification<BalancingNode, ServerStats> notification) {
                    notification.getValue().close();
                }
            })
            .build(
                new CacheLoader<BalancingNode, ServerStats>() {
                    public ServerStats load(BalancingNode server) {
                        return createServerStats(server);
                    }
                });
        
    private ServerStats createServerStats(BalancingNode server) {
        ServerStats ss = new ServerStats(this);
        //configure custom settings
        ss.setBufferSize(1000);
        ss.setPublishInterval(1000);                    
        ss.initialize(server);
        return ss;        
    }
    
    private LoadBalancerStats(){
        zoneStatsMap = new ConcurrentHashMap<String, ZoneStats>();  
        upServerListZoneMap = new ConcurrentHashMap<String, Collection<? extends BalancingNode>>();        
    }
    
    public LoadBalancerStats(String name){
        this();
        this.name = name;
        Monitors.registerObject(name, this); 
    }
       
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    DynamicIntProperty getConnectionFailureCountThreshold() {
        if (connectionFailureThreshold == null) {
            connectionFailureThreshold = DynamicPropertyFactory.getInstance().getIntProperty(
                    "niws.loadbalancer." + name + ".connectionFailureCountThreshold", 3);
        }
        return connectionFailureThreshold;

    }
    
    DynamicIntProperty getCircuitTrippedTimeoutFactor() {
        if (circuitTrippedTimeoutFactor == null) {
            circuitTrippedTimeoutFactor = DynamicPropertyFactory.getInstance().getIntProperty(
                    "niws.loadbalancer." + name + ".circuitTripTimeoutFactorSeconds", 10);
        }
        return circuitTrippedTimeoutFactor;        
    }
    
    DynamicIntProperty getCircuitTripMaxTimeoutSeconds() {
        if (maxCircuitTrippedTimeout == null) {
            maxCircuitTrippedTimeout = DynamicPropertyFactory.getInstance().getIntProperty(
                    "niws.loadbalancer." + name + ".circuitTripMaxTimeoutSeconds", 30);
        }
        return maxCircuitTrippedTimeout;        
    }
    
    /**
     * The caller o this class is tasked to call this method every so often if
     * the servers participating in the LoadBalancer changes
     * @param servers
     */
    public void updateServerList(Collection<BalancingNode> servers){
        for (BalancingNode s: servers){
            addServer(s);
        }
    }
    
    
    public void addServer(BalancingNode server) {
        try {
            serverStatsCache.get(server);
        } catch (ExecutionException e) {
            ServerStats stats = createServerStats(server);
            serverStatsCache.asMap().putIfAbsent(server, stats);
        }
    } 
    
    /**
     * Method that updates the internal stats of Response times maintained on a per Server
     * basis
     * @param server
     * @param msecs
     */
    public void noteResponseTime(BalancingNode server, double msecs){
        ServerStats ss = getServerStats(server);  
        ss.noteResponseTime(msecs);
    }
    
    private ServerStats getServerStats(BalancingNode server) {
        try {
            return serverStatsCache.get(server);
        } catch (ExecutionException e) {
            ServerStats stats = createServerStats(server);
            serverStatsCache.asMap().putIfAbsent(server, stats);
            return serverStatsCache.asMap().get(server);
        }
    }
    
    public void incrementActiveRequestsCount(BalancingNode server) {
        ServerStats ss = getServerStats(server); 
        ss.incrementActiveRequestsCount();
    }

    public void decrementActiveRequestsCount(BalancingNode server) {
        ServerStats ss = getServerStats(server); 
        ss.decrementActiveRequestsCount();
    }

    private ZoneStats getZoneStats(String zone) {
        zone = zone.toLowerCase();
        ZoneStats zs = zoneStatsMap.get(zone);
        if (zs == null){
            zoneStatsMap.put(zone, new ZoneStats(this.getName(), zone, this));
            zs = zoneStatsMap.get(zone);
        }
        return zs;
    }

    
    public boolean isCircuitBreakerTripped(BalancingNode server) {
        ServerStats ss = getServerStats(server);
        return ss.isCircuitBreakerTripped();
    }
        
    public void incrementSuccessiveConnectionFailureCount(BalancingNode server) {
        ServerStats ss = getServerStats(server);
        ss.incrementSuccessiveConnectionFailureCount();
    }
    
    public void clearSuccessiveConnectionFailureCount(BalancingNode server) {
        ServerStats ss = getServerStats(server);
        ss.clearSuccessiveConnectionFailureCount();        
    }

    public void incrementNumRequests(BalancingNode server){
        ServerStats ss = getServerStats(server);  
        ss.incrementNumRequests();
    }

    public void incrementZoneCounter(BalancingNode server) {
        String zone = server.getZone();
        if (zone != null) {
            getZoneStats(zone).incrementCounter();
        }
    }
    
    public void updateZoneServerMapping(Map<String, Collection<BalancingNode>> map) {
        upServerListZoneMap = new ConcurrentHashMap<String, Collection<? extends BalancingNode>>(map);
        // make sure ZoneStats object exist for available zones for monitoring purpose
        for (String zone: map.keySet()) {
            getZoneStats(zone);
        }
    }

    public int getInstanceCount(String zone) {
        if (zone == null) {
            return 0;
        }
        zone = zone.toLowerCase();
        Collection<? extends BalancingNode> currentList = upServerListZoneMap.get(zone);
        if (currentList == null) {
            return 0;
        }
        return currentList.size();
    }
    
    public int getActiveRequestsCount(String zone) {
        return getZoneSnapshot(zone).getActiveRequestsCount();
    }
        
    public double getActiveRequestsPerServer(String zone) {
        return getZoneSnapshot(zone).getLoadPerServer();
    }

    public ZoneSnapshot getZoneSnapshot(String zone) {
        if (zone == null) {
            return new ZoneSnapshot();
        }
        zone = zone.toLowerCase();
        Collection<? extends BalancingNode> currentList = upServerListZoneMap.get(zone);
        return getZoneSnapshot(currentList);        
    }
    
    /**
     * This is the core function to get zone stats. All stats are reported to avoid
     * going over the list again for a different stat.
     * 
     * @param servers
     */
    public ZoneSnapshot getZoneSnapshot(Collection<? extends BalancingNode> servers) {
        if (servers == null || servers.size() == 0) {
            return new ZoneSnapshot();
        }
        int instanceCount = servers.size();
        int activeConnectionsCount = 0;
        int activeConnectionsCountOnAvailableServer = 0;
        int circuitBreakerTrippedCount = 0;
        double loadPerServer = 0;
        long currentTime = System.currentTimeMillis();
        for (BalancingNode server: servers) {
            ServerStats stat = getSingleServerStat(server);   
            if (stat.isCircuitBreakerTripped(currentTime)) {
                circuitBreakerTrippedCount++;
            } else {
                activeConnectionsCountOnAvailableServer += stat.getActiveRequestsCount(currentTime);
            }
            activeConnectionsCount += stat.getActiveRequestsCount(currentTime);
        }
        if (circuitBreakerTrippedCount == instanceCount) {
            if (instanceCount > 0) {
                // should be NaN, but may not be displayable on Epic
                loadPerServer = -1;
            }
        } else {
            loadPerServer = ((double) activeConnectionsCountOnAvailableServer) / (instanceCount - circuitBreakerTrippedCount);
        }
        return new ZoneSnapshot(instanceCount, circuitBreakerTrippedCount, activeConnectionsCount, loadPerServer);
    }
    
    public int getCircuitBreakerTrippedCount(String zone) {
        return getZoneSnapshot(zone).getCircuitTrippedCount();
    }

    public int getCircuitBreakerTrippedCount() {
        int count = 0;
        for (String zone: upServerListZoneMap.keySet()) {
            count += getCircuitBreakerTrippedCount(zone);
        }
        return count;
    }
    
    public long getMeasuredZoneHits(String zone) {
        if (zone == null) {
            return 0;
        }
        zone = zone.toLowerCase();
        long count = 0;
        Collection<? extends BalancingNode> currentList = upServerListZoneMap.get(zone);
        if (currentList == null) {
            return 0;
        }
        for (BalancingNode server: currentList) {
            ServerStats stat = getSingleServerStat(server);
            count += stat.getMeasuredRequestsCount();
        }
        return count;
    }
        
    public int getCongestionRatePercentage(String zone) {
        if (zone == null) {
            return 0;
        }
        zone = zone.toLowerCase();
        Collection<? extends BalancingNode> currentList = upServerListZoneMap.get(zone);
        if (currentList == null || currentList.size() == 0) {
            return 0;            
        }
        int serverCount = currentList.size(); 
        int activeConnectionsCount = 0;
        int circuitBreakerTrippedCount = 0;
        for (BalancingNode server: currentList) {
            ServerStats stat = getSingleServerStat(server);   
            activeConnectionsCount += stat.getActiveRequestsCount();
            if (stat.isCircuitBreakerTripped()) {
                circuitBreakerTrippedCount++;
            }
        }
        return (int) ((activeConnectionsCount + circuitBreakerTrippedCount) * 100L / serverCount); 
    }
    
    @Monitor(name=PREFIX + "AvailableZones", type = DataSourceType.INFORMATIONAL)   
    public Set<String> getAvailableZones() {
        return upServerListZoneMap.keySet();
    }
    
    public ServerStats getSingleServerStat(BalancingNode server) {
        return getServerStats(server);
    }

    /**
     * returns map of Stats for all servers
     */
    public Map<BalancingNode,ServerStats> getServerStats(){
        return serverStatsCache.asMap();
    }
    
    public Map<String, ZoneStats> getZoneStats() {
        return zoneStatsMap;
    }
    
    @Override
    public String toString() {
        return "Zone stats: " + zoneStatsMap.toString() 
                + "," + "Server stats: " + getSortedServerStats(getServerStats().values()).toString();
    }
    
    private static Comparator<ServerStats> serverStatsComparator = new Comparator<ServerStats>() {
        @Override
        public int compare(ServerStats o1, ServerStats o2) {
            String zone1 = "";
            String zone2 = "";
            if (o1.server != null && o1.server.getZone() != null) {
                zone1 = o1.server.getZone();
            }
            if (o2.server != null && o2.server.getZone() != null) {
                zone2 = o2.server.getZone();
            }
            return zone1.compareTo(zone2);            
        }
    };
    
    private static Collection<ServerStats> getSortedServerStats(Collection<ServerStats> stats) {
        List<ServerStats> list = new ArrayList<ServerStats>(stats);
        Collections.sort(list, serverStatsComparator);
        return list;
    }

}
