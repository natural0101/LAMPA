package top.rootu.lampa.fakeplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Test-only transport: never opens media, files, sockets, or a real video player. */
public final class FakePlayerActivity extends Activity {
    private static final String TEST_CALLER = "top.rootu.lampa.nexttest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent request = getIntent();
        if (!TEST_CALLER.equals(getCallingPackage()) ||
                !Intent.ACTION_VIEW.equals(request.getAction()) ||
                request.getData() == null || !"example.invalid".equals(request.getData().getHost()) ||
                !request.getBooleanExtra("return_result", false)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Intent observed = new Intent("top.rootu.lampa.fakeplayer.TEST_LAUNCH")
                .setPackage(TEST_CALLER)
                .putExtra("url", request.getDataString())
                .putExtra("return_result_received", true)
                .putExtra("title", request.getStringExtra("title"));
        sendBroadcast(observed);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent result = new Intent("com.mxtech.intent.result.VIEW")
                    .setData(request.getData())
                    .putExtra("end_by", "playback_completion")
                    .putExtra("position", 60000)
                    .putExtra("duration", 60000);
            setResult(RESULT_OK, result);
            finish();
        }, 150L);
    }
}
