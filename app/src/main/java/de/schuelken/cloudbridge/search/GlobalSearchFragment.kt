package de.schuelken.cloudbridge.search

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.pkay.rcloneexplorer.Dialogs.SortDialog
import ca.pkay.rcloneexplorer.FileComparators
import ca.pkay.rcloneexplorer.Fragments.RemotesFragment
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import de.schuelken.cloudbridge.cache.CacheManager
import java.util.concurrent.Executors

class GlobalSearchFragment : Fragment(), FileExplorerRecyclerViewAdapter.OnClickListener {

    private var currentQuery: String = ""
    private var currentFilter = SearchFilter(SearchFilter.TypeFilter.ALL, 0L, 0L)
    private lateinit var adapter: FileExplorerRecyclerViewAdapter
    private lateinit var emptyView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingSearch: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_global_search, container, false)
        activity?.setTitle(R.string.global_search_title)

        emptyView = view.findViewById(R.id.global_search_empty)
        val hiddenEmpty = View(requireContext())
        adapter = FileExplorerRecyclerViewAdapter(requireContext(), hiddenEmpty, emptyView, this)
        adapter.setSearchMode(true)
        adapter.disableFileOptions()

        val recyclerView = view.findViewById<RecyclerView>(R.id.global_search_recycler)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        val searchInput = view.findViewById<TextInputEditText>(R.id.global_search_input)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        view.findViewById<Chip>(R.id.global_chip_filter_type).setOnClickListener {
            showTypeFilterMenu(it as Chip)
        }
        view.findViewById<Chip>(R.id.global_chip_filter_size).setOnClickListener {
            showSizeFilterMenu(it as Chip)
        }
        view.findViewById<Chip>(R.id.global_chip_filter_date).setOnClickListener {
            showDateFilterMenu(it as Chip)
        }

        scheduleSearch("")
        return view
    }

    private fun scheduleSearch(query: String) {
        currentQuery = query
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { runSearch(query, currentFilter) }
        pendingSearch = runnable
        mainHandler.postDelayed(runnable, 180L)
    }

    private fun runSearch(query: String, filter: SearchFilter) {
        val ctx = context ?: return
        val cm = CacheManager.getInstance(ctx)
        if (cm.getTier3EnabledRemotes().isEmpty()) {
            emptyView.setText(R.string.global_search_no_indexed_remotes)
            adapter.setSearchDisplay("", true)
            adapter.newData(emptyList())
            return
        }

        emptyView.setText(R.string.global_search_no_results)
        val sortOrder = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getInt("ca.pkay.rcexplorer.sort_order", SortDialog.ALPHA_ASCENDING)

        executor.execute {
            val rclone = Rclone(ctx.applicationContext)
            val remotesByName = rclone.remotes.associateBy { it.name }
            val results = cm.searchIndexed(query, remotesByName, filter, 300)
            FileComparators.sortFileItems(results, sortOrder)
            mainHandler.post {
                if (!isAdded) return@post
                adapter.setSearchDisplay(query, true)
                adapter.newData(results)
            }
        }
    }

    private fun showTypeFilterMenu(anchor: Chip) {
        val menu = PopupMenu(requireContext(), anchor)
        val values = SearchFilter.TypeFilter.values()
        val labels = intArrayOf(
            R.string.search_filter_all, R.string.search_filter_images, R.string.search_filter_video,
            R.string.search_filter_audio, R.string.search_filter_docs, R.string.search_filter_archives,
            R.string.search_filter_apk, R.string.search_filter_folders
        )
        for (i in values.indices) menu.menu.add(0, i, i, labels[i])
        menu.setOnMenuItemClickListener { item ->
            val chosen = values[item.itemId]
            currentFilter = currentFilter.copy(type = chosen)
            anchor.setText(labels[item.itemId])
            anchor.isChecked = chosen != SearchFilter.TypeFilter.ALL
            scheduleSearch(currentQuery)
            true
        }
        menu.show()
    }

    private fun showSizeFilterMenu(anchor: Chip) {
        val menu = PopupMenu(requireContext(), anchor)
        val sizes = longArrayOf(0L, 1024L * 1024L, 10L * 1024L * 1024L, 100L * 1024L * 1024L)
        val labels = intArrayOf(
            R.string.search_filter_size_any, R.string.search_filter_size_gt_1mb,
            R.string.search_filter_size_gt_10mb, R.string.search_filter_size_gt_100mb
        )
        for (i in sizes.indices) menu.menu.add(0, i, i, labels[i])
        menu.setOnMenuItemClickListener { item ->
            val minSize = sizes[item.itemId]
            currentFilter = currentFilter.copy(minSizeBytes = minSize)
            anchor.setText(labels[item.itemId])
            anchor.isChecked = minSize > 0L
            scheduleSearch(currentQuery)
            true
        }
        menu.show()
    }

    private fun showDateFilterMenu(anchor: Chip) {
        val menu = PopupMenu(requireContext(), anchor)
        val day = 24L * 60L * 60L * 1000L
        val ages = longArrayOf(0L, 7L * day, 30L * day, 365L * day)
        val labels = intArrayOf(
            R.string.search_filter_date_any, R.string.search_filter_date_7d,
            R.string.search_filter_date_30d, R.string.search_filter_date_1y
        )
        for (i in ages.indices) menu.menu.add(0, i, i, labels[i])
        menu.setOnMenuItemClickListener { item ->
            val maxAge = ages[item.itemId]
            currentFilter = currentFilter.copy(maxAgeMs = maxAge)
            anchor.setText(labels[item.itemId])
            anchor.isChecked = maxAge > 0L
            scheduleSearch(currentQuery)
            true
        }
        menu.show()
    }

    override fun onFileClicked(fileItem: FileItem) {
        val remote: RemoteItem = fileItem.remote ?: return
        (activity as? RemotesFragment.OnRemoteClickListener)?.onRemoteClick(remote)
    }

    override fun onDirectoryClicked(fileItem: FileItem, position: Int) {
        val remote: RemoteItem = fileItem.remote ?: return
        (activity as? RemotesFragment.OnRemoteClickListener)?.onRemoteClick(remote)
    }

    override fun onFilesSelected() {}
    override fun onFileDeselected() {}
    override fun onFileOptionsClicked(view: View, fileItem: FileItem) {}
    override fun getThumbnailServerParams(): Array<String> = arrayOf("", "0")

    override fun onDestroy() {
        super.onDestroy()
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        executor.shutdownNow()
    }
}
