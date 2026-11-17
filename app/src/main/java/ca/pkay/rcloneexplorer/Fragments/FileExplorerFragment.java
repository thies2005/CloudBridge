package ca.pkay.rcloneexplorer.Fragments;

import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivity;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartActivityForResult;
import static ca.pkay.rcloneexplorer.util.ActivityHelper.tryStartService;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.leinardi.android.speeddial.SpeedDialActionItem;
import com.leinardi.android.speeddial.SpeedDialOverlayLayout;
import com.leinardi.android.speeddial.SpeedDialView;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

import ca.pkay.rcloneexplorer.Activities.MainActivity;
import ca.pkay.rcloneexplorer.BuildConfig;
import ca.pkay.rcloneexplorer.Dialogs.Dialogs;
import ca.pkay.rcloneexplorer.Dialogs.FilePropertiesDialog;
import ca.pkay.rcloneexplorer.Dialogs.GoToDialog;
import ca.pkay.rcloneexplorer.Dialogs.InputDialog;
import ca.pkay.rcloneexplorer.Dialogs.LinkDialog;
import ca.pkay.rcloneexplorer.Dialogs.LoadingDialog;
import ca.pkay.rcloneexplorer.Dialogs.OpenAsDialog;
import ca.pkay.rcloneexplorer.Dialogs.ServeDialog;
import ca.pkay.rcloneexplorer.Dialogs.SortDialog;
import ca.pkay.rcloneexplorer.FileComparators;
import ca.pkay.rcloneexplorer.FilePicker;
import ca.pkay.rcloneexplorer.Items.DirectoryObject;
import ca.pkay.rcloneexplorer.Items.FileItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject;
import ca.pkay.rcloneexplorer.Items.Task;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter;
import ca.pkay.rcloneexplorer.Services.StreamingService;
import ca.pkay.rcloneexplorer.Services.ThumbnailsLoadingService;
import ca.pkay.rcloneexplorer.util.ActivityHelper;
import ca.pkay.rcloneexplorer.util.FLog;
import ca.pkay.rcloneexplorer.util.LargeParcel;
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager;
import ca.pkay.rcloneexplorer.workmanager.SyncManager;
import de.felixnuesse.ui.BreadcrumbView;
import de.schuelken.cloudbridge.cache.CacheManager;
import de.schuelken.cloudbridge.install.ApkInstallHelper;
import de.schuelken.cloudbridge.search.SearchEngine;
import de.schuelken.cloudbridge.search.SearchFilter;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java9.util.stream.Collectors;
import java9.util.stream.StreamSupport;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class FileExplorerFragment extends Fragment implements   FileExplorerRecyclerViewAdapter.OnClickListener,
                                                                SwipeRefreshLayout.OnRefreshListener,
                                                                BreadcrumbView.OnClickListener,
                                                                OpenAsDialog.OnClickListener,
                                                                InputDialog.OnPositive,
                                                                GoToDialog.Callbacks,
                                                                SortDialog.OnClickListener,
                                                                ServeDialog.Callback {

    public static final int STREAMING_INTENT_RESULT = 468;
    private static final String TAG = "FileExplorerFragment";
    private static final String ARG_REMOTE = "remote_param";
    private static final String SHARED_PREFS_SORT_ORDER = "ca.pkay.rcexplorer.sort_order";
    private static final int FILE_PICKER_UPLOAD_RESULT = 186;
    private static final int FILE_PICKER_DOWNLOAD_RESULT = 204;
    private static final int FILE_PICKER_SYNC_RESULT = 45;
    private final String SAVED_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SAVED_PATH";
    private final String SAVED_CONTENT = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SAVED_CONTENT";
    private final String SAVED_SEARCH_MODE = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SEARCH_MODE";
    private final String SAVED_SEARCH_STRING = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SEARCH_STRING";
    private final String SAVED_RENAME_ITEM = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_RENAME_ITEM";
    private final String SAVED_SELECTED_ITEMS = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SELECTED_ITEMS";
    private final String SAVED_IS_IN_MOVE_MODE = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_IS_IN_MOVE_MODE";
    private final String SAVED_START_AT_BOOT = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_START_AT_BOOT";
    private final String SAVED_DOWNLOAD_LIST = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_DOWNLOAD_LIST";
    private final String SAVED_MOVE_START_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_MOVE_START_PATH";
    private final String SAVED_SYNC_DIRECTION = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SYNC_DIRECTION";
    private final String SAVED_SYNC_REMOTE_PATH = "ca.pkay.rcexplorer.FILE_EXPLORER_FRAG_SYNC_REMOTE_PATH";
    private String originalToolbarTitle;
    private Stack<String> pathStack;
    private Map<String, Integer> directoryPosition;
    private DirectoryObject directoryObject;
    private List<FileItem> moveList;
    private String moveStartPath;
    private List<FileItem> downloadList;
    private FileItem renameItem;
    private BreadcrumbView breadcrumbView;
    private Rclone rclone;
    private RemoteItem remote;
    private String remoteName;
    private FileExplorerRecyclerViewAdapter recyclerViewAdapter;
    private LinearLayoutManager recyclerViewLinearLayoutManager;
    private SwipeRefreshLayout swipeRefreshLayout;
    private View searchBar;
    private View searchButton;
    private View searchClear;
    private AsyncTask fetchDirectoryTask;
    private boolean isRunning;
    private int sortOrder;
    private boolean isInMoveMode;
    private SpeedDialView fab;
    private MenuItem menuSelectAll;
    private MenuItem menuGoTo;
    private MenuItem menuLink;
    private MenuItem menuHttpServe;
    private MenuItem menuEmptyTrash;
    private Boolean isDarkTheme;
    private Boolean isSearchMode;
    private String searchString;
    private String syncRemotePath;
    private int syncDirection;
    private boolean is720dp;
    private boolean showThumbnails;
    private boolean isThumbnailsServiceRunning;
    private boolean startAtRoot;
    private boolean goToDefaultSet;
    private Context context;
    private String thumbnailServerAuth;
    private int thumbnailServerPort;
    private boolean wrapFilenames;
    private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;

    private enum SearchScope { FOLDER, RECURSIVE, INDEXED }
    private SearchScope searchScope = SearchScope.FOLDER;
    private SearchFilter currentSearchFilter = new SearchFilter(SearchFilter.TypeFilter.ALL, 0L, 0L);
    private View searchFiltersBar;
    private View searchStatusBar;
    private android.widget.TextView searchStatusText;
    private final Handler searchDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearchRunnable;
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<Process> recursiveSearchProcess = new AtomicReference<>();
    private File pendingApkInstallFile;
    private final ActivityResultLauncher<Intent> installPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (pendingApkInstallFile != null && context != null) {
                    File file = pendingApkInstallFile;
                    pendingApkInstallFile = null;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getPackageManager().canRequestPackageInstalls()) {
                        ApkInstallHelper.launchViewIntent(context, file, ApkInstallHelper.MIME_APK);
                    }
                }
            });

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public FileExplorerFragment() {
    }

    @SuppressWarnings("unused")
    public static FileExplorerFragment newInstance(RemoteItem remoteItem) {
        FileExplorerFragment fragment = new FileExplorerFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_REMOTE, remoteItem);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() == null) {
            return;
        }

        if (getContext() == null) {
            return;
        }
        setHasOptionsMenu(true);

        remote = getArguments().getParcelable(ARG_REMOTE);
        if (remote == null) {
            return;
        }
        remoteName = remote.getName();
        pathStack = new Stack<>();
        directoryPosition = new HashMap<>();
        directoryObject = new DirectoryObject();

        String path;
        if (savedInstanceState == null) {
            path = "//" + remoteName;
            directoryObject.setPath(path);
        } else {
            path = savedInstanceState.getString(SAVED_PATH);
            if (path == null) {
                return;
            }
            directoryObject.setPath(path);
            ArrayList<FileItem> savedContent = savedInstanceState.getParcelableArrayList(SAVED_CONTENT);
            if(null == savedContent){
                directoryObject.clear();
            } else {
                directoryObject.setContent(savedContent);
            }

            buildStackFromPath(remoteName, path);
            renameItem = savedInstanceState.getParcelable(SAVED_RENAME_ITEM);
        }

        originalToolbarTitle = ((FragmentActivity) context).getTitle().toString();
        setTitle();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sortOrder = sharedPreferences.getInt(SHARED_PREFS_SORT_ORDER, SortDialog.ALPHA_ASCENDING);
        showThumbnails = sharedPreferences.getBoolean(getString(R.string.pref_key_show_thumbnails), false);
        isDarkTheme = ActivityHelper.isDarkTheme(this.getActivity());
        goToDefaultSet = sharedPreferences.getBoolean(getString(R.string.pref_key_go_to_default_set), false);
        String wrapFilenamesKey = getString(R.string.pref_key_wrap_filenames);
        prefChangeListener = (pref, key) -> {
            if (key.equals(wrapFilenamesKey) && recyclerViewAdapter != null) {
                recyclerViewAdapter.setWrapFileNames(pref.getBoolean(wrapFilenamesKey, true));
            }
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefChangeListener);
        wrapFilenames = sharedPreferences.getBoolean(getString(R.string.pref_key_wrap_filenames), true);

        if (goToDefaultSet) {
            startAtRoot = sharedPreferences.getBoolean(getString(R.string.pref_key_start_at_root), false);
        }

        rclone = new Rclone(getContext());

        isSearchMode = false;
        isInMoveMode = false;
        is720dp = getResources().getBoolean(R.bool.is720dp);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_file_explorer_list, container, false);
        if (savedInstanceState != null) {
            startAtRoot = savedInstanceState.getBoolean(SAVED_START_AT_BOOT);
        }

        if (showThumbnails) {
            initializeThumbnailParams();
            startThumbnailService();
        }

        swipeRefreshLayout = view.findViewById(R.id.file_explorer_srl);
        swipeRefreshLayout.setOnRefreshListener(this);

        Context context = view.getContext();

        RecyclerView recyclerView = view.findViewById(R.id.file_explorer_list);
        recyclerViewLinearLayoutManager = new LinearLayoutManager(context);
        recyclerView.setLayoutManager(recyclerViewLinearLayoutManager);
        View emptyFolderView = view.findViewById(R.id.empty_folder_view);
        View noSearchResultsView = view.findViewById(R.id.no_search_results_view);
        recyclerViewAdapter = new FileExplorerRecyclerViewAdapter(context, emptyFolderView, noSearchResultsView, this);
        recyclerViewAdapter.showThumbnails(showThumbnails);
        recyclerViewAdapter.setWrapFileNames(wrapFilenames);
        recyclerView.setAdapter(recyclerViewAdapter);

        if (remote.isRemoteType(RemoteItem.SFTP) && !goToDefaultSet & savedInstanceState == null) {
            showSFTPgoToDialog();
        } else {
            if (directoryObject.isDirectoryContentEmpty()) {
                fetchDirectoryTask = new FetchDirectoryContent().execute();
                swipeRefreshLayout.setRefreshing(true);
            } else {
                recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            }
        }

        fab = view.findViewById(R.id.fab_fragment_file_explorer_list);
        fab.setOverlayLayout((SpeedDialOverlayLayout)view.findViewById(R.id.fab_overlay));
        fab.setOnActionSelectedListener(actionItem -> {
            switch (actionItem.getId()) {
                case R.id.fab_add_folder:
                    onCreateNewDirectory();
                    break;
                case R.id.fab_upload:
                    onUploadFiles();
                    break;
            }
            return false;
        });
        fab.addActionItem(new SpeedDialActionItem.Builder(R.id.fab_upload, R.drawable.ic_file_upload)
                .setFabImageTintColor(ResourcesCompat.getColor(getResources(), R.color.colorAccent, this.context.getTheme()))
                .setLabel(getString(R.string.fab_upload_files))
                .create());
        fab.addActionItem(new SpeedDialActionItem.Builder(R.id.fab_add_folder, R.drawable.ic_create_new_folder)
                .setFabImageTintColor(ResourcesCompat.getColor(getResources(), R.color.colorAccent, this.context.getTheme()))
                .setLabel(getString(R.string.fab_new_folder))
                .create());

        breadcrumbView = ((FragmentActivity) context).findViewById(R.id.breadcrumb_view);
        breadcrumbView.setOnClickListener(this);
        breadcrumbView.setVisibility(View.VISIBLE);
        breadcrumbView.addCrumb(remote.getDisplayName(), "//" + remoteName);
        if (savedInstanceState != null) {
            if (!directoryObject.getCurrentPath().equals("//" + remoteName)) {
                breadcrumbView.buildBreadCrumbsFromPath(directoryObject.getCurrentPath());
            }
        }

        searchBar = ((FragmentActivity) context).findViewById(R.id.search_bar);
        searchButton = ((FragmentActivity) context).findViewById(R.id.searchButton);
        searchButton.setOnClickListener(click -> searchClicked());
        searchButton.setVisibility(View.VISIBLE);

        searchClear = ((FragmentActivity) context).findViewById(R.id.search_clear);
        searchClear.setOnClickListener(v -> {
            EditText searchField = searchBar.findViewById(R.id.search_field);
            if (searchField.getText().toString().isEmpty()) {
                searchClicked();
            } else {
                searchDirContent("");
                searchField.setText("");
                InputMethodManager keyboard = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null && getActivity().getCurrentFocus() != null) {
                    keyboard.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
                }
            }
        });
        setupSearchFiltersUi();

        if (view.findViewById(R.id.background) != null) {
            view.findViewById(R.id.background).setOnClickListener(v -> onClickOutsideOfView());
        }

        setBottomBarClickListeners(view);

        if (savedInstanceState != null && savedInstanceState.getBoolean(SAVED_SEARCH_MODE, false)) {
            searchString = savedInstanceState.getString(SAVED_SEARCH_STRING);
            searchClicked();
        }

        isRunning = true;
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceivers();

        if (showThumbnails) {
            startThumbnailService();
        }

        if (directoryObject.isContentValid()) {
            return;
        }
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent(true).execute();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(SAVED_PATH, directoryObject.getCurrentPath());
        ArrayList<FileItem> content = new ArrayList<>(directoryObject.getDirectoryContent());
        outState.putParcelableArrayList(SAVED_CONTENT, content);
        outState.putBoolean(SAVED_SEARCH_MODE, isSearchMode);
        outState.putParcelable(SAVED_RENAME_ITEM, renameItem);
        outState.putBoolean(SAVED_START_AT_BOOT, startAtRoot);
        outState.putInt(SAVED_SYNC_DIRECTION, syncDirection);
        if (isSearchMode) {
            outState.putString(SAVED_SEARCH_STRING, searchString);
        }
        if (recyclerViewAdapter.isInSelectMode()) {
            outState.putParcelableArrayList(SAVED_SELECTED_ITEMS, new ArrayList<>(recyclerViewAdapter.getSelectedItems()));
        }
        if (isInMoveMode) {
            outState.putBoolean(SAVED_IS_IN_MOVE_MODE, true);
            outState.putParcelableArrayList(SAVED_SELECTED_ITEMS, new ArrayList<>(moveList));
        }
        if (downloadList != null && !downloadList.isEmpty()) {
            outState.putParcelableArrayList(SAVED_DOWNLOAD_LIST, new ArrayList<>(downloadList));
        }
        if (moveStartPath != null) {
            outState.putString(SAVED_MOVE_START_PATH, moveStartPath);
        }
        if (syncRemotePath != null) {
            outState.putString(SAVED_SYNC_REMOTE_PATH, syncRemotePath);
        }
        if (LargeParcel.calculateBundleSize(outState) > 250 * 1024) {
            outState.remove(SAVED_CONTENT);
        }
    }



    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }

        List<FileItem> selectedItems = savedInstanceState.getParcelableArrayList(SAVED_SELECTED_ITEMS);
        boolean moveMode = savedInstanceState.getBoolean(SAVED_IS_IN_MOVE_MODE, false);
        if (selectedItems != null && !selectedItems.isEmpty() && !moveMode) {
            recyclerViewAdapter.setSelectedItems(selectedItems);
            handleFilesSelected();
        }

        if (moveMode) {
            isInMoveMode = true;
            moveList = savedInstanceState.getParcelableArrayList(SAVED_SELECTED_ITEMS);
            recyclerViewAdapter.setMoveMode(true);
            ((FragmentActivity) context).setTitle(getString(R.string.select_destination));
            ((FragmentActivity) context).findViewById(R.id.move_bar).setVisibility(View.VISIBLE);
            fab.hide();
            fab.setVisibility(View.INVISIBLE);
            setFabBehaviour(false);
        }
        downloadList = savedInstanceState.getParcelableArrayList(SAVED_DOWNLOAD_LIST);
        moveStartPath = savedInstanceState.getString(SAVED_MOVE_START_PATH);
        syncDirection = savedInstanceState.getInt(SAVED_SYNC_DIRECTION, -1);
        syncRemotePath = savedInstanceState.getString(SAVED_SYNC_REMOTE_PATH);
    }

    private void setFabBehaviour(boolean enableSnackBarBehaviour) {
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) fab.getLayoutParams();

        if (enableSnackBarBehaviour) {
            params.setBehavior(new SpeedDialView.ScrollingViewSnackbarBehavior());
            fab.requestLayout();
        } else {
            params.setBehavior(new SpeedDialView.NoBehavior());
            fab.requestLayout();
        }
    }

    private void setTitle() {
        String title;
        if (remote.isCrypt()) {
            title = "crypt" + " " + "(" + remote.getTypeReadable() + ")";
        } else {
            title = remote.getTypeReadable();
        }
        ((FragmentActivity) context).setTitle(title);
    }

    private void buildStackFromPath(String remote, String path) {
        String root = "//" + remote;
        if (root.equals(path)) {
            return;
        }
        pathStack.clear();
        pathStack.push(root);

        int index = 0;

        while ((index = path.indexOf("/", index)) > 0) {
            String p = path.substring(0, index);
            pathStack.push(p);
            index++;
        }
    }

    private void registerReceivers() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(getString(R.string.background_service_broadcast));
        LocalBroadcastManager.getInstance(context).registerReceiver(backgroundTaskBroadcastReceiver, intentFilter);
    }

    private BroadcastReceiver backgroundTaskBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String broadcastRemote = intent.getStringExtra(getString(R.string.background_service_broadcast_data_remote));
            String broadcastPath = intent.getStringExtra(getString(R.string.background_service_broadcast_data_path));
            String broadcastPath2 = intent.getStringExtra(getString(R.string.background_service_broadcast_data_path2));
            String path = directoryObject.getCurrentPath();
            if (!remoteName.equals(broadcastRemote)) {
                return;
            }

            if (path.equals(broadcastPath)) {
                if (fetchDirectoryTask != null) {
                    fetchDirectoryTask.cancel(true);
                }
                if (directoryObject.isPathInCache(broadcastPath)) {
                    directoryObject.removePathFromCache(broadcastPath);
                }
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            } else if (directoryObject.isPathInCache(broadcastPath)) {
                directoryObject.removePathFromCache(broadcastPath);
            }

            if (broadcastPath2 == null) {
                return;
            }

            if (path.equals(broadcastPath2)) {
                if (fetchDirectoryTask != null) {
                    fetchDirectoryTask.cancel(true);
                }
                swipeRefreshLayout.setRefreshing(false);
                if (directoryObject.isPathInCache(broadcastPath2)) {
                    directoryObject.removePathFromCache(broadcastPath2);
                }
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            } else if (directoryObject.isPathInCache(broadcastPath2)) {
                directoryObject.removePathFromCache(broadcastPath2);
            }
        }
    };

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_UPLOAD_RESULT && resultCode == FragmentActivity.RESULT_OK) {
            @SuppressWarnings("unchecked")
            ArrayList<File> result = (ArrayList<File>) data.getSerializableExtra(FilePicker.FILE_PICKER_RESULT);
            EphemeralTaskManager.Companion.queueUploads(context, remote, result, directoryObject.getCurrentPath());
        } else if (requestCode == FILE_PICKER_DOWNLOAD_RESULT) {
            if (resultCode != FragmentActivity.RESULT_OK) {
                downloadList.clear();
                return;
            }
            String selectedPath = data.getStringExtra(FilePicker.FILE_PICKER_RESULT);
            recyclerViewAdapter.cancelSelection();

            EphemeralTaskManager.Companion.queueDownloads(context, remote, downloadList, selectedPath);
            downloadList.clear();
        } else if (requestCode == FILE_PICKER_SYNC_RESULT && resultCode == FragmentActivity.RESULT_OK) {
            String path = data.getStringExtra(FilePicker.FILE_PICKER_RESULT);

            Task ephemeralTask = new Task(-1);
            ephemeralTask.setRemoteId(remote.getName());
            ephemeralTask.setLocalPath(path);
            ephemeralTask.setDirection(syncDirection);
            ephemeralTask.setRemotePath(syncRemotePath);
            (new SyncManager(this.context)).queueEphemeral(ephemeralTask);

        } else if (requestCode == STREAMING_INTENT_RESULT) {
            Intent serveIntent = new Intent(getContext(), StreamingService.class);
            context.stopService(serveIntent);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.file_explorer_folder_menu, menu);
        menuSelectAll = menu.findItem(R.id.action_select_all);
        menuGoTo = menu.findItem(R.id.action_go_to);
        menuLink = menu.findItem(R.id.action_link);
        menuHttpServe = menu.findItem(R.id.action_serve);
        menuEmptyTrash = menu.findItem(R.id.action_empty_trash);

        if (!remote.hasTrashCan()) {
            menu.findItem(R.id.action_empty_trash).setVisible(false);
        }
        Boolean cachedLinkSupport = CacheManager.getInstance(context).getPublicLinkSupport(remote.getName());
        if (!remote.hasLinkSupport() || Boolean.FALSE.equals(cachedLinkSupport)) {
            menu.findItem(R.id.action_link).setVisible(false);
        }
        if (!remote.isRemoteType(RemoteItem.SFTP)) {
            menu.findItem(R.id.action_go_to).setVisible(false);
        }
        if (!remote.hasSyncSupport()) {
            menu.findItem(R.id.action_sync).setVisible(false);
        }

        if (isInMoveMode || recyclerViewAdapter.isInSelectMode()) {
            setOptionsMenuVisibility(false);
        }
    }

    private void setOptionsMenuVisibility(boolean setVisible) {
        if (menuSelectAll == null || menuGoTo == null || menuLink == null || menuHttpServe == null || menuEmptyTrash == null) {
            return;
        }

        menuHttpServe.setVisible(setVisible);
        if (!setVisible && isInMoveMode) {
            menuSelectAll.setVisible(false);
        } else {
            menuSelectAll.setVisible(true);
        }
        if (!remote.isCrypt()) {
            menuLink.setVisible(setVisible);
        }
        if (remote.hasTrashCan()) {
            menuEmptyTrash.setVisible(setVisible);
        }
        if (remote.isRemoteType(RemoteItem.SFTP)) {
            menuGoTo.setVisible(setVisible);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.action_sort:
                showSortMenu();
                return true;
            case R.id.action_select_all:
                recyclerViewAdapter.toggleSelectAll();
                return true;
            case R.id.action_serve:
                serve();
                return true;
            case R.id.action_empty_trash:
                emptyTrash();
                return true;
            case R.id.action_link:
                new LinkTask().execute(directoryObject.getCurrentPath());
                return true;
            case R.id.action_sync:
                showSyncDialog(directoryObject.getCurrentPath());
                return true;
            case R.id.action_go_to:
                showSFTPgoToDialog();
                return true;
            case android.R.id.home:
                if (isInMoveMode) {
                    cancelMoveClicked();
                } else if (recyclerViewAdapter.isInSelectMode()) {
                    recyclerViewAdapter.cancelSelection();
                } else {
                    ((MainActivity)context).openNavigationDrawer();
                }
                return true;
            default:
                    return super.onOptionsItemSelected(item);
        }
    }

    private void serve() {
        ServeDialog serveDialog = new ServeDialog();
        serveDialog.show(getChildFragmentManager(), "serve dialog");
    }

    // serve callback
    @Override
    public void onServeOptionsSelected(int protocol, boolean allowRemoteAccess, String user, String password) {
        // GH-87: Release old stream
        context.stopService(new Intent(context, StreamingService.class));

        Intent intent = new Intent(getContext(), StreamingService.class);
        intent.putExtra(StreamingService.SERVE_PATH_ARG, directoryObject.getCurrentPath());
        intent.putExtra(StreamingService.REMOTE_ARG, remote);
        intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
        intent.putExtra(StreamingService.ALLOW_REMOTE_ACCESS, allowRemoteAccess);
        intent.putExtra(StreamingService.AUTHENTICATION_USERNAME, user);
        intent.putExtra(StreamingService.AUTHENTICATION_PASSWORD, password);

        switch (protocol) {
            case Rclone.SERVE_PROTOCOL_HTTP: // HTTP
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_HTTP);
                break;
            case Rclone.SERVE_PROTOCOL_FTP: // FTP
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_FTP);
                break;
            case Rclone.SERVE_PROTOCOL_DLNA: // DLNA
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_DLNA);
                break;
            case Rclone.SERVE_PROTOCOL_WEBDAV: // Webdav
                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_WEBDAV);
                break;
            default:
                return;
        }
        tryStartService(context, intent);
    }

    private void emptyTrash() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(R.string.empty_trash_confirmation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> new EmptyTrashTask().execute())
                .show();
    }

    private void showSFTPgoToDialog() {
        GoToDialog goToDialog = new GoToDialog();
        goToDialog.show(getChildFragmentManager(), "go to dialog");
    }

    /*
     * Swipe to refresh
     */
    @Override
    public void onRefresh() {
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        fetchDirectoryTask = new FetchDirectoryContent(true).execute();
    }

    private void startThumbnailService() {
        if(RemoteItem.SAFW == remote.getType()){
            // safdav also serves files for thumbnails
            return;
        }
        Intent serveIntent = new Intent(getContext(), ThumbnailsLoadingService.class);
        serveIntent.putExtra(ThumbnailsLoadingService.REMOTE_ARG, remote);
        serveIntent.putExtra(ThumbnailsLoadingService.HIDDEN_PATH, thumbnailServerAuth);
        serveIntent.putExtra(ThumbnailsLoadingService.SERVER_PORT, thumbnailServerPort);
        tryStartService(context, serveIntent);
        isThumbnailsServiceRunning = true;
    }

    private void initializeThumbnailParams() {
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[16];
        random.nextBytes(values);
        thumbnailServerAuth = Base64.encodeToString(values, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
        thumbnailServerPort = allocatePort(29179, true);
    }

    private static int allocatePort(int port, boolean allocateFallback) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            if (allocateFallback) {
                return allocatePort(0, false);
            }
        }
        throw new IllegalStateException("No port available");
    }

    private void setupSearchFiltersUi() {
        FragmentActivity activity = getActivity();
        if (activity == null) return;
        searchFiltersBar = activity.findViewById(R.id.search_filters_bar);
        searchStatusBar = activity.findViewById(R.id.search_status_bar);
        searchStatusText = activity.findViewById(R.id.search_status_text);
        View cancelBtn = activity.findViewById(R.id.search_cancel_btn);
        if (cancelBtn != null) {
            cancelBtn.setOnClickListener(v -> cancelActiveSearch());
        }
        ChipGroup scopeGroup = activity.findViewById(R.id.search_scope_group);
        if (scopeGroup != null) {
            scopeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (checkedIds.isEmpty()) return;
                int id = checkedIds.get(0);
                if (id == R.id.chip_scope_recursive) {
                    searchScope = SearchScope.RECURSIVE;
                } else if (id == R.id.chip_scope_indexed) {
                    searchScope = SearchScope.INDEXED;
                    if (CacheManager.getInstance(context).getTier3EnabledRemotes().isEmpty()) {
                        Toast.makeText(context, R.string.search_indexed_enable_hint, Toast.LENGTH_LONG).show();
                    }
                } else {
                    searchScope = SearchScope.FOLDER;
                }
                searchDirContent(searchString != null ? searchString : "");
            });
        }

        Chip typeChip = activity.findViewById(R.id.chip_filter_type);
        if (typeChip != null) {
            typeChip.setOnClickListener(v -> showTypeFilterMenu(typeChip));
        }
        Chip sizeChip = activity.findViewById(R.id.chip_filter_size);
        if (sizeChip != null) {
            sizeChip.setOnClickListener(v -> showSizeFilterMenu(sizeChip));
        }
        Chip dateChip = activity.findViewById(R.id.chip_filter_date);
        if (dateChip != null) {
            dateChip.setOnClickListener(v -> showDateFilterMenu(dateChip));
        }
    }

    private void showTypeFilterMenu(Chip anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        SearchFilter.TypeFilter[] values = SearchFilter.TypeFilter.values();
        int[] labels = {
                R.string.search_filter_all, R.string.search_filter_images, R.string.search_filter_video,
                R.string.search_filter_audio, R.string.search_filter_docs, R.string.search_filter_archives,
                R.string.search_filter_apk, R.string.search_filter_folders
        };
        for (int i = 0; i < values.length; i++) {
            menu.getMenu().add(0, i, i, labels[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            SearchFilter.TypeFilter chosen = values[item.getItemId()];
            currentSearchFilter = new SearchFilter(chosen, currentSearchFilter.getMinSizeBytes(), currentSearchFilter.getMaxAgeMs());
            anchor.setText(labels[item.getItemId()]);
            anchor.setChecked(chosen != SearchFilter.TypeFilter.ALL);
            searchDirContent(searchString != null ? searchString : "");
            return true;
        });
        menu.show();
    }

    private void showSizeFilterMenu(Chip anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        long[] sizes = {0L, 1024L * 1024L, 10L * 1024L * 1024L, 100L * 1024L * 1024L};
        int[] labels = {
                R.string.search_filter_size_any, R.string.search_filter_size_gt_1mb,
                R.string.search_filter_size_gt_10mb, R.string.search_filter_size_gt_100mb
        };
        for (int i = 0; i < sizes.length; i++) {
            menu.getMenu().add(0, i, i, labels[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            long minSize = sizes[item.getItemId()];
            currentSearchFilter = new SearchFilter(currentSearchFilter.getType(), minSize, currentSearchFilter.getMaxAgeMs());
            anchor.setText(labels[item.getItemId()]);
            anchor.setChecked(minSize > 0L);
            searchDirContent(searchString != null ? searchString : "");
            return true;
        });
        menu.show();
    }

    private void showDateFilterMenu(Chip anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        long day = 24L * 60L * 60L * 1000L;
        long[] ages = {0L, 7L * day, 30L * day, 365L * day};
        int[] labels = {
                R.string.search_filter_date_any, R.string.search_filter_date_7d,
                R.string.search_filter_date_30d, R.string.search_filter_date_1y
        };
        for (int i = 0; i < ages.length; i++) {
            menu.getMenu().add(0, i, i, labels[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            long maxAge = ages[item.getItemId()];
            currentSearchFilter = new SearchFilter(currentSearchFilter.getType(), currentSearchFilter.getMinSizeBytes(), maxAge);
            anchor.setText(labels[item.getItemId()]);
            anchor.setChecked(maxAge > 0L);
            searchDirContent(searchString != null ? searchString : "");
            return true;
        });
        menu.show();
    }

    private void cancelActiveSearch() {
        if (pendingSearchRunnable != null) {
            searchDebounceHandler.removeCallbacks(pendingSearchRunnable);
        }
        Process proc = recursiveSearchProcess.getAndSet(null);
        if (proc != null) {
            proc.destroy();
        }
        if (searchStatusBar != null) {
            searchStatusBar.setVisibility(View.GONE);
        }
    }

    private void searchClicked() {
        FragmentActivity activity = getActivity();
        if (activity == null || searchBar == null || searchButton == null) return;
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);

        if (isSearchMode) {
            cancelActiveSearch();
            searchBar.setVisibility(View.GONE);
            if (searchFiltersBar != null) {
                searchFiltersBar.setVisibility(View.GONE);
            }
            searchButton.setVisibility(View.VISIBLE);
            searchScope = SearchScope.FOLDER;
            currentSearchFilter = new SearchFilter(SearchFilter.TypeFilter.ALL, 0L, 0L);
            recyclerViewAdapter.setSearchDisplay("", false);
            searchString = "";
            ((EditText) searchBar.findViewById(R.id.search_field)).setText("");
            recyclerViewAdapter.setSearchMode(false);
            isSearchMode = false;
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
        } else {
            searchBar.setVisibility(View.VISIBLE);
            if (searchFiltersBar != null) {
                searchFiltersBar.setVisibility(View.VISIBLE);
            }
            searchButton.setVisibility(View.GONE);
            recyclerViewAdapter.setSearchMode(true);
            isSearchMode = true;
            EditText search = searchBar.findViewById(R.id.search_field);
            search.requestFocus();
            if (keyboard != null) {
                keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelActiveSearch();
        if (searchBar != null) searchBar.setVisibility(View.GONE);
        if (searchFiltersBar != null) searchFiltersBar.setVisibility(View.GONE);
        if (searchStatusBar != null) searchStatusBar.setVisibility(View.GONE);
        if (searchButton != null) searchButton.setVisibility(View.GONE);
    }

    private void showOpenAsDialog(FileItem fileItem) {
        OpenAsDialog openAsDialog = new OpenAsDialog();
        openAsDialog
                .setFileItem(fileItem);
        if (getFragmentManager() != null) {
            openAsDialog.show(getChildFragmentManager(), "open as");
        }
    }

    /*
     * Open As dialog callbacks
     */
    @Override
    public void onClickText(FileItem fileItem) {
        new DownloadAndOpen(DownloadAndOpen.OPEN_AS_TEXT).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, fileItem);
    }

    @Override
    public void onClickAudio(FileItem fileItem) {
        new StreamTask(StreamTask.OPEN_AS_AUDIO).execute(fileItem);
    }

    @Override
    public void onClickVideo(FileItem fileItem) {
        new StreamTask(StreamTask.OPEN_AS_VIDEO).execute(fileItem);
    }

    @Override
    public void onClickImage(FileItem fileItem) {
        new DownloadAndOpen(DownloadAndOpen.OPEN_AS_IMAGE).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, fileItem);
    }

    private void showFileProperties(FileItem fileItem) {
        FilePropertiesDialog filePropertiesDialog = new FilePropertiesDialog()
                .setFile(fileItem)
                .setRemote(remote);
        if (remote.isCrypt()) {
            filePropertiesDialog.withHashCalculations(false);
        }
        if (getFragmentManager() != null) {
            filePropertiesDialog.show(getChildFragmentManager(), "file properties");
        }
    }

    private void setBottomBarClickListeners(final View view) {
        view.findViewById(R.id.file_download).setOnClickListener(v -> {
            downloadList = new ArrayList<>(recyclerViewAdapter.getSelectedItems());
            downloadFiles();
        });

        view.findViewById(R.id.file_move).setOnClickListener(v -> moveFiles(recyclerViewAdapter.getSelectedItems()));

        view.findViewById(R.id.file_rename).setOnClickListener(v -> {
            renameItem = recyclerViewAdapter.getSelectedItems().get(0);
            renameFiles();
        });

        view.findViewById(R.id.file_delete).setOnClickListener(v -> deleteFiles(recyclerViewAdapter.getSelectedItems()));
        view.findViewById(R.id.cancel_move).setOnClickListener(v -> cancelMoveClicked());
        view.findViewById(R.id.select_move).setOnClickListener(v -> moveLocationSelected());
        view.findViewById(R.id.new_folder).setOnClickListener(v -> onCreateNewDirectory());

        ((EditText)searchBar.findViewById(R.id.search_field)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchDirContent(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
    }

    private void searchDirContent(String search) {
        searchString = search != null ? search : "";
        if (pendingSearchRunnable != null) {
            searchDebounceHandler.removeCallbacks(pendingSearchRunnable);
        }

        final String querySnapshot = searchString;
        final SearchScope scopeSnapshot = searchScope;
        final SearchFilter filterSnapshot = currentSearchFilter;

        if (scopeSnapshot == SearchScope.FOLDER && querySnapshot.isEmpty() && filterSnapshot.isDefault()) {
            if (searchStatusBar != null) searchStatusBar.setVisibility(View.GONE);
            recyclerViewAdapter.setSearchDisplay("", false);
            List<FileItem> content = directoryObject.getDirectoryContent();
            if (!recyclerViewAdapter.getCurrentContent().equals(content)) {
                recyclerViewAdapter.newData(content);
            }
            return;
        }

        pendingSearchRunnable = () -> executeSearch(querySnapshot, scopeSnapshot, filterSnapshot);
        long delayMs = (scopeSnapshot == SearchScope.RECURSIVE) ? 400L : 180L;
        searchDebounceHandler.postDelayed(pendingSearchRunnable, delayMs);
    }

    private void executeSearch(String query, SearchScope scope, SearchFilter filter) {
        if (!isAdded() || context == null) return;
        Process prev = recursiveSearchProcess.getAndSet(null);
        if (prev != null) {
            prev.destroy();
        }

        if (scope == SearchScope.RECURSIVE) {
            if (searchStatusBar != null && searchStatusText != null) {
                searchStatusBar.setVisibility(View.VISIBLE);
                searchStatusText.setText(getString(R.string.search_recursive_running, 0));
            }
        } else if (searchStatusBar != null) {
            searchStatusBar.setVisibility(View.GONE);
        }

        final List<FileItem> folderSnapshot = new ArrayList<>(directoryObject.getDirectoryContent());
        final String currentPath = directoryObject.getCurrentPath();
        final int activeSort = sortOrder;

        searchExecutor.execute(() -> {
            List<FileItem> results;
            boolean showPath = scope != SearchScope.FOLDER;
            boolean truncated = false;

            if (scope == SearchScope.INDEXED) {
                List<RemoteItem> allRemotes = rclone.getRemotes();
                Map<String, RemoteItem> remoteMap = new HashMap<>();
                for (RemoteItem r : allRemotes) {
                    remoteMap.put(r.getName(), r);
                }
                results = CacheManager.getInstance(context).searchIndexed(query, remoteMap, filter, 300);
                if (results.size() >= 300) truncated = true;
            } else if (scope == SearchScope.RECURSIVE) {
                if (query.trim().isEmpty() && filter.isDefault()) {
                    results = folderSnapshot;
                } else {
                    int maxItems = 500;
                    List<FileItem> allItems = rclone.getDirectoryContentRecursive(
                            remote, currentPath, startAtRoot, 8, maxItems, recursiveSearchProcess);
                    if (allItems == null) {
                        allItems = Collections.emptyList();
                    }
                    if (allItems.size() >= maxItems) truncated = true;
                    results = SearchEngine.filterItems(allItems, query, filter, true);
                }
            } else {
                results = SearchEngine.filterItems(folderSnapshot, query, filter, false);
            }

            FileComparators.sortFileItems(results, activeSort);
            final List<FileItem> finalResults = results;
            final boolean wasTruncated = truncated;

            searchDebounceHandler.post(() -> {
                if (!isAdded() || !isSearchMode || recyclerViewAdapter == null) return;
                if (searchStatusBar != null) {
                    if (wasTruncated && searchStatusText != null) {
                        searchStatusBar.setVisibility(View.VISIBLE);
                        searchStatusText.setText(getString(R.string.search_recursive_truncated, finalResults.size()));
                    } else {
                        searchStatusBar.setVisibility(View.GONE);
                    }
                }
                recyclerViewAdapter.setSearchDisplay(query, showPath);
                recyclerViewAdapter.newData(finalResults);
            });
        });
    }

    private void cancelMoveClicked() {
        setTitle();
        recyclerViewAdapter.setMoveMode(false);
        isInMoveMode = false;
        hideMoveBar();
        fab.show();
        fab.setVisibility(View.VISIBLE);
        setFabBehaviour(true);
        showNavDrawerButtonInToolbar();
        setOptionsMenuVisibility(true);
        recyclerViewAdapter.refreshData();

        if (moveStartPath != null && !moveStartPath.equals(directoryObject.getCurrentPath())) {
            if (fetchDirectoryTask != null) {
                fetchDirectoryTask.cancel(true);
            }
            if (directoryObject.isPathInCache(moveStartPath)) {
                directoryObject.restoreFromCache(moveStartPath);
                recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            } else {
                directoryObject.setPath(moveStartPath);
                recyclerViewAdapter.clear();
                fetchDirectoryTask = new FetchDirectoryContent(true).execute();
            }
            buildStackFromPath(remoteName, moveStartPath);
            breadcrumbView.clearCrumbs();
            if (!moveStartPath.equals("//" + remoteName)) {
                breadcrumbView.buildBreadCrumbsFromPath(directoryObject.getCurrentPath());
            }
            breadcrumbView.addCrumb(remote.getDisplayName(), "//" + remoteName);
            moveStartPath = null;
        }
    }

    private void moveLocationSelected() {
        setTitle();
        hideMoveBar();
        fab.show();
        fab.setVisibility(View.VISIBLE);
        setFabBehaviour(true);
        setOptionsMenuVisibility(true);
        recyclerViewAdapter.setMoveMode(false);
        recyclerViewAdapter.refreshData();
        isInMoveMode = false;
        showNavDrawerButtonInToolbar();
        if (moveList.size() < 1) {
            Toast.makeText(context, getString(R.string.error_moving_file), Toast.LENGTH_SHORT).show();
            moveList.clear();
            moveStartPath = null;
            return;
        }
        String oldPath = moveList.get(0).getPath();
        int index = oldPath.lastIndexOf(moveList.get(0).getName());
        String path2;
        if (index > 0) {
            path2 = moveList.get(0).getPath().substring(0, index - 1);
        } else {
            path2 = "//" + remoteName;
        }
        for (FileItem moveItem : moveList) {
            EphemeralTaskManager.Companion.queueMove(this.context, remote, directoryObject.getCurrentPath(), moveItem, path2);
        }
        Toast.makeText(context, getString(R.string.moving_info), Toast.LENGTH_SHORT).show();
        moveList.clear();
        moveStartPath = null;
    }

    private void showCancelButtonInToolbar() {
        ActionBar actionbar = ((AppCompatActivity) context).getSupportActionBar();
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true);
            actionbar.setHomeAsUpIndicator(R.drawable.ic_cancel_white);
        }
    }

    private void showNavDrawerButtonInToolbar() {
        ActionBar actionbar = ((AppCompatActivity)context).getSupportActionBar();
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true);
            actionbar.setHomeAsUpIndicator(R.drawable.ic_menu);
        }
    }

    private void showSortMenu() {
        SortDialog sortDialog = new SortDialog();
        sortDialog
                .setTitle(R.string.sort)
                .setNegativeButton(R.string.cancel)
                .setPositiveButton(R.string.ok)
                .setSortOrder(sortOrder);
        sortDialog.show(getChildFragmentManager(), "sort dialog");
    }

    /*
     * Sort Dialog callback
     */
    @Override
    public void onPositiveButtonClick(int sortById, int sortOrderId) {
        if (!directoryObject.isDirectoryContentEmpty()) {
            sortSelected(sortById, sortOrderId);
        }
    }

    private void sortSelected(int sortById, int sortOrderId) {
        List<FileItem> directoryContent = directoryObject.getDirectoryContent();
        sortOrder = FileComparators.resolveSortOrder(sortById, sortOrderId);
        FileComparators.sortFileItems(directoryContent, sortOrder);
        directoryObject.setContent(directoryContent);

        if (isSearchMode) {
            List<FileItem> sortedSearch = new ArrayList<>(recyclerViewAdapter.getCurrentContent());
            FileComparators.sortFileItems(sortedSearch, sortOrder);
            recyclerViewAdapter.updateSortedData(sortedSearch);
        } else {
            recyclerViewAdapter.updateSortedData(directoryContent);
        }
        if (sortOrder > 0) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            sharedPreferences.edit().putInt(SHARED_PREFS_SORT_ORDER, sortOrder).apply();
        }
    }

    private void sortDirectory() {
        List<FileItem> directoryContent = directoryObject.getDirectoryContent();
        FileComparators.sortFileItems(directoryContent, sortOrder);
        directoryObject.setContent(directoryContent);
    }

    private void onClickOutsideOfView() {
        if (recyclerViewAdapter.isInSelectMode()) {
            recyclerViewAdapter.cancelSelection();
        } else if (recyclerViewAdapter.isInMoveMode()) {
            cancelMoveClicked();
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
        isRunning = true;
    }

    @Override
    public void onStop() {
        super.onStop();
        if (isThumbnailsServiceRunning) {
            Intent intent = new Intent(context, ThumbnailsLoadingService.class);
            context.stopService(intent);
            isThumbnailsServiceRunning = false;
        }

        LocalBroadcastManager.getInstance(context).unregisterReceiver(backgroundTaskBroadcastReceiver);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        breadcrumbView.clearCrumbs();
        breadcrumbView.setVisibility(View.GONE);
        searchBar.setVisibility(View.GONE);
        ((FragmentActivity) context).setTitle(originalToolbarTitle);
        showNavDrawerButtonInToolbar();
        prefChangeListener = null;
        isRunning = false;
        context = null;
    }

    public boolean onBackButtonPressed() {
        if (recyclerViewAdapter.isInSelectMode()) {
            recyclerViewAdapter.cancelSelection();
            showNavDrawerButtonInToolbar();
            return true;
        } else if (isSearchMode) {
            searchClicked();
            return true;
        } else if (fab.isOpen()) {
            fab.close(true);
            return true;
        } else if (pathStack.isEmpty()) {
            return false;
        }
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            fab.show();
        }
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        swipeRefreshLayout.setRefreshing(false);
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        breadcrumbView.removeLastCrumb();
        String path = pathStack.pop();
        recyclerViewAdapter.clear();
        if (!directoryObject.isContentValid(path)) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(path)) {
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
        } else {
            directoryObject.setPath(path);
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
        return true;
    }

    @Override
    public void onFileClicked(FileItem fileItem) {
        String type = fileItem.getMimeType();
        if (type.startsWith("video/") || type.startsWith("audio/")) {
            // stream video or audio
            new StreamTask().execute(fileItem);
        } else {
            // download and open
            new DownloadAndOpen().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, fileItem);
        }
    }

    @Override
    public void onDirectoryClicked(FileItem fileItem, int position) {
        directoryPosition.put(directoryObject.getCurrentPath(), position);
        breadcrumbView.addCrumb(fileItem.getName(), fileItem.getPath());
        swipeRefreshLayout.setRefreshing(true);
        pathStack.push(directoryObject.getCurrentPath());
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            fab.show();
        }

        if (isSearchMode) {
            searchClicked();
        }

        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }

        if (!directoryObject.isContentValid(fileItem.getPath())) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(fileItem.getPath());
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(fileItem.getPath())) {
            directoryObject.restoreFromCache(fileItem.getPath());
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            swipeRefreshLayout.setRefreshing(false);
        } else {
            directoryObject.setPath(fileItem.getPath());
            recyclerViewAdapter.clear();
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    @Override
    public void onFilesSelected() {
        handleFilesSelected();
    }

    private void handleFilesSelected() {
        int numOfSelected = recyclerViewAdapter.getNumberOfSelectedItems();

        if (numOfSelected > 0) { // something is selected
            ((FragmentActivity) context).setTitle(numOfSelected + " " + getString(R.string.selected));
            showBottomBar();
            fab.hide();
            fab.setVisibility(View.INVISIBLE);
            setFabBehaviour(false);
            setOptionsMenuVisibility(false);
            showCancelButtonInToolbar();
            if (numOfSelected > 1) {
                ((FragmentActivity) context).findViewById(R.id.file_rename).setAlpha(.5f);
                ((FragmentActivity) context).findViewById(R.id.file_rename).setClickable(false);
            } else {
                ((FragmentActivity) context).findViewById(R.id.file_rename).setAlpha(1f);
                ((FragmentActivity) context).findViewById(R.id.file_rename).setClickable(true);
            }
        }
    }

    @Override
    public void onFileDeselected() {
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            setTitle();
            hideBottomBar();
            fab.show();
            fab.setVisibility(View.VISIBLE);
            setFabBehaviour(true);
            setOptionsMenuVisibility(true);
            showNavDrawerButtonInToolbar();
        } else {
            handleFilesSelected();
        }
    }

    @Override
    public void onFileOptionsClicked(View view, FileItem fileItem) {
        showFileMenu(view, fileItem);
    }

    @Override
    public String[] getThumbnailServerParams() {
        return new String[]{thumbnailServerAuth + '/' + remote.getName(), String.valueOf(thumbnailServerPort)};
    }

    private void showFileMenu(View view, final FileItem fileItem) {
        PopupMenu popupMenu = new PopupMenu(context, view);
        popupMenu.getMenuInflater().inflate(R.menu.file_explorer_menu, popupMenu.getMenu());
        androidx.core.view.MenuCompat.setGroupDividerEnabled(popupMenu.getMenu(), true);
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.action_file_properties:
                    showFileProperties(fileItem);
                    break;
                case R.id.action_open_as:
                    showOpenAsDialog(fileItem);
                    break;
                case R.id.action_serve:
                    String[] serveOptions = getResources().getStringArray(R.array.serve_options);
                    AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    builder.setItems(serveOptions, (dialog, which) -> {
                        Intent intent = new Intent(getContext(), StreamingService.class);
                        switch (which) {
                            case 0: // HTTP
                                intent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
                                intent.putExtra(StreamingService.REMOTE_ARG, remote);
                                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_HTTP);
                                intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
                                break;
                            case 1: // Webdav
                                intent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
                                intent.putExtra(StreamingService.REMOTE_ARG, remote);
                                intent.putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_WEBDAV);
                                intent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, true);
                                break;
                            default:
                                return;
                        }
                        // GH-87: Release old server
                        context.stopService(new Intent(context, StreamingService.class));
                        tryStartService(context, intent);
                    });
                    builder.setTitle(R.string.pick_a_protocol);
                    builder.show();
                    break;
                case R.id.action_download:
                    downloadList = new ArrayList<>();
                    downloadList.add(fileItem);
                    downloadFiles();
                    break;
                case R.id.action_move:
                    moveFiles(Collections.singletonList(fileItem));
                    break;
                case R.id.action_rename:
                    renameItem = fileItem;
                    renameFiles();
                    break;
                case R.id.action_delete:
                    deleteFiles(Collections.singletonList(fileItem));
                    break;
                case R.id.action_link:
                    new LinkTask().execute(fileItem.getPath());
                    break;
                case R.id.action_sync:
                    showSyncDialog(fileItem.getPath());
                    break;
                default:
                    return false;
            }
            return true;
        });
        popupMenu.show();
        if (fileItem.isDir()) {
            popupMenu.getMenu().findItem(R.id.action_open_as).setVisible(false);
        } else {
            popupMenu.getMenu().findItem(R.id.action_sync).setVisible(false);
        }
        if (!remote.hasSyncSupport()) {
            // TODO: remove once destination sync is added.
            popupMenu.getMenu().findItem(R.id.action_sync).setVisible(false);
        }
        Boolean cachedLinkSupport = CacheManager.getInstance(context).getPublicLinkSupport(remote.getName());
        if (!remote.hasLinkSupport() || Boolean.FALSE.equals(cachedLinkSupport)) {
            popupMenu.getMenu().findItem(R.id.action_link).setVisible(false);
        }
        if (remote.isRemoteType(RemoteItem.LOCAL, RemoteItem.SAFW) || remote.isPathAlias()) {
            // TODO: replace with .setTitle(copy) once destination copy is added
            popupMenu.getMenu().findItem(R.id.action_download).setVisible(false);
        }
    }

    @Override
    public void onBreadCrumbClicked(String path) {
        if (fab.isOpen()) {
            fab.close(true);
        }
        if (isSearchMode) {
            searchClicked();
        }
        if (!isInMoveMode && !recyclerViewAdapter.isInSelectMode()) {
            fab.show();
        }
        if (directoryObject.getCurrentPath().equals(path)) {
            return;
        }
        swipeRefreshLayout.setRefreshing(false);
        if (fetchDirectoryTask != null) {
            fetchDirectoryTask.cancel(true);
        }
        directoryObject.setPath(path);
        //noinspection StatementWithEmptyBody
        while (!pathStack.empty() && !pathStack.pop().equals(path)) {
            // pop stack until we find path
        }
        breadcrumbView.removeCrumbsUpTo(path);
        recyclerViewAdapter.clear();

        if (!directoryObject.isContentValid(path)) {
            swipeRefreshLayout.setRefreshing(true);
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        } else if (directoryObject.isPathInCache(path)) {
            directoryObject.restoreFromCache(path);
            sortDirectory();
            recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
            if (directoryPosition.containsKey(directoryObject.getCurrentPath())) {
                int position = directoryPosition.get(directoryObject.getCurrentPath());
                recyclerViewLinearLayoutManager.scrollToPositionWithOffset(position, 10);
            }
        } else {
            fetchDirectoryTask = new FetchDirectoryContent().execute();
        }
    }

    private void showSyncDialog(String path) {
        syncRemotePath = path;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        String[] options = getResources().getStringArray(R.array.sync_direction_options);
        builder.setTitle(R.string.select_sync_direction);
        builder.setItems(options, (dialog, which) -> {
            int value = which+1;
            switch (value){
                case SyncDirectionObject.SYNC_REMOTE_TO_LOCAL: syncDirection = SyncDirectionObject.SYNC_REMOTE_TO_LOCAL; break;
                case SyncDirectionObject.COPY_LOCAL_TO_REMOTE: syncDirection = SyncDirectionObject.COPY_LOCAL_TO_REMOTE; break;
                case SyncDirectionObject.COPY_REMOTE_TO_LOCAL: syncDirection = SyncDirectionObject.COPY_REMOTE_TO_LOCAL; break;
                default: syncDirection = SyncDirectionObject.SYNC_LOCAL_TO_REMOTE; break;
            }

            Intent intent = new Intent(context, FilePicker.class);
            intent.putExtra(FilePicker.FILE_PICKER_PICK_DESTINATION_TYPE, true);
            startActivityForResult(intent, FILE_PICKER_SYNC_RESULT);
        });
        builder.show();
    }

    private void showBottomBar() {
        View bottomBar = ((FragmentActivity) context).findViewById(R.id.bottom_bar);
        if (bottomBar.getVisibility() == View.VISIBLE) {
            return;
        }
        bottomBar.setVisibility(View.VISIBLE);
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_in_animation);
        bottomBar.startAnimation(animation);
    }

    private void hideBottomBar() {
        View bottomBar = ((FragmentActivity) context).findViewById(R.id.bottom_bar);
        if (bottomBar.getVisibility() != View.VISIBLE) {
            return;
        }
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out_animation);
        bottomBar.setAnimation(animation);
        bottomBar.setVisibility(View.GONE);
    }

    private void hideMoveBar() {
        View moveBar = ((FragmentActivity) context).findViewById(R.id.move_bar);
        Animation animation = AnimationUtils.loadAnimation(getContext(), R.anim.fade_out_animation);
        moveBar.setAnimation(animation);
        moveBar.setVisibility(View.GONE);
    }

    private void deleteFiles(final List<FileItem> deleteList) {
        String title = getResources().getQuantityString(R.plurals.delete_x_items, deleteList.size(), deleteList.size());
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder
                .setTitle(title)
                .setNegativeButton(getResources().getString(R.string.cancel), null)
                .setPositiveButton(getResources().getString(R.string.delete), (dialog, which) -> {
                    recyclerViewAdapter.cancelSelection();
                    View view = getView();
                    if (view == null) {
                        enqueueDeletes(deleteList);
                        return;
                    }
                    // RC-37: defer the destructive enqueue until the Snackbar undo window elapses.
                    // Once a delete worker has started it cannot be reliably cancelled, so we only
                    // enqueue when the user lets the Snackbar time out (no Undo tap).
                    final List<FileItem> pendingDeletes = new ArrayList<>(deleteList);
                    Snackbar snackbar = Snackbar.make(view, getString(R.string.deleting_info), Snackbar.LENGTH_LONG);
                    snackbar.setAction(R.string.undo, v -> {
                        pendingDeletes.clear();
                        Toast.makeText(context, getString(R.string.cancel), Toast.LENGTH_SHORT).show();
                    });
                    snackbar.addCallback(new Snackbar.Callback() {
                        @Override
                        public void onDismissed(Snackbar transientBottomBar, int event) {
                            super.onDismissed(transientBottomBar, event);
                            if (event != DISMISS_EVENT_ACTION && !pendingDeletes.isEmpty()) {
                                enqueueDeletes(pendingDeletes);
                            }
                        }
                    });
                    snackbar.show();
                });
        if(deleteList.size() == 1) {
            builder.setMessage(getString(R.string.name_will_be_deleted, deleteList.get(0).getName()));
        }
        builder.create().show();
    }

    private void enqueueDeletes(List<FileItem> deleteList) {
        if (context == null) {
            return;
        }
        EphemeralTaskManager.Companion.queueDelete(this.context, remote, deleteList, directoryObject.getCurrentPath());
    }

    private void renameFiles() {
        if (getFragmentManager() != null) {
            new InputDialog()
                    .setTitle(R.string.rename_file)
                    .setMessage(R.string.type_new_file_name)
                    .setNegativeButton(R.string.cancel)
                    .setPositiveButton(R.string.okay_confirmation)
                    .setFilledText(renameItem.getName())
                    .setTag("rename file")
                    .show(getChildFragmentManager(), "input dialog");
        }
    }

    private void downloadFiles() {
        Intent intent = new Intent(context, FilePicker.class);
        intent.putExtra(FilePicker.FILE_PICKER_PICK_DESTINATION_TYPE, true);
        startActivityForResult(intent, FILE_PICKER_DOWNLOAD_RESULT);
    }

    private void moveFiles(List<FileItem> moveItems) {
        if (moveItems.size() < 1) {
            return;
        }
        moveStartPath = directoryObject.getCurrentPath();
        moveList = new ArrayList<>(moveItems);
        recyclerViewAdapter.cancelSelection();
        recyclerViewAdapter.setMoveMode(true);
        isInMoveMode = true;
        showCancelButtonInToolbar();
        ((FragmentActivity) context).setTitle(getString(R.string.select_destination));
        ((FragmentActivity) context).findViewById(R.id.move_bar).setVisibility(View.VISIBLE);
        setOptionsMenuVisibility(false);
        fab.hide();
        fab.setVisibility(View.INVISIBLE);
        setFabBehaviour(false);
    }

    private void onCreateNewDirectory() {
        if (getFragmentManager() != null) {
            new InputDialog()
                    .setTitle(R.string.create_new_folder)
                    .setMessage(R.string.type_new_folder_name)
                    .setNegativeButton(R.string.cancel)
                    .setPositiveButton(R.string.okay_confirmation)
                    .setTag("new dir")
                    .setHint(R.string.hint_new_folder)
                    .show(getChildFragmentManager(), "input dialog");
        }
    }

    /*
     * Input Dialog callback
     */
    @Override
    public void onPositive(String tag, String input) {
        switch (tag) {
            case "new dir":
                if (input.trim().length() == 0) {
                    return;
                }
                String newDir;
                if (directoryObject.getCurrentPath().equals("//" + remoteName)) {
                    newDir = input;
                } else {
                    newDir = directoryObject.getCurrentPath() + "/" + input;
                }
                new MakeDirectoryTask().execute(newDir);
                break;
            case "rename file":
                if (renameItem.getName().equals(input)) {
                    return;
                }
                recyclerViewAdapter.cancelSelection();
                String newFilePath;
                if (directoryObject.getCurrentPath().equals("//" + remoteName)) {
                    newFilePath = input;
                } else {
                    newFilePath = directoryObject.getCurrentPath() + "/" + input;
                }
                new RenameFileTask().execute(renameItem.getPath(), newFilePath);
                renameItem = null;
                break;
        }
    }

    private void onUploadFiles() {
        Intent intent = new Intent(context, FilePicker.class);
        startActivityForResult(intent, FILE_PICKER_UPLOAD_RESULT);
    }

    /*
     * Go To Dialog Callback
     */
    @Override
    public void onRootClicked(boolean isSetAsDefault) {
        startAtRoot = true;
        directoryObject.clear();
        String path = "//" + remoteName;
        directoryObject.setPath(path);
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (isSetAsDefault) {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), true);
            editor.putBoolean(getString(R.string.pref_key_start_at_root), true);
        } else {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), false);
        }
        editor.apply();
    }

    /*
     * Go To Dialog Callback
     */
    @Override
    public void onHomeClicked(boolean isSetAsDefault) {
        startAtRoot = false;
        directoryObject.clear();
        String path = "//" + remoteName;
        directoryObject.setPath(path);
        swipeRefreshLayout.setRefreshing(true);
        fetchDirectoryTask = new FetchDirectoryContent().execute();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (isSetAsDefault) {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), true);
            editor.putBoolean(getString(R.string.pref_key_start_at_root), false);
        } else {
            editor.putBoolean(getString(R.string.pref_key_go_to_default_set), false);
        }
        editor.apply();
    }

    /***********************************************************************************************
     * AsyncTask classes
     ***********************************************************************************************/
    @SuppressLint("StaticFieldLeak")
    private class FetchDirectoryContent extends AsyncTask<Void, Void, List<FileItem>> {

        private boolean silentFetch;

        FetchDirectoryContent() {
            this(false);
        }

        FetchDirectoryContent(boolean silentFetch) {
            this.silentFetch = silentFetch;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(true);
            }
        }

        @Override
        protected List<FileItem> doInBackground(Void... voids) {
            String curPath = directoryObject.getCurrentPath();
            CacheManager cm = CacheManager.getInstance(context);
            if (!silentFetch) {
                CacheManager.CachedListing cached = cm.getDirectoryListing(remote, curPath);
                if (cached != null && !cached.isStale()) {
                    return cached.getItems();
                }
            }
            List<FileItem> fileItemList = rclone.getDirectoryContent(remote, curPath, startAtRoot);
            if (fileItemList != null) {
                cm.saveDirectoryListing(remote.getName(), curPath, fileItemList);
            } else if (!silentFetch) {
                CacheManager.CachedListing fallback = cm.getDirectoryListing(remote, curPath);
                if (fallback != null) {
                    return fallback.getItems();
                }
            }
            return fileItemList;
        }

        @Override
        protected void onPostExecute(List<FileItem> fileItems) {
            super.onPostExecute(fileItems);
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
            if (null == getContext()) {
                FLog.w(TAG, "FetchDirectoryContent/onPostExecute: discarding refresh");
                return;
            }
            if (fileItems == null) {
                if (silentFetch) {
                    return;
                }
                // UI-021: keep the currently-displayed content rather than blanking the list on a
                // transient rclone error; the user can pull-to-refresh to retry.
                Toast.makeText(context, getString(R.string.error_getting_dir_content), Toast.LENGTH_SHORT).show();
                return;
            }

            directoryObject.setContent(fileItems);
            sortDirectory();

            if (isSearchMode && searchString != null) {
                searchDirContent(searchString);
            } else {
                if (recyclerViewAdapter != null) {
                    if (silentFetch) {
                        recyclerViewAdapter.updateData(directoryObject.getDirectoryContent());
                    } else {
                        recyclerViewAdapter.newData(directoryObject.getDirectoryContent());
                    }
                }
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class RenameFileTask extends AsyncTask<String, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = directoryObject.getCurrentPath();
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            String oldFileName = strings[0];
            String newFileName = strings[1];

            return rclone.moveTo(remote, oldFileName, newFileName);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                CacheManager.getInstance(context).invalidateDirectory(remote.getName(), pathWhenTaskStarted);
                Toast.makeText(context, getString(R.string.file_renamed_success), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, getString(R.string.error_moving_file), Toast.LENGTH_SHORT).show();

            }
            if (!pathWhenTaskStarted.equals(directoryObject.getCurrentPath())) {
                directoryObject.removePathFromCache(pathWhenTaskStarted);
                return;
            }
            if (fetchDirectoryTask != null) {
                fetchDirectoryTask.cancel(true);
            }
            swipeRefreshLayout.setRefreshing(false);

            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class MakeDirectoryTask extends AsyncTask<String, Void, Boolean> {

        private String pathWhenTaskStarted;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pathWhenTaskStarted = directoryObject.getCurrentPath();
            swipeRefreshLayout.setRefreshing(true);
        }

        @Override
        protected Boolean doInBackground(String... strings) {
            String newDir = strings[0];
            return rclone.makeDirectory(remote, newDir);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (!isRunning) {
                return;
            }
            if (result) {
                CacheManager.getInstance(context).invalidateDirectory(remote.getName(), pathWhenTaskStarted);
                Toast.makeText(context, getString(R.string.make_directory_success), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, getString(R.string.error_mkdir), Toast.LENGTH_SHORT).show();
            }
            if (!pathWhenTaskStarted.equals(directoryObject.getCurrentPath())) {
                directoryObject.removePathFromCache(pathWhenTaskStarted);
                return;
            }
            swipeRefreshLayout.setRefreshing(false);
            if (fetchDirectoryTask != null) {
                fetchDirectoryTask.cancel(true);
            }
            fetchDirectoryTask = new FetchDirectoryContent(true).execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class DownloadAndOpen extends AsyncTask<FileItem, Void, Boolean> {

        public static final int OPEN_AS_TEXT = 1;
        public static final int OPEN_AS_IMAGE = 2;
        private int openAs;
        private LoadingDialog loadingDialog;
        private String fileLocation;
        private Process process;
        private volatile boolean isCancelled = false;
        private String mimeType;

        DownloadAndOpen() {
            this(-1);
        }

        DownloadAndOpen(int openAs) {
            this.openAs = openAs;
        }

        private void cancelProcess() {
            isCancelled = true;
            if (null != process) {
                process.destroy();
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            loadingDialog = new LoadingDialog()
                    .setCanCancel(false)
                    .setTitle(getString(R.string.loading_file))
                    .setNegativeButton(getResources().getString(R.string.cancel))
                    .setOnNegativeListener(() -> {
                        cancelProcess();
                        cancel(true);
                    });
            if (getFragmentManager() != null) {
                loadingDialog.show(getChildFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            FileItem fileItem = fileItems[0];
            mimeType = fileItem.getMimeType();
            CacheManager cacheManager = CacheManager.getInstance(context);
            File cachedFile = cacheManager.getCachedFile(
                    remote.getName(),
                    fileItem.getPath(),
                    fileItem.getSize(),
                    fileItem.getModTime(),
                    fileItem.getName()
            );
            if (cachedFile != null) {
                fileLocation = cachedFile.getAbsolutePath();
                return true;
            }

            File previewCacheDir = new File(context.getCacheDir(), "preview");
            if (!previewCacheDir.exists() && !previewCacheDir.mkdirs()) {
                return false;
            }
            String safeFileName = new File(fileItem.getName().replace('\\', '/')).getName().trim();
            if (safeFileName.isEmpty() || ".".equals(safeFileName) || "..".equals(safeFileName)) {
                return false;
            }
            String saveLocation = previewCacheDir.getAbsolutePath();
            File targetFile = new File(previewCacheDir, safeFileName);
            try {
                if (!targetFile.getCanonicalPath().startsWith(previewCacheDir.getCanonicalPath() + File.separator)) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
            if (targetFile.exists()) {
                targetFile.setWritable(true);
                targetFile.delete();
            }
            fileLocation = targetFile.getAbsolutePath();

            process = rclone.downloadFile(remote, fileItem, saveLocation);

            if (process != null) {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    if (!isCancelled) {
                        FLog.e(TAG, "DownloadAndOpen/doInBackground: error waiting for process", e);
                    }
                    return false;
                }
            }

            if (process != null && process.exitValue() == 0) {
                File savedFile = new File(fileLocation);
                File stored = cacheManager.putCachedFile(
                        remote.getName(),
                        fileItem.getPath(),
                        fileItem.getSize(),
                        fileItem.getModTime(),
                        fileItem.getName(),
                        savedFile
                );
                if (stored != null) {
                    savedFile.delete();
                    fileLocation = stored.getAbsolutePath();
                } else {
                    savedFile.setReadOnly();
                }
            }

            if (process != null && process.exitValue() != 0) {
                rclone.logErrorOutput(process);
            }

            return process != null && process.exitValue() == 0;
        }

        @Override
        protected void onPostExecute(Boolean status) {
            super.onPostExecute(status);
            Dialogs.dismissSilently(loadingDialog);
            if (!status) {
                if (context != null && !isCancelled) {
                    Toast.makeText(context, R.string.error_downloading_file, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            if (null == context) {
                FLog.e(TAG, "Cannot provide file %s using dead context", fileLocation);
                return;
            }
            File file = new File(fileLocation);
            ApkInstallHelper.openFile(context, file, mimeType, openAs, (settingsIntent, apkFile) -> {
                pendingApkInstallFile = apkFile;
                installPermissionLauncher.launch(settingsIntent);
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class StreamTask extends AsyncTask<FileItem, Void, Boolean> {

        private static final String TAG = "StreamTask";
        public static final int OPEN_AS_VIDEO = 0;
        public static final int OPEN_AS_AUDIO = 1;
        private int openAs;
        private LoadingDialog loadingDialog;
        private Intent serveIntent;
        private FileItem fileItem;
        private Intent intent;

        StreamTask() {
            this(-1);
        }

        StreamTask(int openAs) {
            this.openAs = openAs;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setCanCancel(false)
                    .setTitle(R.string.loading);

            if (getFragmentManager() != null) {
                loadingDialog.show(getChildFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected Boolean doInBackground(FileItem... fileItems) {
            if(context == null) {
                FLog.w(TAG, "doInBackground: could not start stream, context is invalid");
                return false;
            }
            fileItem = fileItems[0];
            int port = allocatePort(8080, true);
            byte[] tokenBytes = new byte[16];
            new java.security.SecureRandom().nextBytes(tokenBytes);
            String streamToken = android.util.Base64.encodeToString(
                    tokenBytes, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING | android.util.Base64.URL_SAFE);
            serveIntent = new Intent(context, StreamingService.class);
            serveIntent.putExtra(StreamingService.SERVE_PATH_ARG, fileItem.getPath());
            serveIntent.putExtra(StreamingService.REMOTE_ARG, remote);
            serveIntent.putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, false);
            serveIntent.putExtra(StreamingService.SERVE_PORT, port);
            serveIntent.putExtra(StreamingService.SERVE_BASE_URL, streamToken);
            // GH-87: Release old stream
            context.stopService(new Intent(context, StreamingService.class));
            tryStartService(context, serveIntent);

            Uri uri = Uri.parse("http://127.0.0.1:" + port)
                    .buildUpon()
                    .appendPath(streamToken)
                    .appendPath(fileItem.getName())
                    .build();

            intent = new Intent(Intent.ACTION_VIEW);

            // open as takes precedence
            if (openAs == OPEN_AS_VIDEO) {
                intent.setDataAndType(uri, "video/*");
            } else if (openAs == OPEN_AS_AUDIO) {
                intent.setDataAndType(uri, "audio/*");
            } else {
                String type = fileItem.getMimeType();
                if (type.startsWith("audio/")) {
                    intent.setDataAndType(uri, "audio/*");
                } else if (type.startsWith("video/")) {
                    intent.setDataAndType(uri, "video/*");
                } else {
                    intent.setData(uri);
                }
            }

            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder().url(uri.toString()).head().build();
            int code = -1;

            long waitTime = 30 * 1000;
            boolean available = false;
            while (waitTime > 0) {
                long waitStart = System.nanoTime();
                try {
                    FLog.v(TAG, "doInBackground: HEAD %s", uri.toString());
                    Response response = client.newCall(request).execute();
                    code = response.code();

                    if(BuildConfig.DEBUG) {
                        Map<String, List<String>> headerFields =  response.headers().toMultimap();
                        String headers = StreamSupport.stream(headerFields.keySet())
                                .map(k -> k + '=' + headerFields.get(k))
                                .collect(Collectors.joining(",", "{", "}"));
                        FLog.v(TAG, "doInBackground: Response %s", headers);
                    }
                } catch (IOException e) {
                    FLog.v(TAG, "doInBackground: Server not (yet) online");
                }

                if (code == 200) {
                    available = true;
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    // ignored
                }
                long waitEnd = System.nanoTime();
                long actualWait = (waitEnd - waitStart) / 1000000;
                waitTime -= actualWait;
            }
            return available;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (!isRunning) {
                // TODO: restructure user flow
                //       1) Start the host service and display a notification
                //          "Loading file for streaming"
                //       2) If the stream becomes available, set notification
                //          intent to start stream
                //          a) The user has not navigated away -> invoke Intent
                //          b) The user has navigated away -> Send an intent to
                //             the service to update its notification
                FLog.w(TAG, "Streaming failed: user navigated away before stream could load");
                return;
            }
            Dialogs.dismissSilently(loadingDialog);
            if(success) {
                Activity activity = getActivity();
                if (null == activity) {
                    return;
                }
                tryStartActivityForResult(activity, intent, STREAMING_INTENT_RESULT);
            } else {
                Toast.makeText(context, getString(R.string.streaming_task_failed), Toast.LENGTH_LONG).show();
                context.stopService(serveIntent);
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class EmptyTrashTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... voids) {
            return rclone.emptyTrashCan(remoteName);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            super.onPostExecute(result);
            if (isRunning) {
                if (result) {
                    Toast.makeText(context, getString(R.string.trash_emptied), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, getString(R.string.error_emptying_trash), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class LinkTask extends AsyncTask<String, Void, Rclone.LinkResult> {

        private LoadingDialog loadingDialog;
        private String linkPath;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingDialog = new LoadingDialog()
                    .setTitle(R.string.generating_public_link);
            if (getFragmentManager() != null) {
                loadingDialog.show(getChildFragmentManager(), "loading dialog");
            }
        }

        @Override
        protected Rclone.LinkResult doInBackground(String... strings) {
            linkPath = strings[0];
            return rclone.linkWithOptions(remote, linkPath, null, false);
        }

        @Override
        protected void onPostExecute(Rclone.LinkResult result) {
            Dialogs.dismissSilently(loadingDialog);
            if (null == getContext()) {
                return;
            }

            if (result == null || !result.isSuccess() || result.url == null) {
                String msg = (result != null && result.errorMessage != null)
                        ? result.errorMessage
                        : getString(R.string.error_generating_link);
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                return;
            }

            LinkDialog linkDialog = new LinkDialog()
                    .setLinkUrl(result.url)
                    .setTarget(remote, linkPath);
            if (getFragmentManager() != null && !getChildFragmentManager().isStateSaved()) {
                linkDialog.show(getChildFragmentManager(), "link dialog");
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            Dialogs.dismissSilently(loadingDialog);
        }
    }
}
