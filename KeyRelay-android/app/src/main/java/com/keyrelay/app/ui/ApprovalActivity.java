package com.keyrelay.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.keyrelay.app.KeyRelayApp;
import com.keyrelay.app.R;
import com.keyrelay.app.relay.RelayManager;

import java.util.Locale;

/** "Laptop Connection Request": compare the check code with the website, then Approve or Deny. */
public class ApprovalActivity extends Activity implements RelayManager.Listener {

    private RelayManager relay;
    private TextView tvSas, tvRoom, tvValid;
    private ProgressBar pb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            relay.tick();
            renderTimer();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_approval);
        relay = ((KeyRelayApp) getApplication()).relay();
        tvSas = findViewById(R.id.tvSas);
        tvRoom = findViewById(R.id.tvRoom);
        tvValid = findViewById(R.id.tvValid);
        pb = findViewById(R.id.pbValid);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnApprove).setOnClickListener(v -> { relay.approve(); finish(); });
        findViewById(R.id.btnDeny).setOnClickListener(v -> { relay.deny(); finish(); });
    }

    @Override
    protected void onStart() {
        super.onStart();
        relay.setListener(this);
        relay.onForeground();
        if (relay.pending() == null) { finish(); return; }
        render();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        relay.clearListener(this);
        relay.onBackground();
    }

    private void render() {
        RelayManager.Pending p = relay.pending();
        if (p == null) return;
        tvSas.setText(p.sas);
        String c = relay.code();
        tvRoom.setText("Room: " + (c == null ? "----" : c.substring(0, 4) + "-" + c.substring(4)));
        renderTimer();
    }

    private void renderTimer() {
        RelayManager.Pending p = relay.pending();
        if (p == null) return;
        long left = p.at + RelayManager.PENDING_MS - System.currentTimeMillis();
        if (left <= 0) { Toast.makeText(this, "That request expired.", Toast.LENGTH_SHORT).show(); finish(); return; }
        long s = left / 1000;
        tvValid.setText(String.format(Locale.US, "Valid: %02d:%02d", s / 60, s % 60));
        pb.setProgress((int) (left * 1000 / RelayManager.PENDING_MS));
    }

    // ---- RelayManager.Listener
    @Override public void onRelayChanged() {
        if (relay.pending() == null) { if (!isFinishing()) finish(); } else render();
    }
    @Override public void onJoinRequest() { render(); }
    @Override public void onJoinCleared() {
        Toast.makeText(this, "The laptop cancelled the request.", Toast.LENGTH_SHORT).show();
        finish();
    }
    @Override public void onNotice(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
