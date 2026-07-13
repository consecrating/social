package com.example.instasaver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The hidden vault: shows custom albums as folders (with customizable cover
 * image/color) and can import media from the phone gallery into an album so it
 * lives only in this private area. Reached only by long-pressing the title.
 */
public class AlbumsActivity extends AppCompatActivity implements AlbumFolderAdapter.Listener {

    private MediaRepository repo;
    private AlbumMeta meta;
    private AlbumFolderAdapter adapter;
    private RecyclerView recycler;
    private TextView emptyView;

    private String pendingCoverAlbum; // album whose cover image is being picked
    private boolean currentTypeVideo = true; // Reels tab (true) vs Photos tab (false)

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> pickCover =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null
                                && pendingCoverAlbum != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                try {
                                    getContentResolver().takePersistableUriPermission(
                                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } catch (Exception ignored) { }
                                meta.setCoverImage(pendingCoverAlbum, uri.toString());
                                reload();
                            }
                        }
                        pendingCoverAlbum = null;
                    });

    private final ActivityResultLauncher<Intent> importPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            List<Uri> uris = GalleryUtil.collectUris(result.getData());
                            if (!uris.isEmpty()) chooseAlbumThenImport(uris);
                        }
                    });

    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                    r -> {
                        toast("Originals removed from gallery");
                        reload();
                    });

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
        setContentView(R.layout.activity_albums);

        repo = new MediaRepository(this);
        meta = new AlbumMeta(this);

        Haptics.tick(this); // haptic feedback when the hidden section opens

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.importBtn).setOnClickListener(v -> launchImportPicker());
        findViewById(R.id.newFolderBtn).setOnClickListener(v -> promptNewFolder());
        recycler = findViewById(R.id.albumsRecycler);
        emptyView = findViewById(R.id.emptyView);

        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new AlbumFolderAdapter(repo, meta, this);
        recycler.setAdapter(adapter);

        TabLayout typeTabs = findViewById(R.id.albumTypeTabs);
        typeTabs.addTab(typeTabs.newTab().setText(R.string.tab_reels));
        typeTabs.addTab(typeTabs.newTab().setText(R.string.tab_photos));
        typeTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTypeVideo = tab.getPosition() == 0;
                reload();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) { }

            @Override
            public void onTabReselected(TabLayout.Tab tab) { }
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
        if (VaultLock.consumeInternalActivity()) { // returning from our own picker
            reload();
            return;
        }
        if (!VaultLock.isUnlocked()) { // backgrounded or timed out → re-lock
            finish();
            return;
        }
        reload();
    }

    private void promptNewFolder() {
        final EditText input = new EditText(this);
        input.setHint("Folder name");
        new AlertDialog.Builder(this)
                .setTitle("New hidden folder")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (repo.createAlbum(name)) {
                        toast("Folder created");
                    } else {
                        toast("Couldn't create folder");
                    }
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void reload() {
        adapter.setType(currentTypeVideo);
        List<String> albums = repo.albumsForType(currentTypeVideo);
        adapter.submit(albums);
        boolean empty = albums.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Import from gallery
    // ------------------------------------------------------------------

    private void launchImportPicker() {
        try {
            VaultLock.beginInternalActivity();
            importPicker.launch(GalleryUtil.chooser(false, false, true));
        } catch (Exception e) {
            toast("No gallery app available.");
        }
    }

    private void chooseAlbumThenImport(List<Uri> uris) {
        List<String> albums = repo.allAlbums();
        if (albums.isEmpty()) {
            promptNewAlbumForImport(uris);
            return;
        }
        List<String> options = new ArrayList<>(albums);
        options.add("New album…");
        CharSequence[] arr = options.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle("Import " + uris.size() + " into…")
                .setItems(arr, (d, which) -> {
                    if (which == options.size() - 1) {
                        promptNewAlbumForImport(uris);
                    } else {
                        confirmImportMode(uris, options.get(which));
                    }
                })
                .show();
    }

    private void promptNewAlbumForImport(List<Uri> uris) {
        final EditText input = new EditText(this);
        input.setHint("Album name");
        new AlertDialog.Builder(this)
                .setTitle("New hidden album")
                .setView(input)
                .setPositiveButton("Next", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) confirmImportMode(uris, name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmImportMode(List<Uri> uris, String album) {
        new AlertDialog.Builder(this)
                .setTitle("Import " + uris.size() + " to \"" + album + "\"")
                .setMessage("Copy keeps the originals in your gallery. Move also tries "
                        + "to remove them from the gallery so they stay only here.")
                .setPositiveButton("Move (remove originals)", (d, w) -> doImport(uris, album, true))
                .setNeutralButton("Copy", (d, w) -> doImport(uris, album, false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doImport(List<Uri> uris, String album, boolean removeOriginals) {
        toast("Importing " + uris.size() + "…");
        io.execute(() -> {
            int ok = 0;
            final List<Uri> imported = new ArrayList<>();
            for (Uri uri : uris) {
                File f = repo.importUri(uri, album);
                if (f != null) {
                    ok++;
                    imported.add(uri);
                }
            }
            final int done = ok;
            main.post(() -> {
                toast(done + " of " + uris.size() + " imported to \"" + album + "\"");
                if (removeOriginals && !imported.isEmpty()) {
                    removeOriginals(imported);
                }
                reload();
            });
        });
    }

    // ------------------------------------------------------------------
    // Album folder interactions
    // ------------------------------------------------------------------

    /** Ensure read-media permission (to find the file in MediaStore), then delete. */
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
                    : "Couldn't locate the originals to remove — delete them from your gallery.");
        }
    }

    @Override
    public void onOpen(String album) {
        Intent i = new Intent(this, AlbumDetailActivity.class);
        i.putExtra(AlbumDetailActivity.EXTRA_ALBUM, album);
        i.putExtra(AlbumDetailActivity.EXTRA_IS_VIDEO, currentTypeVideo);
        startActivity(i);
    }

    @Override
    public void onCustomize(String album) {
        CharSequence[] options = {
                "Change cover image", "Change cover color", "Rename album", "Delete album"
        };
        new AlertDialog.Builder(this)
                .setTitle(album)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: pickCoverImage(album); break;
                        case 1: pickCoverColor(album); break;
                        case 2: renameAlbum(album); break;
                        default: deleteAlbum(album); break;
                    }
                })
                .show();
    }

    private void pickCoverImage(String album) {
        pendingCoverAlbum = album;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            VaultLock.beginInternalActivity();
            pickCover.launch(i);
        } catch (Exception e) {
            toast("No gallery app available.");
            pendingCoverAlbum = null;
        }
    }

    private void pickCoverColor(String album) {
        final int[] colors = {
                0xFFC13584, 0xFF833AB4, 0xFFE1306C, 0xFFF56040, 0xFFFCAF45,
                0xFF405DE6, 0xFF2E7D32, 0xFF00897B, 0xFF546E7A, 0xFF212121
        };
        final CharSequence[] names = {
                "Instagram Pink", "Purple", "Rose", "Orange", "Amber",
                "Blue", "Green", "Teal", "Slate", "Charcoal"
        };
        new AlertDialog.Builder(this)
                .setTitle("Cover color")
                .setItems(names, (d, which) -> {
                    meta.setCoverColor(album, colors[which]);
                    reload();
                })
                .setNeutralButton("Clear cover", (d, w) -> { meta.clear(album); reload(); })
                .show();
    }

    private void renameAlbum(String album) {
        final EditText input = new EditText(this);
        input.setText(album);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Rename album")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(album)) return;
                    if (repo.renameAlbum(album, name)) {
                        meta.rename(album, name);
                        toast("Renamed");
                    } else {
                        toast("Rename failed");
                    }
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAlbum(String album) {
        new AlertDialog.Builder(this)
                .setTitle("Delete album?")
                .setMessage("This permanently deletes \"" + album + "\" and all files in it.")
                .setPositiveButton("Delete", (d, w) -> {
                    repo.deleteAlbum(album);
                    meta.clear(album);
                    toast("Album deleted");
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
