package org.artcraft.camcraft.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import org.artcraft.camcraft.protocol.CcftProtocolConstants

private const val DEFAULT_MDNS_SERVICE_NAME = "CamCraft Android"
private val EXCLUDED_INTERFACE_PREFIXES = listOf("lo", "rmnet", "ccmni", "tun", "ppp", "dummy")
private val WIFI_INTERFACE_PREFIXES = listOf("wlan", "wifi", "swlan", "ap")
private val ETHERNET_INTERFACE_PREFIXES = listOf("eth", "en")
private val WHITESPACE_REGEX = Regex("\\s+")

/**
 * Candidate IPv4 address bound to a local network interface, usable in JVM tests
 * without Android framework dependencies.
 */
data class InterfaceAddressCandidate(
    val interfaceName: String,
    val ipv4: String,
    val isSiteLocal: Boolean,
    val isLinkLocal: Boolean,
)

/**
 * Sanitizes a raw device name into a valid DNS-SD service instance label.
 *
 * Replaces control characters and dots with spaces, collapses whitespace, trims,
 * falls back to `"CamCraft Android"` if blank, and truncates safely on a UTF-8
 * code-point boundary to at most [MdnsAdvertiser.MAX_SERVICE_NAME_BYTES] bytes.
 */
fun sanitizeServiceName(raw: String): String {
    val cleaned = buildString(raw.length) {
        for (ch in raw) {
            append(if (ch == '.' || ch.isISOControl()) ' ' else ch)
        }
    }.replace(WHITESPACE_REGEX, " ").trim()

    val source = cleaned.ifBlank { DEFAULT_MDNS_SERVICE_NAME }
    if (source.toByteArray(Charsets.UTF_8).size <= MdnsAdvertiser.MAX_SERVICE_NAME_BYTES) {
        return source
    }

    val truncated = StringBuilder()
    var byteCount = 0
    var index = 0
    while (index < source.length) {
        val codePoint = Character.codePointAt(source, index)
        val charCount = Character.charCount(codePoint)
        val segment = source.substring(index, index + charCount)
        val segmentBytes = segment.toByteArray(Charsets.UTF_8).size
        if (byteCount + segmentBytes > MdnsAdvertiser.MAX_SERVICE_NAME_BYTES) {
            break
        }
        truncated.append(segment)
        byteCount += segmentBytes
        index += charCount
    }

    return truncated.toString().trim().ifBlank { DEFAULT_MDNS_SERVICE_NAME }
}

/**
 * Selects the best local LAN IPv4 address from enumerated interface candidates.
 *
 * Excludes empty, loopback (`127.*`), and cellular/VPN/dummy/loopback interfaces
 * (`lo`, `rmnet`, `ccmni`, `tun`, `ppp`, `dummy`), preferring RFC 1918 site-local
 * addresses on Wi-Fi, then Ethernet, then other interfaces, and finally non-link-local
 * Wi-Fi/Ethernet IPv4 addresses (falling back to Wi-Fi link-local only if nothing else exists).
 */
fun selectBestLocalIpv4(candidates: List<InterfaceAddressCandidate>): String? {
    val usable = candidates.mapNotNull { candidate ->
        val iface = candidate.interfaceName.trim().lowercase()
        val ip = candidate.ipv4.trim()
        val excluded = iface.isEmpty() || EXCLUDED_INTERFACE_PREFIXES.any { iface.startsWith(it) }
        val invalidIp = ip.isEmpty() || ip == "0.0.0.0" || ip.startsWith("127.") || ip.contains(':')
        if (excluded || invalidIp) {
            null
        } else {
            candidate.copy(interfaceName = iface, ipv4 = ip)
        }
    }

    fun isWifi(candidate: InterfaceAddressCandidate): Boolean =
        WIFI_INTERFACE_PREFIXES.any { candidate.interfaceName.startsWith(it) }

    fun isEthernet(candidate: InterfaceAddressCandidate): Boolean =
        ETHERNET_INTERFACE_PREFIXES.any { candidate.interfaceName.startsWith(it) }

    fun isLinkLocal(candidate: InterfaceAddressCandidate): Boolean =
        candidate.isLinkLocal || candidate.ipv4.startsWith("169.254.")

    return usable.firstOrNull { isWifi(it) && it.isSiteLocal && !isLinkLocal(it) }?.ipv4
        ?: usable.firstOrNull { isEthernet(it) && it.isSiteLocal && !isLinkLocal(it) }?.ipv4
        ?: usable.firstOrNull { it.isSiteLocal && !isLinkLocal(it) }?.ipv4
        ?: usable.firstOrNull { (isWifi(it) || isEthernet(it)) && !isLinkLocal(it) }?.ipv4
        ?: usable.firstOrNull { isWifi(it) }?.ipv4
}

/**
 * Formats an optional IPv4 address and TCP port into `"ip:port"`, returning `null`
 * if [ip] is null or blank.
 */
fun formatWifiEndpoint(ip: String?, port: Int = CcftProtocolConstants.DEFAULT_PORT): String? {
    val trimmed = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "$trimmed:$port"
}

