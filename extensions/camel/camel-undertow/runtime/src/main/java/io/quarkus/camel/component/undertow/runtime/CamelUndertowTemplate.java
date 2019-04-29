package io.quarkus.camel.component.undertow.runtime;

import org.apache.camel.Component;
import org.apache.camel.spi.Registry;

import io.quarkus.camel.core.runtime.CamelRuntime;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Template;

@Template
public class CamelUndertowTemplate {

    public CamelUndertowHandlerWrapper registerComponent(RuntimeValue<CamelRuntime> camelRuntime) {
        final Registry registry = camelRuntime.getValue().getRegistry();
        final CamelUndertowHandlerWrapper wrapper = new CamelUndertowHandlerWrapper();
        registry.bind("undertow", Component.class, new QuarkusUndertowComponent(wrapper));
        return wrapper;
    }

}
