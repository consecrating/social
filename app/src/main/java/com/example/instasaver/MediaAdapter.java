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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Grid adapter that renders downloaded media thumbnails and supports multi-select. */
public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.VH> {

    public interface Listener {
        void onOpen(DownloadedItem item);
        void onActions(DownloadedItem item);
        void onSelectionChanged(int count);
    }

    private final List<DownloadedItem> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean selectionMode = false;
    private final Listener listener;

    public MediaAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<DownloadedItem> newItems) {
        items.clear();
        items.addAll(newItems);
        // A reload invalidates any prior selection (paths may have moved).
        clearSelectionInternal();
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public List<DownloadedItem> getSelected() {
        List<DownloadedItem> out = new ArrayList<>();
        for (DownloadedItem i : items) {
            if (selected.contains(i.file.getAbsolutePath())) out.add(i);
        }
        return out;
    }

    public void selectAll() {
        selectionMode = true;
        selected.clear();
        for (DownloadedItem i : items) selected.add(i.file.getAbsolutePath());
        notifyDataSetChanged();
        notifyChanged();
    }

    public void clearSelection() {
        clearSelectionInternal();
        notifyDataSetChanged();
        notifyChanged();
    }

    private void clearSelectionInternal() {
        selectionMode = false;
        selected.clear();
    }

    private void notifyChanged() {
        if (listener != null) listener.onSelectionChanged(selected.size());
    }

    private void toggle(DownloadedItem item, int position) {
        String key = item.file.getAbsolutePath();
        if (selected.contains(key)) {
            selected.remove(key);
        } else {
            selected.add(key);
        }
        if (selected.isEmpty()) {
            selectionMode = false;
            notifyDataSetChanged();
        } else {
            notifyItemChanged(position);
        }
        notifyChanged();
    }

    private void enterSelection(DownloadedItem item, int position) {
        selectionMode = true;
        selected.add(item.file.getAbsolutePath());
        notifyDataSetChanged();
        notifyChanged();
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

        boolean isSel = selected.contains(item.file.getAbsolutePath());
        h.selectionTint.setVisibility(isSel ? View.VISIBLE : View.GONE);
        h.check.setVisibility(isSel ? View.VISIBLE : View.GONE);
        // Hide the per-item menu button while multi-selecting to avoid confusion.
        h.menu.setVisibility(selectionMode ? View.GONE : View.VISIBLE);

        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            DownloadedItem it = items.get(pos);
            if (selectionMode) {
                toggle(it, pos);
            } else if (listener != null) {
                listener.onOpen(it);
            }
        });

        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            DownloadedItem it = items.get(pos);
            if (selectionMode) {
                toggle(it, pos);
            } else {
                enterSelection(it, pos);
            }
            return true;
        });

        h.menu.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (listener != null) listener.onActions(items.get(pos));
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
        final ImageView check;
        final View selectionTint;
        final TextView name;
        final TextView subtitle;

        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            playBadge = v.findViewById(R.id.playBadge);
            menu = v.findViewById(R.id.menu);
            check = v.findViewById(R.id.check);
            selectionTint = v.findViewById(R.id.selectionTint);
            name = v.findViewById(R.id.name);
            subtitle = v.findViewById(R.id.subtitle);
        }
    }
}
