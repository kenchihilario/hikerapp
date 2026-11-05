package ph.trailwatch.hikerphone;

import android.content.Context;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Writes local observations to app-private storage as JSON Lines. */
public final class LocalSampleStore {
    public static final String FILE_NAME = "gps-samples.jsonl";
    private final File file;

    public LocalSampleStore(Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
    }

    public synchronized void append(GpsSample sample) throws Exception {
        appendRecord(sample.toJson());
    }

    public synchronized void appendRecord(JSONObject record) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            writer.write(record.toString());
            writer.newLine();
        }
    }

    public File getFile() {
        return file;
    }

    public long getRecordCount() {
        if (!file.isFile()) return 0;
        long count = 0;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) count++;
        } catch (Exception ignored) {
            return count;
        }
        return count;
    }
}