/**
 * Resolves the device's local LAN IPv4 address, preferring active Wi-Fi transport
 * (even without internet access) and falling back to [NetworkInterface] enumeration.
 */
object LocalIpResolver {
    private const val TAG = "LocalIpResolver"

    fun resolveLocalIpv4Address(context: Context? = null): String? {
        if (context != null) {
            resolveFromActiveWifi(context)?.let { return it }
        }
        return resolveFromNetworkInterfaces()
    }

    private fun resolveFromActiveWifi(context: Context): String? {
        return try {
            val appContext = context.applicationContext ?: context
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null
            }
            val linkProperties = cm.getLinkProperties(activeNetwork) ?: return null
            val ifaceName = linkProperties.interfaceName ?: "wlan0"
            val wifiCandidates = linkProperties.linkAddresses.mapNotNull { linkAddress ->
                val addr = linkAddress.address as? Inet4Address ?: return@mapNotNull null
                val host = addr.hostAddress ?: return@mapNotNull null
                if (addr.isLoopbackAddress) return@mapNotNull null
                InterfaceAddressCandidate(
                    interfaceName = ifaceName,
                    ipv4 = host,
                    isSiteLocal = addr.isSiteLocalAddress,
                    isLinkLocal = addr.isLinkLocalAddress,
                )
            }
            wifiCandidates.firstOrNull { it.isSiteLocal && !it.isLinkLocal }?.ipv4
                ?: selectBestLocalIpv4(wifiCandidates)
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving IP from active Wi-Fi network: ${e.message}")
            null
        }
    }

    private fun resolveFromNetworkInterfaces(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<InterfaceAddressCandidate>()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val isActive = try {
                    nif.isUp && !nif.isLoopback && !nif.isVirtual
                } catch (_: Exception) {
                    false
                }
                if (!isActive) continue

                val ifaceName = nif.name ?: continue
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(
                            InterfaceAddressCandidate(
                                interfaceName = ifaceName,
                                ipv4 = host,
                                isSiteLocal = addr.isSiteLocalAddress,
                                isLinkLocal = addr.isLinkLocalAddress,
                            ),
                        )
                    }
                }
            }
            selectBestLocalIpv4(candidates)
        } catch (e: Exception) {
            Log.w(TAG, "Failed enumerating network interfaces: ${e.message}")
            null
        }
    }
}

/**
 * Observes Wi-Fi connectivity and link property changes to keep the displayed local IP
 * and mDNS TXT attributes up to date.
 */
class WifiNetworkMonitor(
    context: Context,
    private val onIpChanged: (String?) -> Unit,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val registered = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyResolvedIp()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            notifyResolvedIp()
        }

        override fun onLost(network: Network) {
            notifyResolvedIp()
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            if (connectivityManager != null) {
                registered.set(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Wi-Fi network callback: ${e.message}")
        }
    }

    private fun notifyResolvedIp() {
        val ip = LocalIpResolver.resolveLocalIpv4Address(appContext)
        onIpChanged(ip)
    }

    /**
     * Unregisters the Wi-Fi callback safely and idempotently.
     */
    fun stop() {
        if (registered.compareAndSet(true, false)) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister Wi-Fi network callback: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WifiNetworkMonitor"
    }
}

/**
 * Advertises the CamCraft TCP server over mDNS/DNS-SD (`_camcraft._tcp.`) using
 * Android's [NsdManager] with a synchronized state machine that safely defers
 * mid-registration cancellations.
 */
