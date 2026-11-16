package ca.pkay.rcloneexplorer.RemoteConfig

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.RemoteConfigListItemAdapter
import ca.pkay.rcloneexplorer.RemoteConfig.ProviderListFragment.SelectionChangedListener
import ca.pkay.rcloneexplorer.rclone.Provider
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton


class ProviderListFragment : Fragment() {
    private var mPreselection: String? = null
    interface ProviderSelectedListener {
        fun onProviderSelected(provider: Provider)
    }

    fun interface SelectionChangedListener {
        fun onProviderChanged(provider: Provider)
    }

    private var mRclone: Rclone? = null

    private var mProviders: List<Provider> = listOf()
    private var mProviderFilter = ""
    private var mSelectedProvider: Provider? = null
    private var mProviderSelectedListener: ProviderSelectedListener? = null

    private var mSelectionChangeListener = SelectionChangedListener {
        mSelectedProvider = it
        mFab?.visibility = View.VISIBLE
    }

    private var mRootView: View? = null
    private var mFab: ExtendedFloatingActionButton? = null


    companion object {
        private const val ARG_PRESELECTION = "preselection"
        private const val STATE_SELECTION = "selection"
        private const val STATE_FILTER = "filter"

        @JvmStatic
        fun newProviderListConfig(): ProviderListFragment = newProviderListConfig(null)

        @JvmStatic
        fun newProviderListConfig(preselection: String?): ProviderListFragment {
            return ProviderListFragment().apply {
                arguments = Bundle().apply { putString(ARG_PRESELECTION, preselection) }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mRclone = Rclone(this.context)
        mProviders = mRclone?.providers ?: listOf()
        mPreselection = savedInstanceState?.getString(STATE_SELECTION)
            ?: arguments?.getString(ARG_PRESELECTION)
        mProviderFilter = savedInstanceState?.getString(STATE_FILTER).orEmpty()
        mSelectedProvider = mProviders.find { it.name.equals(mPreselection, ignoreCase = true) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTION, mSelectedProvider?.name)
        outState.putString(STATE_FILTER, mProviderFilter)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_config_list, container, false)
        mRootView = view
        mFab = view.findViewById(R.id.next)
        if (mSelectedProvider != null) mFab?.visibility = View.VISIBLE
        setClickListeners(view)
        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mProviderSelectedListener = if (context is ProviderSelectedListener) {
            context
        } else {
            throw RuntimeException("$context must implement ProviderSelectedListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        mProviderSelectedListener = null
    }

    private fun setClickListeners(view: View) {
        mFab?.setOnClickListener {
            if(mSelectedProvider != null) {
                mProviderSelectedListener?.onProviderSelected(mSelectedProvider!!)
            }
        }

        val recyclerView: RecyclerView = view.findViewById(R.id.config_content)
        recyclerView.adapter = updateList()
        recyclerView.layoutManager = LinearLayoutManager(context)
    }

    fun updateList(): RemoteConfigListItemAdapter {
        var filteredProvider = mProviders
        if(mProviderFilter.isNotBlank()) {
            filteredProvider = filteredProvider.filter {
                it.name.contains(mProviderFilter, true) ||
                        it.description.contains(mProviderFilter, true)
            }
        }

        return RemoteConfigListItemAdapter(
            ArrayList(filteredProvider.sortedWith(compareBy { it.name })),
            requireContext(),
            mSelectionChangeListener,
            mSelectedProvider?.name
        )

    }

    fun setSearchterm (term: String) {
        mProviderFilter = term

        if(mRootView != null) {
            val recyclerView: RecyclerView = (mRootView as View).findViewById(R.id.config_content)
            recyclerView.adapter = updateList()
            recyclerView.layoutManager = LinearLayoutManager(context)
        }
    }
}