package io.github.mrbolz.utilityapp;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import io.github.mrbolz.utilityapp.bridge.BridgesPlugin;
import io.github.mrbolz.utilityapp.network.NetworkScannerPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // App-local plugins must be registered before the bridge starts.
        registerPlugin(NetworkScannerPlugin.class);
        registerPlugin(BridgesPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
