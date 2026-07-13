package com.example.instasaver;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows one album's contents for a single media type (Reels or Photos). */
public class AlbumDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ALBUM = "album";
    public static final String EXTRA_IS_VIDEO = "is_video";

    private String album;
    private boolean isVideo;
    private MediaRepository repo;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> addFilesPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            List<Uri> uris = collectUris(result.getData());
                            if (!uris.isEmpty()) importInto(uris);
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_album_detail);

        album = getIntent().getStringExtra(EXTRA_ALBUM);
        isVideo = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, true);
        if (TextUtils.isEmpty(album)) {
            finish();
            return;
        }
        repo = new MediaRepository(this);

        ((TextView) findViewById(R.id.albumTitle))
                .setText(album + (isVideo ? "  ·  Reels" : "  ·  Photos"));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.addFilesBtn).setOnClickListener(v -> launchAddFiles());

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.albumContainer, MediaListFragment.albumTyped(album, isVideo))
                    .commit();
        }
    }

    private void launchAddFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(isVideo ? "video/*" : "image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            addFilesPicker.launch(i);
        } catch (Exception e) {
            toast("No gallery app available.");
        }
    }

    private List<Uri> collectUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void importInto(List<Uri> uris) {
        toast("Adding " + uris.size() + "…");
        io.execute(() -> {
            int ok = 0;
            for (Uri uri : uris) {
                if (repo.importUri(uri, album) != null) ok++;
            }
            final int done = ok;
            main.post(() -> {
                toast(done + " added to \"" + album + "\"");
                Fragment f = getSupportFragmentManager().findFragmentById(R.id.albumContainer);
                if (f instanceof MediaListFragment) ((MediaListFragment) f).refresh();
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        VaultLock.onVaultScreenStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        VaultLock.onVaultScreenStop();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        VaultLock.touch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!VaultLock.isUnlocked()) { // backgrounded or timed out → re-lock
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
