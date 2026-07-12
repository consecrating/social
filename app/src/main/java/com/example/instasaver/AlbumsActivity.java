package com.example.instasaver;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
                            List<Uri> uris = collectUris(result.getData());
                            if (!uris.isEmpty()) chooseAlbumThenImport(uris);
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_albums);

        repo = new MediaRepository(this);
        meta = new AlbumMeta(this);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.importBtn).setOnClickListener(v -> launchImportPicker());
        recycler = findViewById(R.id.albumsRecycler);
        emptyView = findViewById(R.id.emptyView);

        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new AlbumFolderAdapter(repo, meta, this);
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<String> albums = repo.allAlbums();
        adapter.submit(albums);
        boolean empty = albums.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Import from gallery
    // ------------------------------------------------------------------

    private void launchImportPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            importPicker.launch(i);
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
                    deleteOriginals(imported);
                }
                reload();
            });
        });
    }

    /** Best-effort removal of the original gallery items after a successful copy. */
    private void deleteOriginals(List<Uri> uris) {
        int removed = 0, failed = 0;
        for (Uri uri : uris) {
            try {
                boolean ok;
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    ok = DocumentsContract.deleteDocument(getContentResolver(), uri);
                } else {
                    ok = getContentResolver().delete(uri, null, null) > 0;
                }
                if (ok) removed++; else failed++;
            } catch (Exception e) {
                failed++;
            }
        }
        if (failed == 0) {
            toast(removed + " removed from gallery");
        } else {
            toast(removed + " removed; " + failed + " couldn't be removed — delete those "
                    + "from your gallery manually.");
        }
    }

    // ------------------------------------------------------------------
    // Album folder interactions
    // ------------------------------------------------------------------

    @Override
    public void onOpen(String album) {
        Intent i = new Intent(this, AlbumDetailActivity.class);
        i.putExtra(AlbumDetailActivity.EXTRA_ALBUM, album);
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
