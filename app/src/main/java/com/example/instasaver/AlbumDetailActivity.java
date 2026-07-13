package com.example.instasaver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

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
                            ArrayList<Uri> uris = result.getData()
                                    .getParcelableArrayListExtra(MediaPickerActivity.EXTRA_RESULT_URIS);
                            if (uris != null && !uris.isEmpty()) confirmAddMode(uris);
                        }
                    });

    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                    r -> toast("Originals removed from gallery"));

    private List<Uri> pendingDeleteUris;
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    res -> {
                        boolean granted = false;
                        for (Boolean b : res.values()) granted |= Boolean.TRUE.equals(b);
                        List<Uri> pend = pendingDeleteUris;
                        pendingDeleteUris = null;
                        if (granted && pend != null) {
                            doRemoveOriginals(pend);
                        } else {
                            toast("Permission is needed to remove originals from the gallery.");
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
        VaultLock.beginInternalActivity();
        Intent i = new Intent(this, MediaPickerActivity.class);
        i.putExtra(MediaPickerActivity.EXTRA_MODE,
                isVideo ? MediaPickerActivity.MODE_VIDEO : MediaPickerActivity.MODE_IMAGE);
        addFilesPicker.launch(i);
    }

    private void confirmAddMode(List<Uri> uris) {
        new AlertDialog.Builder(this)
                .setTitle("Add " + uris.size() + " to \"" + album + "\"")
                .setMessage("Copy keeps the originals in your gallery. Move also removes "
                        + "them from the gallery so they stay only here.")
                .setPositiveButton("Move (remove originals)", (d, w) -> importInto(uris, true))
                .setNeutralButton("Copy", (d, w) -> importInto(uris, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importInto(List<Uri> uris, boolean removeOriginals) {
        toast("Adding " + uris.size() + "…");
        io.execute(() -> {
            int ok = 0;
            final List<Uri> imported = new ArrayList<>();
            for (Uri uri : uris) {
                if (repo.importUri(uri, album) != null) {
                    ok++;
                    imported.add(uri);
                }
            }
            final int done = ok;
            main.post(() -> {
                toast(done + " added to \"" + album + "\"");
                Fragment f = getSupportFragmentManager().findFragmentById(R.id.albumContainer);
                if (f instanceof MediaListFragment) ((MediaListFragment) f).refresh();
                if (removeOriginals && !imported.isEmpty()) {
                    removeOriginals(imported);
                }
            });
        });
    }

    private void removeOriginals(List<Uri> uris) {
        if (!GalleryUtil.hasReadMedia(this)) {
            pendingDeleteUris = uris;
            VaultLock.beginInternalActivity();
            permLauncher.launch(GalleryUtil.readMediaPermissions());
            return;
        }
        doRemoveOriginals(uris);
    }

    private void doRemoveOriginals(List<Uri> uris) {
        int removed = GalleryUtil.deleteOriginals(this, uris, deleteLauncher);
        if (removed != GalleryUtil.DELETE_PENDING) {
            toast(removed > 0 ? removed + " removed from gallery"
                    : "Couldn't remove originals automatically (Android "
                            + android.os.Build.VERSION.RELEASE + "). Delete them from your gallery.");
        }
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
        if (VaultLock.consumeInternalActivity()) { // returning from our own picker
            return;
        }
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
