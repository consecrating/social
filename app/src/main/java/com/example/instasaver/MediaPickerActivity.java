package com.example.instasaver;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An in-app gallery picker backed directly by MediaStore. Because we build the
 * URIs from MediaStore ourselves (content://media/...), a later "Move" can delete
 * the originals reliably — unlike the opaque URIs returned by external pickers.
 */
public class MediaPickerActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";           // 0 both, 1 video, 2 image
    public static final String EXTRA_RESULT_URIS = "selected_uris";

    public static final int MODE_BOTH = 0;
    public static final int MODE_VIDEO = 1;
    public static final int MODE_IMAGE = 2;

    private int mode;
    private PickerAdapter adapter;
    private RecyclerView recycler;
    private TextView emptyView;
    private Button doneBtn;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    res -> {
                        boolean granted = false;
                        for (Boolean b : res.values()) granted |= Boolean.TRUE.equals(b);
                        if (granted) {
                            load();
                        } else {
                            Toast.makeText(this, "Media permission is required to pick files.",
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_picker);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_BOTH);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        recycler = findViewById(R.id.pickerRecycler);
        emptyView = findViewById(R.id.emptyView);
        doneBtn = findViewById(R.id.doneBtn);

        recycler.setLayoutManager(new GridLayoutManager(this, 3));
        adapter = new PickerAdapter();
        recycler.setAdapter(adapter);

        doneBtn.setOnClickListener(v -> finishWithSelection());
        updateDone();

        if (GalleryUtil.hasReadMedia(this)) {
            load();
        } else {
            permLauncher.launch(GalleryUtil.readMediaPermissions());
        }
    }

    private void load() {
        List<Item> items = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        Uri files = MediaStore.Files.getContentUri("external");

        final int IMG = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
        final int VID = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
        String typeCol = MediaStore.Files.FileColumns.MEDIA_TYPE;
        String sel;
        if (mode == MODE_VIDEO) sel = typeCol + "=" + VID;
        else if (mode == MODE_IMAGE) sel = typeCol + "=" + IMG;
        else sel = typeCol + " IN (" + IMG + "," + VID + ")";

        String[] proj = {MediaStore.Files.FileColumns._ID, typeCol};
        String sort = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        try (Cursor c = cr.query(files, proj, sel, null, sort)) {
            if (c != null) {
                int idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int mtIdx = c.getColumnIndexOrThrow(typeCol);
                while (c.moveToNext() && items.size() < 5000) {
                    long id = c.getLong(idIdx);
                    boolean video = c.getInt(mtIdx) == VID;
                    Uri uri = ContentUris.withAppendedId(
                            video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    items.add(new Item(uri, video));
                }
            }
        } catch (Exception ignored) { }

        adapter.submit(items);
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateDone() {
        int n = adapter == null ? 0 : adapter.selected.size();
        doneBtn.setText(n > 0 ? "Done (" + n + ")" : getString(R.string.pick_done));
        doneBtn.setEnabled(n > 0);
    }

    private void finishWithSelection() {
        ArrayList<Uri> out = new ArrayList<>();
        for (Item item : adapter.items) {
            if (adapter.selected.contains(item.uri.toString())) out.add(item.uri);
        }
        if (out.isEmpty()) {
            finish();
            return;
        }
        Intent data = new Intent();
        data.putParcelableArrayListExtra(EXTRA_RESULT_URIS, out);
        setResult(RESULT_OK, data);
        finish();
    }

    static class Item {
        final Uri uri;
        final boolean video;

        Item(Uri uri, boolean video) {
            this.uri = uri;
            this.video = video;
        }
    }

    class PickerAdapter extends RecyclerView.Adapter<PickerAdapter.VH> {
        final List<Item> items = new ArrayList<>();
        final Set<String> selected = new LinkedHashSet<>();

        void submit(List<Item> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_picker, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Item item = items.get(position);
            Glide.with(h.thumb.getContext()).load(item.uri).centerCrop()
                    .placeholder(R.drawable.ic_placeholder).into(h.thumb);
            h.playBadge.setVisibility(item.video ? View.VISIBLE : View.GONE);
            boolean sel = selected.contains(item.uri.toString());
            h.tint.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                String key = items.get(pos).uri.toString();
                if (selected.contains(key)) selected.remove(key); else selected.add(key);
                notifyItemChanged(pos);
                updateDone();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final ImageView playBadge;
            final ImageView check;
            final View tint;

            VH(@NonNull View v) {
                super(v);
                thumb = v.findViewById(R.id.thumb);
                playBadge = v.findViewById(R.id.playBadge);
                check = v.findViewById(R.id.check);
                tint = v.findViewById(R.id.selectionTint);
            }
        }
    }
}
