package com.example.instasaver;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/** Grid of album "folders" in the hidden vault. */
public class AlbumFolderAdapter extends RecyclerView.Adapter<AlbumFolderAdapter.VH> {

    public interface Listener {
        void onOpen(String album);
        void onCustomize(String album);
    }

    private final List<String> albums = new ArrayList<>();
    private final MediaRepository repo;
    private final AlbumMeta meta;
    private final Listener listener;
    private boolean isVideo = true; // which media type this grid is showing

    public AlbumFolderAdapter(MediaRepository repo, AlbumMeta meta, Listener listener) {
        this.repo = repo;
        this.meta = meta;
        this.listener = listener;
    }

    public void setType(boolean isVideo) {
        this.isVideo = isVideo;
    }

    public void submit(List<String> newAlbums) {
        albums.clear();
        albums.addAll(newAlbums);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album_folder, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String album = albums.get(position);
        List<DownloadedItem> contents = repo.list(isVideo, MediaRepository.Sort.NEWEST, album);

        h.name.setText(album);
        h.count.setText(contents.size() + (contents.size() == 1 ? " item" : " items"));

        String coverUri = meta.coverImage(album);
        int coverColor = meta.coverColor(album);

        if (coverUri != null) {
            h.cover.setBackgroundColor(0x22000000);
            Glide.with(h.cover.getContext()).load(Uri.parse(coverUri))
                    .centerCrop().placeholder(R.drawable.ic_folder).into(h.cover);
        } else if (coverColor != 0) {
            Glide.with(h.cover.getContext()).clear(h.cover);
            h.cover.setImageDrawable(null);
            h.cover.setBackgroundColor(coverColor);
        } else if (!contents.isEmpty()) {
            h.cover.setBackgroundColor(0x22000000);
            Glide.with(h.cover.getContext()).load(contents.get(0).file)
                    .centerCrop().placeholder(R.drawable.ic_folder).into(h.cover);
        } else {
            Glide.with(h.cover.getContext()).clear(h.cover);
            h.cover.setBackgroundColor(0x22000000);
            h.cover.setImageResource(R.drawable.ic_folder);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(album);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onCustomize(album);
            return true;
        });
        h.menu.setOnClickListener(v -> {
            if (listener != null) listener.onCustomize(album);
        });
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final ImageView menu;
        final TextView name;
        final TextView count;

        VH(@NonNull View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            menu = v.findViewById(R.id.menu);
            name = v.findViewById(R.id.name);
            count = v.findViewById(R.id.count);
        }
    }
}
