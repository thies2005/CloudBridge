package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build


class WifiConnectivitiyUtil {

    enum class Connection {
        NOT_AVAILABLE, CONNECTED, METERED, DISCONNECTED
    }

    companion object {

        fun dataConnection(mContext: Context): Connection {
            val connMgr = mContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork: Network? = connMgr.activeNetwork
                if (activeNetwork != null) {
                    val capabilities = connMgr.getNetworkCapabilities(activeNetwork)
                    capabilities ?: return Connection.DISCONNECTED

                    if(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)){
                        return Connection.CONNECTED
                    }

                    return Connection.METERED
                }
            } else {
                val activeNetworkInfo: NetworkInfo? = connMgr.activeNetworkInfo
                if (activeNetworkInfo != null) {
                    if (activeNetworkInfo.getType() === ConnectivityManager.TYPE_WIFI) {
                        return Connection.CONNECTED
                    }
                    if (activeNetworkInfo.getType() === ConnectivityManager.TYPE_MOBILE) {
                        return Connection.METERED
                    }
                }
            }
            return Connection.NOT_AVAILABLE
        }
    }
}