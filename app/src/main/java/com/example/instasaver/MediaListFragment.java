package com.example.instasaver;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A grid of downloaded media. Three "modes":
 *   LIBRARY   - the main Reels / Photos library (supports albums).
 *   FAVORITES - the "My Reels" / "My Photos" collections.
 *   TRASH     - the "Delete" bin, holding both reels and photos.
 */
public class MediaListFragment extends Fragment implements MediaAdapter.Listener {

    private static final String ARG_MODE = "mode";
    private static final String ARG_TYPE = "type";

    static final int MODE_LIBRARY = 0;
    static final int MODE_FAVORITES = 1;
    static final int MODE_TRASH = 2;

    static final int TYPE_VIDEO = 0;
    static final int TYPE_PHOTO = 1;
    static final int TYPE_BOTH = 2;

    private int mode;
    private int type;

    private MediaRepository repo;
    private MediaAdapter adapter;

    private RecyclerView recycler;
    private TextView emptyView;
    private View controlsRow;
    private Spinner albumSpinner;
    private Spinner sortSpinner;

    private View selectionBar;
    private TextView selectionCount;
    private LinearLayout selectionButtons;

    private String albumFilter = MediaRepository.ALL;
    private MediaRepository.Sort sort = MediaRepository.Sort.NEWEST;

    private BroadcastReceiver downloadComplete;

    public static MediaListFragment library(boolean isVideo) {
        return build(MODE_LIBRARY, isVideo ? TYPE_VIDEO : TYPE_PHOTO);
    }

    public static MediaListFragment favorites(boolean isVideo) {
        return build(MODE_FAVORITES, isVideo ? TYPE_VIDEO : TYPE_PHOTO);
    }

    public static MediaListFragment trash() {
        return build(MODE_TRASH, TYPE_BOTH);
    }

