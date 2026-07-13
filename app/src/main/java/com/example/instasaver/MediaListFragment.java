package com.example.instasaver;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
    private static final String ARG_ALBUM = "album";

    static final int MODE_LIBRARY = 0;
    static final int MODE_FAVORITES = 1;
    static final int MODE_TRASH = 2;
    static final int MODE_ALBUM = 3;

    static final int TYPE_VIDEO = 0;
    static final int TYPE_PHOTO = 1;
    static final int TYPE_BOTH = 2;

    private int mode;
    private int type;
    private String fixedAlbum; // used only in MODE_ALBUM

    private MediaRepository repo;
    private MediaAdapter adapter;

    private RecyclerView recycler;
    private TextView emptyView;
    private TextView countLabel;
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

    public static MediaListFragment albumTyped(String album, boolean isVideo) {
        MediaListFragment f = build(MODE_ALBUM, isVideo ? TYPE_VIDEO : TYPE_PHOTO);
        f.getArguments().putString(ARG_ALBUM, album);
        return f;
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
        fixedAlbum = getArguments() != null ? getArguments().getString(ARG_ALBUM) : null;
        repo = new MediaRepository(requireContext());

        recycler = v.findViewById(R.id.recycler);
        emptyView = v.findViewById(R.id.emptyView);
        countLabel = v.findViewById(R.id.countLabel);
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

        // Album navigation now happens via folders in the vault, so the in-list
        // album picker is no longer shown anywhere.
        albumSpinner.setVisibility(View.GONE);

        emptyView.setText(emptyMessage());
    }

    private int emptyMessage() {
        if (mode == MODE_ALBUM) return R.string.empty_album;
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

    private void reload() {
        if (!isAdded()) return;
        reloadItemsOnly();
    }

    /** Public reload hook (e.g. after files are imported into this album). */
    public void refresh() {
        reload();
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
            case MODE_ALBUM:
                items = fixedAlbum == null
                        ? new ArrayList<>() : repo.list(isVideoType(), sort, fixedAlbum);
                break;
            case MODE_LIBRARY:
            default:
                items = repo.list(isVideoType(), sort, albumFilter);
                break;
        }
        adapter.submit(items);
        int n = items.size();
        countLabel.setText(n + (n == 1 ? " file" : " files"));
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
            addSelBtnHold(R.string.sel_move_album, this::bulkMoveToAlbum);
            addSelBtn(R.string.sel_to_gallery, this::bulkMoveToGallery);
            addSelBtn(R.string.sel_to_delete, () -> bulkMove(MediaRepository.TRASH_DIR));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        } else if (mode == MODE_FAVORITES) {
            addSelBtn(R.string.action_remove_fav, () -> bulkMove(MediaRepository.ALL));
            addSelBtn(R.string.sel_to_gallery, this::bulkMoveToGallery);
            addSelBtn(R.string.sel_to_delete, () -> bulkMove(MediaRepository.TRASH_DIR));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        } else if (mode == MODE_ALBUM) {
            addSelBtn(R.string.sel_to_gallery, this::bulkMoveToGallery);
            addSelBtn(R.string.action_remove_album, () -> bulkMove(MediaRepository.ALL));
            addSelBtn(R.string.sel_to_delete, () -> bulkMove(MediaRepository.TRASH_DIR));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        } else { // TRASH
            addSelBtn(R.string.sel_restore, () -> bulkMove(MediaRepository.ALL));
            addSelBtn(R.string.sel_share, this::bulkShare);
            addSelBtn(R.string.sel_delete_now, this::bulkDelete);
        }
    }

    /** Move all selected library items into a chosen (or new) album. */
    private void bulkMoveToAlbum() {
        final List<DownloadedItem> sel = adapter.getSelected();
        if (sel.isEmpty()) return;
        Haptics.tick(getContext());
        List<String> options = new ArrayList<>(repo.albums(isVideoType()));
        options.add("New album…");
        final CharSequence[] arr = options.toArray(new CharSequence[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("Move " + sel.size() + " to album")
                .setItems(arr, (dialog, which) -> {
                    if (which == options.size() - 1) {
                        final EditText input = new EditText(requireContext());
                        input.setHint("Album name");
                        new AlertDialog.Builder(requireContext())
                                .setTitle("New album")
                                .setView(input)
                                .setPositiveButton("Create", (d, w) -> {
                                    String name = input.getText().toString().trim();
                                    if (!name.isEmpty()) bulkMove(name);
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        bulkMove(options.get(which));
                    }
                })
                .show();
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

    /** Move-to-album is a private feature: it requires a 3-second press to arm. */
    private static final long HOLD_MS = 3000;

    private void addSelBtnHold(int labelRes, Runnable action) {
        Button b = new Button(requireContext(),
                null, android.R.attr.borderlessButtonStyle);
        b.setText(labelRes);
        b.setTextColor(getResources().getColor(R.color.white));
        b.setTextSize(12f);
        b.setAllCaps(false);
        attachHold(b, action);
        selectionButtons.addView(b);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachHold(View v, Runnable action) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable fire = () -> action.run();
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.postDelayed(fire, HOLD_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(fire);
                    return true;
                default:
                    return false;
            }
        });
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

        // When opening the viewer from inside the vault, mark it as an internal
        // navigation so the vault auto-lock doesn't eject us to the home screen
        // when the viewer is closed.
        if (mode == MODE_ALBUM) {
            VaultLock.beginInternalActivity();
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
            addSheetRowHold(root, R.string.action_move, () -> { sheet.dismiss(); moveToAlbum(item); });
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
        } else if (mode == MODE_ALBUM) {
            addSheetRow(root, R.string.action_move_to_gallery, false,
                    () -> { sheet.dismiss(); singleMoveToGallery(item); });
            addSheetRow(root, R.string.action_remove_album, false,
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

    /** A private action row that only fires after a 5-second press. */
    private void addSheetRowHold(LinearLayout parent, int labelRes, Runnable action) {
        TextView row = new TextView(requireContext());
        row.setText(labelRes);
        row.setTextSize(15f);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        attachHold(row, action);
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

    private void singleMoveToGallery(DownloadedItem item) {
        boolean ok = repo.moveToGallery(item);
        toast(ok ? "Moved back to gallery" : "Couldn't move to gallery");
        reload();
    }

    private void bulkMoveToGallery() {
        List<DownloadedItem> sel = adapter.getSelected();
        if (sel.isEmpty()) return;
        int ok = 0;
        for (DownloadedItem item : sel) if (repo.moveToGallery(item)) ok++;
        toast(ok + " moved back to gallery");
        adapter.clearSelection();
        reload();
    }

    private void moveToAlbum(DownloadedItem item) {
        Haptics.tick(getContext());
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
