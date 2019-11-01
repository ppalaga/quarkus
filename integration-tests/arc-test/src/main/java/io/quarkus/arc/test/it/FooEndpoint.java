package io.quarkus.arc.test.it;

import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.arc.test.Context;
import io.quarkus.arc.test.Endpoint;

@ApplicationScoped
public class FooEndpoint implements Endpoint {
    static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);
    private static final Logger log = Logger.getLogger(Context.class);

    @Inject
    Counter counter;

    private final int instanceId;

    public FooEndpoint() {
        this.instanceId = INSTANCE_COUNTER.incrementAndGet();
        log.infof("Created FooEndpoint no. %d", instanceId);
        //new RuntimeException().printStackTrace();
    }

    @Override
    public String handle(String input) {
        final int val = counter.increment();
        log.infof("FooHandler no. %d at counter %d", instanceId, val);
        return "foo (" + val + ")";
    }

    @Override
    public String getId() {
        return "foo";
    }

}