class MdnsAdvertiser(
    context: Context,
    private val onStateChanged: (registered: Boolean, serviceName: String?) -> Unit = { _, _ -> },
) {
    private enum class State {
        IDLE,
        REGISTERING,
        REGISTERED,
        UNREGISTERING,
    }

    private data class PendingRegistration(
        val deviceName: String,
        val port: Int,
        val localIpAddress: String?,
    )

    private val appContext: Context = context.applicationContext ?: context
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lock = Any()

    private var state: State = State.IDLE
    private var currentListener: NsdManager.RegistrationListener? = null
    private var pendingUnregister: Boolean = false
    private var pendingRegister: PendingRegistration? = null

    /**
     * Registers or updates the `_camcraft._tcp.` service advertisement.
     */
    fun register(deviceName: String, port: Int, localIpAddress: String? = null) {
        val manager = nsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager unavailable; cannot advertise mDNS service")
            onStateChanged(false, null)
            return
        }

        val request = PendingRegistration(deviceName, port, localIpAddress)
        var listenerToUnregister: NsdManager.RegistrationListener? = null
        var immediateRegistration: Pair<NsdServiceInfo, NsdManager.RegistrationListener>? = null

        synchronized(lock) {
            when (state) {
                State.IDLE -> {
                    immediateRegistration = prepareRegistrationLocked(request)
                }
                State.REGISTERING -> {
                    // Wait for onServiceRegistered before unregistering, then re-register with latest metadata.
                    pendingUnregister = true
                    pendingRegister = request
                }
                State.REGISTERED -> {
                    pendingUnregister = false
                    pendingRegister = request
                    state = State.UNREGISTERING
                    listenerToUnregister = currentListener
                }
                State.UNREGISTERING -> {
                    pendingUnregister = false
                    pendingRegister = request
                }
            }
        }

        if (listenerToUnregister != null) {
            unregisterListenerSafely(listenerToUnregister)
        } else if (immediateRegistration != null) {
            dispatchRegisterService(
                manager = manager,
                serviceInfo = immediateRegistration.first,
                listener = immediateRegistration.second,
                port = request.port,
            )
        }
    }

    /**
     * Safely unregisters any active or in-flight mDNS advertisement.
     */
    fun unregister() {
        var listenerToUnregister: NsdManager.RegistrationListener? = null

        synchronized(lock) {
            pendingRegister = null
            when (state) {
                State.IDLE -> Unit
                State.REGISTERING -> {
                    pendingUnregister = true
                }
                State.REGISTERED -> {
                    pendingUnregister = false
                    state = State.UNREGISTERING
                    listenerToUnregister = currentListener
                }
                State.UNREGISTERING -> {
                    pendingUnregister = false
                }
            }
        }

        if (listenerToUnregister != null) {
            unregisterListenerSafely(listenerToUnregister)
        }
    }

    private fun prepareRegistrationLocked(
        request: PendingRegistration,
    ): Pair<NsdServiceInfo, NsdManager.RegistrationListener> {
        val sanitizedName = sanitizeServiceName(request.deviceName)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = sanitizedName
            serviceType = SERVICE_TYPE
            this.port = request.port
            setAttribute("ver", CcftProtocolConstants.VERSION.toInt().toString())
            setAttribute("device", sanitizedName)
            setAttribute("port", request.port.toString())
            if (!request.localIpAddress.isNullOrBlank()) {
                setAttribute("ip", request.localIpAddress.trim())
            }
        }
        val listener = createListener()
        state = State.REGISTERING
        currentListener = listener
        pendingUnregister = false
        pendingRegister = null
        return serviceInfo to listener
    }

    private fun dispatchRegisterService(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.RegistrationListener,
        port: Int,
    ) {
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.i(
                TAG,
                "Registering mDNS service '${serviceInfo.serviceName}' ($SERVICE_TYPE) on port $port",
            )
        } catch (e: Exception) {
            Log.w(TAG, "NsdManager.registerService failed: ${e.message}", e)
            finishListenerLifecycle(listener)
        }
    }

    private fun unregisterListenerSafely(listener: NsdManager.RegistrationListener) {
        val manager = nsdManager
        if (manager == null) {
            finishListenerLifecycle(listener)
            return
        }
        try {
            manager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "NsdManager.unregisterService failed: ${e.message}")
            finishListenerLifecycle(listener)
        }
    }

    private fun finishListenerLifecycle(listener: NsdManager.RegistrationListener) {
        var nextRegistration: Pair<NsdServiceInfo, NsdManager.RegistrationListener>? = null
        var nextPort = 0
        var shouldNotify = false

        synchronized(lock) {
            if (currentListener !== listener) return
            val nextRequest = pendingRegister
            pendingRegister = null
            pendingUnregister = false
            shouldNotify = true

            val manager = nsdManager
            if (nextRequest != null && manager != null) {
                nextPort = nextRequest.port
                nextRegistration = prepareRegistrationLocked(nextRequest)
            } else {
                state = State.IDLE
                currentListener = null
            }
        }

        if (shouldNotify) {
            onStateChanged(false, null)
        }
        val manager = nsdManager
        if (nextRegistration != null && manager != null) {
            dispatchRegisterService(
                manager = manager,
                serviceInfo = nextRegistration.first,
                listener = nextRegistration.second,
                port = nextPort,
            )
        }
    }

    private fun createListener(): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                var deferUnregister = false
                var notifyRegistered = false

                synchronized(lock) {
                    if (currentListener !== this) return
                    if (pendingUnregister) {
                        // Transition to UNREGISTERING while keeping currentListener and pendingRegister
                        // intact so any pending re-registration runs only after OS NSD unregisters.
                        deferUnregister = true
                        pendingUnregister = false
                        state = State.UNREGISTERING
                    } else {
                        state = State.REGISTERED
                        notifyRegistered = true
                    }
                }

                if (deferUnregister) {
                    unregisterListenerSafely(this)
                } else if (notifyRegistered) {
                    Log.i(TAG, "mDNS registered as '${serviceInfo.serviceName}'")
                    onStateChanged(true, serviceInfo.serviceName)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed (errorCode=$errorCode)")
                finishListenerLifecycle(this)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service unregistered ('${serviceInfo.serviceName}')")
                finishListenerLifecycle(this)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed (errorCode=$errorCode)")
                finishListenerLifecycle(this)
            }
        }

    companion object {
        private const val TAG = "MdnsAdvertiser"
        const val SERVICE_TYPE = "_camcraft._tcp."
        const val MAX_SERVICE_NAME_BYTES = 63
    }
}
