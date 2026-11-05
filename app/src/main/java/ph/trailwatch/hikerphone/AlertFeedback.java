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

    public static void alert(Context context, boolean urgent) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = urgent ? new long[]{0, 450, 180, 450, 180, 450} : new long[]{0, 300};
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else { //noinspection deprecation
                vibrator.vibrate(pattern, -1);
            }
        }
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, urgent ? 85 : 55);
        tone.startTone(urgent ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_PROP_BEEP, urgent ? 1200 : 500);
        new Handler(Looper.getMainLooper()).postDelayed(tone::release, urgent ? 1500 : 700);
    }
}
