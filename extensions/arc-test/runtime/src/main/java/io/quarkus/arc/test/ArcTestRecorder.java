package io.quarkus.arc.test;

import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class ArcTestRecorder {

    public void addEndpoint(String endpointClassName, RuntimeValue<Context> context) {
        context.getValue().addEndpointClass(endpointClassName);
    }

    public RuntimeValue<Context> createContext(BeanContainer beanContainer) {
        Context context = new Context();
        beanContainer.instance(Producers.class).setContext(context);
        return new RuntimeValue<>(context);
    }

    public void start(RuntimeValue<Context> context) {
        context.getValue().start();
    }
}
