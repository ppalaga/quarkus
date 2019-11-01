package io.quarkus.arc.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;

public class Context {

    private static final Logger log = Logger.getLogger(Context.class);

    private final List<String> endpointClassNames = new ArrayList<String>();
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<String, Endpoint>();

    public Context() {
        super();
    }

    public Endpoint resolveEndpoint(String id) {
        synchronized (endpoints) {
            return endpoints.get(id);
        }
    }

    public void addEndpointClass(String endpointClassName) {
        endpointClassNames.add(endpointClassName);
    }

    public void start() {
        synchronized (endpoints) {
            try {
                for (String cl : endpointClassNames) {
                    final Endpoint endpoint = Arc.container().instance((Class<Endpoint>) Class.forName(cl)).get();
                    endpoints.put(endpoint.getId(), endpoint);
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            log.infof("Started context with %d endpoints", endpoints.size());
        }
    }

}
