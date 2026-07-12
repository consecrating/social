package com.example.instasaver;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import java.util.List;

/**
 * The hidden vault: shows custom albums as folders (with customizable cover
 * image/color). Reached only by long-pressing the InstaSaver title.
 */
public class AlbumsActivity extends AppCompatActivity implements AlbumFolderAdapter.Listener {

    private MediaRepository repo;
    private AlbumMeta meta;
    private AlbumFolderAdapter adapter;
    private RecyclerView recycler;
    private TextView emptyView;

    private String pendingCoverAlbum; // album whose cover image is being picked

    private final ActivityResultLauncher<Intent> pickImage =
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_albums);

        repo = new MediaRepository(this);
        meta = new AlbumMeta(this);

        findViewById(R.id.back).setOnClickListener(v -> finish());
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
            pickImage.launch(i);
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
