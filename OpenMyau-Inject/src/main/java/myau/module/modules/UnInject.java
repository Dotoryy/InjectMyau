package myau.module.modules;

import myau.inject.Bootstrap;
import myau.module.Module;

public class UnInject extends Module {
    public UnInject() {
        super("UnInject", false);
    }

    @Override
    public void onEnabled() {
        this.setEnabled(false);
        Bootstrap.requestStop();
    }
}
