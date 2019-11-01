package io.quarkus.arc.test;

import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

@Singleton
public class Producers {

    private volatile Context context;

    @Produces
    @Singleton
    Context context() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context;
    }
}
