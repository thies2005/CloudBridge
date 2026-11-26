package ca.pkay.rcloneexplorer.RemoteConfig

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.RemoteConfig.ProviderListFragment.Companion.newProviderListConfig
import ca.pkay.rcloneexplorer.RemoteConfig.ProviderListFragment.ProviderSelectedListener
import ca.pkay.rcloneexplorer.RuntimeConfiguration
import ca.pkay.rcloneexplorer.rclone.Provider
import ca.pkay.rcloneexplorer.util.ActivityHelper


class RemoteConfig : AppCompatActivity(), ProviderSelectedListener {

    private val OUTSTATE_TITLE = "ca.pkay.rcexplorer.remoteConfig.TITLE"
    private val OUTSTATE_SEARCH_OPEN = "search_open"
    private val OUTSTATE_SEARCH_QUERY = "search_query"
    private var mFragment: Fragment? = null
    private lateinit var mSearchBar: SearchView
    private var mSearchIcon: MenuItem? = null

    companion object {
        const val CONFIG_EDIT_CODE = 139
        const val CONFIG_EDIT_TARGET = "CONFIG_EDIT_TARGET"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(RuntimeConfiguration.attach(this, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityHelper.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_config)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }

        mSearchBar = findViewById(R.id.search)
        if (savedInstanceState != null) {
            mFragment = supportFragmentManager.findFragmentById(R.id.flFragment)
            if (mFragment != null) {
                savedInstanceState.getString(OUTSTATE_TITLE)?.let { supportActionBar?.title = it }
            } else {
                startProviderlist()
            }
        } else {
            val editTarget = intent.getStringExtra(CONFIG_EDIT_TARGET)?.takeIf { it.isNotEmpty() }
            val provider = editTarget?.let { target ->
                val rclone = Rclone(this)
                rclone.getConfig(target)?.get("type")?.let { rclone.getProvider(it) }
            }
            if (editTarget == null) {
                startProviderlist()
            } else if (provider == null) {
                Toast.makeText(this, R.string.remote_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return
            } else {
                mFragment = DynamicRemoteConfigFragment.newInstance(provider.name, editTarget)
                startConfig(provider)
            }
        }

        mSearchBar.setOnCloseListener {
            toggleSearch(false)
            return@setOnCloseListener true
        }

        mSearchBar.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                if(isProviderlist()) {
                    (getCurrentFragment() as ProviderListFragment).setSearchterm(newText)
                }
                if(isDynamicRemoteConfigFragment()) {
                    (getCurrentFragment() as DynamicRemoteConfigFragment).setSearchterm(newText)
                }
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                return true
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackAction()
        })

        if (savedInstanceState?.getBoolean(OUTSTATE_SEARCH_OPEN) == true) {
            mSearchBar.visibility = View.VISIBLE
            mSearchBar.isIconified = false
            mSearchBar.setQuery(savedInstanceState.getString(OUTSTATE_SEARCH_QUERY).orEmpty(), false)
            mSearchBar.clearFocus()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.remote_config_menu, menu)
        mSearchIcon = menu?.findItem(R.id.app_bar_search)
        mSearchIcon?.isVisible = mSearchBar.visibility != View.VISIBLE
        return true
    }

    private fun toggleSearch(show: Boolean) {
        if(show) {
            mSearchBar.visibility = View.VISIBLE
            mSearchBar.requestFocus()
            mSearchBar.isFocusable = true
            mSearchBar.isIconified = false
            mSearchBar.requestFocusFromTouch()
            mSearchIcon?.isVisible = false
        } else {
            mSearchBar.setQuery("", false)
            mSearchBar.clearFocus()
            mSearchBar.visibility = View.GONE
            mSearchIcon?.isVisible = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.app_bar_search -> {
                toggleSearch(true)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(OUTSTATE_SEARCH_OPEN, mSearchBar.visibility == View.VISIBLE)
        outState.putString(OUTSTATE_SEARCH_QUERY, mSearchBar.query?.toString())
        if (supportActionBar != null) {
            val title = supportActionBar!!.title
            if (title != null) {
                outState.putString(OUTSTATE_TITLE, title.toString())
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        handleBackAction()
        return true
    }

    private fun handleBackAction() {
        if(mSearchBar.visibility == View.VISIBLE) {
            toggleSearch(false)
            return
        }

        if (mFragment is ProviderListFragment) {
            finish()
        } else if (mFragment is DynamicRemoteConfigFragment) {
            if ((mFragment as DynamicRemoteConfigFragment).isEditConfig()) {
                finish()
            } else {
                startProviderlist((mFragment as DynamicRemoteConfigFragment).providerName())
            }
        } else {
            startProviderlist()
        }
    }

    override fun onProviderSelected(provider: Provider) {
        if (provider == null) {
            Toast.makeText(this, getString(R.string.nothing_selected), Toast.LENGTH_SHORT)
                .show()
            return
        }
        toggleSearch(false)
        mFragment = DynamicRemoteConfigFragment.newInstance(provider.name)
        startConfig(provider)
    }

    private fun startConfig(provider: Provider) {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.flFragment, getCurrentFragment(), "new config")
        transaction.commit()
        if (supportActionBar != null) {
            supportActionBar!!.title = provider.getNameCapitalized()
        }
    }

    private fun startProviderlist() {
        startProviderlist(null)
    }
    private fun startProviderlist(preselection: String?) {
        mFragment = newProviderListConfig(preselection)
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.flFragment, getCurrentFragment(), "config list")
        fragmentTransaction.commit()
        if (supportActionBar != null) {
            supportActionBar!!.setTitle(R.string.title_activity_remote_config)
        }
    }

    private fun isProviderlist(): Boolean {
        if (mFragment is ProviderListFragment) {
            return true
        }
        return false
    }
    private fun isDynamicRemoteConfigFragment(): Boolean {
        if (mFragment is DynamicRemoteConfigFragment) {
            return true
        }
        return false
    }


    /**
     * This defauls to the provider list
     */
    private fun getCurrentFragment(): Fragment {
        if(mFragment == null) {
            return newProviderListConfig()
        }
        return mFragment!!
    }
}