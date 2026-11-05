package ph.trailwatch.hikerphone;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

public final class AlertFeedback {
    private AlertFeedback() { }

    public static void alert(Context context, String eventType) {
        boolean linkLost = "LINK_LOST".equals(eventType);
        boolean quiet = "LINK_RESTORED".equals(eventType) || "BATTERY_RECOVERED".equals(eventType)
                || "TEST_MODE_ON".equals(eventType) || "TEST_MODE_OFF".equals(eventType)
                || "INACTIVITY_CONFIRMED".equals(eventType);
        if (quiet) return;
        boolean urgent = !"LOW_BATTERY".equals(eventType) && !linkLost;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = urgent ? new long[]{0, 450, 180, 450, 180, 450} : new long[]{0, 250};
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else { //noinspection deprecation
                vibrator.vibrate(pattern, -1);
            }
        }
        if (linkLost) return;
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, urgent ? 85 : 55);
        tone.startTone(urgent ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_PROP_BEEP, urgent ? 1200 : 500);
        new Handler(Looper.getMainLooper()).postDelayed(tone::release, urgent ? 1500 : 700);
    }
}