    private static MediaListFragment build(int mode, int type) {
        MediaListFragment f = new MediaListFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, mode);
        b.putInt(ARG_TYPE, type);
        f.setArguments(b);
        return f;
    }

    private boolean isVideoType() {
        return type == TYPE_VIDEO;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_media_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        mode = getArguments() != null ? getArguments().getInt(ARG_MODE) : MODE_LIBRARY;
        type = getArguments() != null ? getArguments().getInt(ARG_TYPE) : TYPE_VIDEO;
        repo = new MediaRepository(requireContext());

        recycler = v.findViewById(R.id.recycler);
        emptyView = v.findViewById(R.id.emptyView);
        controlsRow = v.findViewById(R.id.controlsRow);
        albumSpinner = v.findViewById(R.id.albumSpinner);
        sortSpinner = v.findViewById(R.id.sortSpinner);
        selectionBar = v.findViewById(R.id.selectionBar);
        selectionCount = v.findViewById(R.id.selectionCount);
        selectionButtons = v.findViewById(R.id.selectionButtons);

        v.findViewById(R.id.btnSelectAll).setOnClickListener(x -> adapter.selectAll());
        v.findViewById(R.id.btnCancelSelection).setOnClickListener(x -> adapter.clearSelection());

        int span = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 4 : 3;
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), span));
        adapter = new MediaAdapter(this);
        recycler.setAdapter(adapter);

        setupSortSpinner();

        // Albums only make sense in the main library.
        albumSpinner.setVisibility(mode == MODE_LIBRARY ? View.VISIBLE : View.GONE);

        emptyView.setText(emptyMessage());
    }

    private int emptyMessage() {
        if (mode == MODE_TRASH) return R.string.empty_delete;
        if (mode == MODE_FAVORITES) {
            return isVideoType() ? R.string.empty_my_reels : R.string.empty_my_photos;
        }
        return isVideoType() ? R.string.empty_reels : R.string.empty_photos;
    }

    private void setupSortSpinner() {
        ArrayAdapter<String> sa = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Newest", "Oldest", "Name", "Largest"});
        sortSpinner.setAdapter(sa);
        sortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                switch (pos) {
                    case 1: sort = MediaRepository.Sort.OLDEST; break;
                    case 2: sort = MediaRepository.Sort.NAME; break;
                    case 3: sort = MediaRepository.Sort.LARGEST; break;
                    default: sort = MediaRepository.Sort.NEWEST; break;
                }
                reload();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void refreshAlbumSpinner() {
        if (mode != MODE_LIBRARY) return;
        List<String> albums = new ArrayList<>();
        albums.add(MediaRepository.ALL);
        albums.addAll(repo.albums(isVideoType()));

        int keep = albums.indexOf(albumFilter);
        ArrayAdapter<String> aa = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, albums);
        albumSpinner.setAdapter(aa);
        albumSpinner.setSelection(keep >= 0 ? keep : 0);

        albumSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                albumFilter = (String) parent.getItemAtPosition(pos);
                reloadItemsOnly();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void reload() {
        if (!isAdded()) return;
        refreshAlbumSpinner();
        reloadItemsOnly();
    }

    private void reloadItemsOnly() {
        if (!isAdded()) return;
        List<DownloadedItem> items;
        switch (mode) {
            case MODE_FAVORITES:
                items = repo.listCollection(isVideoType(), MediaRepository.FAV_DIR, sort);
                break;
            case MODE_TRASH:
                items = repo.listTrashBoth(sort);
                break;
            case MODE_LIBRARY:
            default:
                items = repo.list(isVideoType(), sort, albumFilter);
                break;
        }
        adapter.submit(items);
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();

        downloadComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reload();
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(downloadComplete, filter, Context.RECEIVER_EXPORTED);
        } else {
            requireContext().registerReceiver(downloadComplete, filter);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (downloadComplete != null) {
            try {
                requireContext().unregisterReceiver(downloadComplete);
            } catch (IllegalArgumentException ignored) { }
            downloadComplete = null;
        }
    }

    // ------------------------------------------------------------------
    // Selection bar
    // ------------------------------------------------------------------

    @Override
    public void onSelectionChanged(int count) {
        if (selectionBar == null) return;
        if (count <= 0) {
            selectionBar.setVisibility(View.GONE);
            return;
        }
        selectionBar.setVisibility(View.VISIBLE);
        selectionCount.setText(count + " selected");
        buildSelectionButtons();
    }

    private void buildSelectionButtons() {
        selectionButtons.removeAllViews();
        if (mode == MODE_LIBRARY) {
            addSelBtn(isVideoType() ? R.string.action_move_my_reels : R.string.action_move_my_photos,
                    () -> bulkMove(MediaRepository.FAV_DIR));
            addSelBtn(R.string.sel_to_delete, () -> bulkMove(MediaRepository.TRASH_DIR));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        } else if (mode == MODE_FAVORITES) {
            addSelBtn(R.string.action_remove_fav, () -> bulkMove(MediaRepository.ALL));
            addSelBtn(R.string.sel_to_delete, () -> bulkMove(MediaRepository.TRASH_DIR));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        } else { // TRASH
            addSelBtn(R.string.sel_restore, () -> bulkMove(MediaRepository.ALL));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        }
    }

    private void addSelBtn(int labelRes, Runnable action) {
        Button b = new Button(requireContext(),
                null, android.R.attr.borderlessButtonStyle);
        b.setText(labelRes);
        b.setTextColor(getResources().getColor(R.color.white));
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setOnClickListener(x -> action.run());
        selectionButtons.addView(b);
    }

    private void bulkMove(String targetAlbum) {
        List<DownloadedItem> sel = adapter.getSelected();
        if (sel.isEmpty()) return;
        int ok = 0;
        for (DownloadedItem item : sel) {
            if (repo.moveToAlbum(item, targetAlbum) != null) ok++;
        }
        toast(ok + " moved");
        adapter.clearSelection();
        reload();
    }

    private void bulkDelete() {
        final List<DownloadedItem> sel = adapter.getSelected();
        if (sel.isEmpty()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete " + sel.size() + " item(s)?")
                .setMessage("This permanently removes the selected files.")
                .setPositiveButton("Delete", (d, w) -> {
                    int ok = 0;
                    for (DownloadedItem item : sel) if (repo.delete(item)) ok++;
                    toast(ok + " deleted");
                    adapter.clearSelection();
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void bulkShare() {
        List<DownloadedItem> sel = adapter.getSelected();
        if (sel.isEmpty()) return;
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            boolean anyVideo = false, anyPhoto = false;
            for (DownloadedItem item : sel) {
                uris.add(FileProvider.getUriForFile(requireContext(),
                        MediaRepository.authority(requireContext()), item.file));
                anyVideo |= item.isVideo;
                anyPhoto |= !item.isVideo;
            }
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.setType(anyVideo && anyPhoto ? "*/*" : (anyVideo ? "video/*" : "image/*"));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share via"));
        } catch (Exception e) {
            toast("Couldn't share the selected files.");
        }
    }

    // ------------------------------------------------------------------
    // Single-item actions
    // ------------------------------------------------------------------

    @Override
    public void onOpen(DownloadedItem item) {
        // Open the in-app viewer at the tapped item, scoped to the list on screen,
        // so the user can swipe to the next/previous photo or reel.
        List<DownloadedItem> current = adapter.getItems();
        if (current.isEmpty()) return;

        String[] paths = new String[current.size()];
        boolean[] video = new boolean[current.size()];
        int index = 0;
        for (int i = 0; i < current.size(); i++) {
            DownloadedItem it = current.get(i);
            paths[i] = it.file.getAbsolutePath();
            video[i] = it.isVideo;
            if (it.file.equals(item.file)) index = i;
        }

        Intent i = new Intent(requireContext(), ViewerActivity.class);
        i.putExtra(ViewerActivity.EXTRA_PATHS, paths);
        i.putExtra(ViewerActivity.EXTRA_VIDEO, video);
        i.putExtra(ViewerActivity.EXTRA_INDEX, index);
        startActivity(i);
    }

    /** Hand the file to another installed app (kept as an action-sheet option). */
    private void openExternal(DownloadedItem item) {
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    MediaRepository.authority(requireContext()), item.file);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, item.isVideo ? "video/*" : "image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception e) {
            toast("No app available to open this file.");
        }
    }

    @Override
    public void onActions(DownloadedItem item) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(0, pad, 0, pad * 2);

        TextView header = new TextView(requireContext());
        header.setText(item.getName());
        header.setPadding(dp(16), dp(16), dp(16), dp(16));
        header.setTextSize(15f);
        header.setSingleLine(true);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(header);

        addSheetRow(root, R.string.action_open, false, () -> { sheet.dismiss(); openExternal(item); });
        addSheetRow(root, R.string.action_share, false, () -> { sheet.dismiss(); shareOne(item); });
        addSheetRow(root, R.string.action_save_gallery, false, () -> { sheet.dismiss(); saveToGallery(item); });

        if (mode == MODE_LIBRARY) {
            addSheetRow(root, R.string.action_move, false, () -> { sheet.dismiss(); moveToAlbum(item); });
            addSheetRow(root, isVideoType() ? R.string.action_move_my_reels : R.string.action_move_my_photos,
                    false, () -> { sheet.dismiss(); singleMove(item, MediaRepository.FAV_DIR); });
            addSheetRow(root, R.string.action_move_delete, false,
                    () -> { sheet.dismiss(); singleMove(item, MediaRepository.TRASH_DIR); });
            addSheetRow(root, R.string.action_rename, false, () -> { sheet.dismiss(); rename(item); });
        } else if (mode == MODE_FAVORITES) {
            addSheetRow(root, R.string.action_remove_fav, false,
                    () -> { sheet.dismiss(); singleMove(item, MediaRepository.ALL); });
            addSheetRow(root, R.string.action_move_delete, false,
                    () -> { sheet.dismiss(); singleMove(item, MediaRepository.TRASH_DIR); });
            addSheetRow(root, R.string.action_rename, false, () -> { sheet.dismiss(); rename(item); });
        } else { // TRASH
            addSheetRow(root, R.string.action_restore, false,
                    () -> { sheet.dismiss(); singleMove(item, MediaRepository.ALL); });
        }

        addSheetRow(root, R.string.action_delete, true, () -> { sheet.dismiss(); confirmDelete(item); });

        sheet.setContentView(root);
        sheet.show();
    }

    private void addSheetRow(LinearLayout parent, int labelRes, boolean danger, Runnable action) {
        TextView row = new TextView(requireContext());
        row.setText(labelRes);
        row.setTextSize(15f);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setClickable(true);
        row.setFocusable(true);
        if (danger) row.setTextColor(0xFFD32F2F);
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setOnClickListener(x -> action.run());
        parent.addView(row);
    }

    private void shareOne(DownloadedItem item) {
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    MediaRepository.authority(requireContext()), item.file);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(item.isVideo ? "video/*" : "image/*");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share via"));
        } catch (Exception e) {
            toast("Couldn't share this file.");
        }
    }

    private void saveToGallery(DownloadedItem item) {
        boolean ok = repo.saveToGallery(item);
        toast(ok ? "Saved to your gallery" : "Failed to save to gallery");
    }

    private void singleMove(DownloadedItem item, String album) {
        File moved = repo.moveToAlbum(item, album);
        toast(moved != null ? "Moved" : "Move failed");
        reload();
    }

    private void moveToAlbum(DownloadedItem item) {
        List<String> albums = repo.albums(isVideoType());
        List<String> options = new ArrayList<>();
        options.add(MediaRepository.ALL + " (no album)");
        options.addAll(albums);
        options.add("New album…");

        CharSequence[] arr = options.toArray(new CharSequence[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("Move to album")
                .setItems(arr, (dialog, which) -> {
                    if (which == 0) {
                        singleMove(item, MediaRepository.ALL);
                    } else if (which == options.size() - 1) {
                        promptNewAlbum(item);
                    } else {
                        singleMove(item, options.get(which));
                    }
                })
                .show();
    }

    private void promptNewAlbum(DownloadedItem item) {
        final EditText input = new EditText(requireContext());
        input.setHint("Album name");
        new AlertDialog.Builder(requireContext())
                .setTitle("New album")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) singleMove(item, name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rename(DownloadedItem item) {
        final EditText input = new EditText(requireContext());
        input.setText(item.getBaseName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(requireContext())
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    File renamed = repo.rename(item, name);
                    toast(renamed != null ? "Renamed" : "Rename failed");
                    reloadItemsOnly();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(DownloadedItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete file?")
                .setMessage(item.getName())
                .setPositiveButton("Delete", (d, w) -> {
                    boolean ok = repo.delete(item);
                    toast(ok ? "Deleted" : "Delete failed");
                    reloadItemsOnly();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
