package com.yea.loadbalancer;

import com.netflix.config.ChainedDynamicProperty;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.yea.core.loadbalancer.IRule;
import com.yea.loadbalancer.config.IClientConfig;

/**
 * Predicate with the logic of filtering out circuit breaker tripped servers and servers 
 * with too many concurrent connections from this client.
 * 
 * @author awang
 *
 */
public class AvailabilityPredicate extends  AbstractNodePredicate {
        
    private static final DynamicBooleanProperty CIRCUIT_BREAKER_FILTERING =
            DynamicPropertyFactory.getInstance().getBooleanProperty("niws.loadbalancer.availabilityFilteringRule.filterCircuitTripped", true);

    private static final DynamicIntProperty ACTIVE_CONNECTIONS_LIMIT =
            DynamicPropertyFactory.getInstance().getIntProperty("niws.loadbalancer.availabilityFilteringRule.activeConnectionsLimit", Integer.MAX_VALUE);

    private ChainedDynamicProperty.IntProperty activeConnectionsLimit = new ChainedDynamicProperty.IntProperty(ACTIVE_CONNECTIONS_LIMIT);
        
    public AvailabilityPredicate(IRule rule, IClientConfig clientConfig) {
        super(rule, clientConfig);
        initDynamicProperty(clientConfig);
    }
    
    public AvailabilityPredicate(LoadBalancerStats lbStats, IClientConfig clientConfig) {
        super(lbStats, clientConfig);
        initDynamicProperty(clientConfig);
    }
    
    public AvailabilityPredicate(IRule rule) {
        super(rule);
    }

    private void initDynamicProperty(IClientConfig clientConfig) {
        String id = "default";
        if (clientConfig != null) {
            id = clientConfig.getClientName();
            activeConnectionsLimit = new ChainedDynamicProperty.IntProperty(id + "." + clientConfig.getNameSpace() + ".ActiveConnectionsLimit", ACTIVE_CONNECTIONS_LIMIT); 
        }               
    }
    
    @Override
    public boolean apply(PredicateKey input) {
        LoadBalancerStats stats = getLBStats();
        if (stats == null) {
            return true;
        }
        return !shouldSkipServer(stats.getSingleServerStat(input.getServer()));
    }
    
    
    private boolean shouldSkipServer(ServerStats stats) {        
        if ((CIRCUIT_BREAKER_FILTERING.get() && stats.isCircuitBreakerTripped()) 
                || stats.getActiveRequestsCount() >= activeConnectionsLimit.get()) {
            return true;
        }
        return false;
    }

}
