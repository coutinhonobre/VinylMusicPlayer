package com.poupa.vinylmusicplayer.util;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.documentfile.provider.DocumentFile;

import com.poupa.vinylmusicplayer.R;
import com.poupa.vinylmusicplayer.model.Song;

import org.jaudiotagger.audio.AudioFileIO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SAFUtil {
    public static final String TAG = SAFUtil.class.getSimpleName();

    private static boolean isSAFRequired(File file) {
        return !file.canWrite();
    }

    public static boolean isSAFRequired(String path) {
        return isSAFRequired(new File(path));
    }

    public static boolean isSAFRequired(Song song) {
        return isSAFRequired(song.data);
    }

    public static boolean isSAFRequired(List<Song> songs) {
        for (Song song : songs) {
            if (isSAFRequired(song.data)) return true;
        }
        return false;
    }

    public static void saveTreeUri(Context context, Uri uri) {
        context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PreferenceUtil.getInstance().setSAFSDCardUri(uri);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static boolean isTreeUriSaved() {
        return !TextUtils.isEmpty(PreferenceUtil.getInstance().getSAFSDCardUri());
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static boolean isSDCardAccessGranted(Context context) {
        if (!isTreeUriSaved()) return false;

        String sdcardUri = PreferenceUtil.getInstance().getSAFSDCardUri();

        List<UriPermission> perms = context.getContentResolver().getPersistedUriPermissions();
        for (UriPermission perm : perms) {
            if (perm.getUri().toString().equals(sdcardUri) && perm.isWritePermission()) {
                return true;
            }
        }

        return false;
    }

    /**
     * https://github.com/vanilla-music/vanilla-music-tag-editor/commit/e00e87fef289f463b6682674aa54be834179ccf0#diff-d436417358d5dfbb06846746d43c47a5R359
     * Finds needed file through Document API for SAF. It's not optimized yet - you can still gain wrong URI on
     * files such as "/a/b/c.mp3" and "/b/a/c.mp3", but I consider it complete enough to be usable.
     *
     * @param dir      - document file representing current dir of search
     * @param segments - path segments that are left to find
     * @return URI for found file. Null if nothing found.
     */
    @Nullable
    private static Uri findDocument(@Nullable DocumentFile dir, @NonNull List<String> segments) {
        if (dir == null) {return null;}

        for (DocumentFile file : dir.listFiles()) {
            int index = segments.indexOf(file.getName());
            if (index == -1) {
                continue;
            }

            if (file.isDirectory()) {
                segments.remove(file.getName());
                return findDocument(file, segments);
            }

            if (file.isFile() && index == segments.size() - 1) {
                // got to the last part
                return file.getUri();
            }
        }

        return null;
    }

    public static void write(Context context, AutoDeleteAudioFile audio, @NonNull final Song song) {
        writeSAF(context, audio, song);
    }

    private static Uri getUriFromSong(@NonNull final Song song) {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id);
    }

    public static @Nullable AutoDeleteAudioFile loadAudioFile(Context context, @NonNull final Song song) {
        // Note: Thus function works around the incompatibility between MediaStore API vs JAudioTagger lib.
        // For file access, MediaStore offers In/OutputStream, whereas JAudioTagger requires File/RandomAccessFile API.
        if (song.id == Song.EMPTY_SONG.id) {return null;}

        final Uri uri = getUriFromSong(song);
        try (InputStream original = context.getContentResolver().openInputStream(uri)) {
            // Create an app-private temp file
            Function<String, String> getSuffix = (name) -> {
                final int i = name.lastIndexOf(".");
                if (i == -1) {return "";}
                return name.substring(i + 1);
            };
            File tempFile = AutoDeleteTempFile.create(String.valueOf(song.id), getSuffix.apply(song.data)).get();

            // Copy the content of the URI to the temp file
            try (OutputStream temp = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = original.read(buffer, 0, 1024)) >= 0) {
                    temp.write(buffer, 0, read);
                }
            }

            // Create a ephemeral/volatile audio file
            return new AutoDeleteAudioFile(AudioFileIO.read(tempFile));
        } catch (Exception e) {
            OopsHandler.copyStackTraceToClipboard(context, e);
            return null;
        }
    }

    private static void writeSAF(Context context, AutoDeleteAudioFile tempAudio, @NonNull final Song song) {
        if (context == null) {
            return;
        }

        Uri uri = getUriFromSong(song);

        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "rw")) {
            // Save the in-memory tags to the backing file
            tempAudio.get().commit();

            // now read persisted data and write it to real FD provided by SAF
            FileInputStream fis = new FileInputStream(tempAudio.get().getFile());
            byte[] audioContent = FileUtil.readBytes(fis);
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(audioContent);
            fos.close();
        } catch (final Exception e) {
            OopsHandler.copyStackTraceToClipboard(context, e);
            toast(context, String.format(context.getString(R.string.saf_write_failed), e.getLocalizedMessage()));
        }
    }

    public static void delete(Context context, String path, Uri safUri) {
        if (isSAFRequired(path)) {
            deleteSAF(context, path, safUri);
        } else {
            try {
                deleteFile(path);
            } catch (NullPointerException e) {
                Log.e("MusicUtils", "Failed to find file " + path);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void deleteFile(String path) {
        new File(path).delete();
    }

    public static void deleteSAF(Context context, String path, Uri safUri) {
        Uri uri = null;

        if (context == null) {
            Log.e(TAG, "deleteSAF: context == null");
            return;
        }

        if (isTreeUriSaved()) {
            List<String> pathSegments = new ArrayList<>(Arrays.asList(path.split("/")));
            Uri sdcard = Uri.parse(PreferenceUtil.getInstance().getSAFSDCardUri());
            uri = findDocument(DocumentFile.fromTreeUri(context, sdcard), pathSegments);
        }

        if (uri == null) {
            uri = safUri;
        }

        if (uri == null) {
            Log.e(TAG, "deleteSAF: Can't get SAF URI");
            toast(context, context.getString(R.string.saf_error_uri));
            return;
        }

        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        } catch (final Exception e) {
            Log.e(TAG, "deleteSAF: Failed to delete a file descriptor provided by SAF", e);

            toast(context, String.format(context.getString(R.string.saf_delete_failed), e.getLocalizedMessage()));
        }
    }

    private static void toast(final Context context, final String message) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }
    }
}
