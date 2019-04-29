package io.quarkus.camel.component.undertow.runtime;

import org.apache.camel.component.undertow.UndertowComponent;
import org.apache.camel.component.undertow.UndertowHost;
import org.apache.camel.component.undertow.UndertowHostKey;

public class QuarkusUndertowComponent extends UndertowComponent {

    private final UndertowHost host;

    public QuarkusUndertowComponent(UndertowHost host) {
        super();
        this.host = host;
    }

    @Override
    protected UndertowHost createUndertowHost(UndertowHostKey key) {
        return host;
    }

}
