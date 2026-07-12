package com.example.instasaver;

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

/** Grid adapter that renders downloaded media thumbnails via Glide. */
public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.VH> {

    public interface Listener {
        void onOpen(DownloadedItem item);
        void onActions(DownloadedItem item);
    }

    private final List<DownloadedItem> items = new ArrayList<>();
    private final Listener listener;

    public MediaAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<DownloadedItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DownloadedItem item = items.get(position);

        Glide.with(h.thumb.getContext())
                .load(item.file)
                .centerCrop()
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(h.thumb);

        h.playBadge.setVisibility(item.isVideo ? View.VISIBLE : View.GONE);
        h.name.setText(item.getName());
        h.subtitle.setText(item.album != null ? item.album : item.getReadableSize());

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(item);
        });
        h.menu.setOnClickListener(v -> {
            if (listener != null) listener.onActions(item);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onActions(item);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final ImageView playBadge;
        final ImageView menu;
        final TextView name;
        final TextView subtitle;

        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            playBadge = v.findViewById(R.id.playBadge);
            menu = v.findViewById(R.id.menu);
            name = v.findViewById(R.id.name);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
