package com.example.instasaver;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
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

/** A grid of downloaded media for one type (videos = reels, or photos). */
public class MediaListFragment extends Fragment implements MediaAdapter.Listener {

    private static final String ARG_VIDEO = "is_video";

    private boolean isVideo;
    private MediaRepository repo;
    private MediaAdapter adapter;

    private RecyclerView recycler;
    private TextView emptyView;
    private Spinner albumSpinner;
    private Spinner sortSpinner;

    private String albumFilter = MediaRepository.ALL;
    private MediaRepository.Sort sort = MediaRepository.Sort.NEWEST;

    private BroadcastReceiver downloadComplete;

    public static MediaListFragment newInstance(boolean isVideo) {
        MediaListFragment f = new MediaListFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_VIDEO, isVideo);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_media_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        isVideo = getArguments() != null && getArguments().getBoolean(ARG_VIDEO);
        repo = new MediaRepository(requireContext());

        recycler = v.findViewById(R.id.recycler);
        emptyView = v.findViewById(R.id.emptyView);
        albumSpinner = v.findViewById(R.id.albumSpinner);
        sortSpinner = v.findViewById(R.id.sortSpinner);

        int span = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 4 : 3;
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), span));
        adapter = new MediaAdapter(this);
        recycler.setAdapter(adapter);

        setupSortSpinner();

        emptyView.setText(isVideo ? R.string.empty_reels : R.string.empty_photos);
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
        List<String> albums = new ArrayList<>();
        albums.add(MediaRepository.ALL);
        albums.addAll(repo.albums(isVideo));

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
        List<DownloadedItem> items = repo.list(isVideo, sort, albumFilter);
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
            requireContext().registerReceiver(downloadComplete, filter,
                    Context.RECEIVER_EXPORTED);
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
    // Item interactions
    // ------------------------------------------------------------------

    @Override
    public void onOpen(DownloadedItem item) {
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
        View v = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_actions, null);

        ((TextView) v.findViewById(R.id.actionHeader)).setText(item.getName());
        v.findViewById(R.id.actionOpen).setOnClickListener(x -> { sheet.dismiss(); onOpen(item); });
        v.findViewById(R.id.actionShare).setOnClickListener(x -> { sheet.dismiss(); share(item); });
        v.findViewById(R.id.actionSaveGallery).setOnClickListener(x -> { sheet.dismiss(); saveToGallery(item); });
        v.findViewById(R.id.actionMove).setOnClickListener(x -> { sheet.dismiss(); moveToAlbum(item); });
        v.findViewById(R.id.actionRename).setOnClickListener(x -> { sheet.dismiss(); rename(item); });
        v.findViewById(R.id.actionDelete).setOnClickListener(x -> { sheet.dismiss(); confirmDelete(item); });

        sheet.setContentView(v);
        sheet.show();
    }

    private void share(DownloadedItem item) {
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

    private void moveToAlbum(DownloadedItem item) {
        List<String> albums = repo.albums(isVideo);
        List<String> options = new ArrayList<>();
        options.add(MediaRepository.ALL + " (no album)");
        options.addAll(albums);
        options.add("New album…");

        CharSequence[] arr = options.toArray(new CharSequence[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("Move to album")
                .setItems(arr, (dialog, which) -> {
                    if (which == 0) {
                        doMove(item, MediaRepository.ALL);
                    } else if (which == options.size() - 1) {
                        promptNewAlbum(item);
                    } else {
                        doMove(item, options.get(which));
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
                    if (!name.isEmpty()) doMove(item, name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doMove(DownloadedItem item, String album) {
        File moved = repo.moveToAlbum(item, album);
        toast(moved != null ? "Moved" : "Move failed");
        reload();
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

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
