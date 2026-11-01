package se.bofr.dspi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.StubDspiBridge
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.DspiTheme

/**
 * WAVE 0: always demo mode (FakeDspiCore). Package BRIDGE swaps
 * StubDspiBridge for DspiBridgeImpl; package USB adds the UsbSession and the
 * FfiDspiCore path (an app-level `AppState` will own both, PLAN §3.3).
 */
class MainActivity : ComponentActivity() {
    private lateinit var bridge: DspiBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = StubDspiBridge(FakeDspiCore(), lifecycleScope)
        setContent {
            DspiTheme {
                AppScaffold(bridge = bridge, demoMode = true)
            }
        }
    }
}
