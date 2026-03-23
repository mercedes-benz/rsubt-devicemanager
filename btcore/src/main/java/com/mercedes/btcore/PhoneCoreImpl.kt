package com.mercedes.btcore

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.mercedes.btcore.adapter.MobileDeviceControlCallbackAdapter
import com.mercedes.btcore.adapter.OnMobileDeviceChangedFunc
import com.mercedes.btcore.adapter.OnMobileDeviceStatusChangedFunc
import com.mercedes.btcore.adapter.Singletons
import com.mercedes.btcore.broadcast.PhoneActivationBroadcastUtils.sendActivationEndEvent
import com.mercedes.btcore.broadcast.PhoneActivationBroadcastUtils.sendActivationStartEvent
import com.mercedes.btcore.broadcast.PhoneActivationBroadcastUtils.sendDeactivationEndEvent
import com.mercedes.btcore.broadcast.PhoneActivationBroadcastUtils.sendDeactivationStartEvent
import com.mercedes.btcore.data.MobileDeviceUsageTypeWrapper
import com.mercedes.btcore.data.PhoneDeviceImpl
import com.mercedes.btcore.data.PhoneState
import com.mercedes.btcore.interfaces.BtDevice
import com.mercedes.btcore.interfaces.PhoneCore
import com.mercedes.btcore.interfaces.PhoneDevice
import com.mercedes.btcore.utils.UserProfileClientInstance
import com.mercedes.btcore.utils.currentDisplayId
import com.mercedes.btcore.utils.getUserIdSafe
import com.mercedes.btcore.utils.getUserProfile
import com.mercedes.btcore.utils.isValidDevice
import com.mercedes.btcore.utils.logd
import com.mercedes.btcore.utils.print
import com.mercedes.userprofile.sdk.IUserProfileServiceCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mercedes.core.manager.communication.mobiledevice.MobileDeviceControlManager
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDevice
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.ACTIVATION_FAILED
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.ACTIVATION_SUCCESSFUL
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.AUTH_FAILED
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.AUTH_NUMERIC_COMPARISON
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.AUTH_SUCCESSFUL
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.CONNECTION_FAILED
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.CONNECTION_IN_PROGRESS
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.CONNECTION_SUCCESSFUL
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.DEACTIVATION_FAILED
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.DEACTIVATION_SUCCESSFUL
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.IDLE
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.REMOVAL_FAILED
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.REMOVAL_SUCCESSFUL
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceStatus.UNKNOWN
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceType
import vendor.mercedes.phone_hal.communication.mobiledevice.MobileDeviceUsageType

@Suppress("MaximumLineLength")
class PhoneCoreImpl(
    private val mobileDeviceControlManager: MobileDeviceControlManager,
    private val callbackAdapter: MobileDeviceControlCallbackAdapter,
    private val context: Context,
) : PhoneCore {

    /**
     * Set device access to current user profile if privateToProfile is true,
     * otherwise set device access to all user profiles.
     * If the device is set to private to profile, it will only be accessible
     * when the user profile is active.
     */
    override fun setDeviceAccess(phoneDevice: PhoneDevice, privateToProfile: Boolean) {
        "setDeviceAccess deviceId ${phoneDevice.deviceId}".logd()
        val userIdSafe = if (privateToProfile) getUserIdSafe() else -1
        mobileDeviceControlManager.setDeviceAccess(phoneDevice.deviceId, userIdSafe)
        phoneDevice.setPrivateToProfileFlag(userIdSafe)
    }

    /**
     * Get device access for the given deviceId.
     * Returns -1 if the device is accessible to all user profiles,
     * otherwise returns the user profile id that the device is private to.
     */
    override fun getDeviceAccess(deviceId: Int): Int {
        TODO("Not yet implemented")
    }

    /**
     * This function is used to get the list of authorized phone devices.
     * It returns a Flow that emits the list of authorized phone devices.
     */
    override fun authorizedPhoneListFlow(): Flow<List<PhoneDevice>> {
        return callbackFlow {
            delay(DELAY_GET_AUTHORIZED_DEVICE)

            fun updateList(mobileDeviceList: List<MobileDevice>) {
                val currentUserDevices = mobileDeviceList
                    .filter { it.enabledProfiles.contains(getUserIdSafe()) }
                    .map { PhoneDeviceImpl.from(it) }
                "displayId=${currentDisplayId()} updateList + $currentUserDevices".logd()
                trySend(currentUserDevices)
            }

            val onMobileDeviceListChangedFunc = { mobileDeviceList: List<MobileDevice> ->
                "authorized mobileDeviceListChanged + ${mobileDeviceList.print()}".logd()
                updateList(mobileDeviceList)
            }

            if (isActive) {
                "authorizedPhoneListFlow - addOnMobileDeviceListChangedFunc".logd()
                callbackAdapter.addOnMobileDeviceListChangedFunc(
                    onMobileDeviceListChangedFunc
                )
            }
            // should delay 100 ms to wait for subscribe to finish, or we cannot get mobileDeviceList
            delay(DELAY_SUBSCRIBE_CALLBACK)

            val phoneDeviceList: List<PhoneDevice> = try {
                withContext(Dispatchers.IO) {
                    mobileDeviceControlManager.mobileDeviceList
                        .filter { it.enabledProfiles.contains(getUserIdSafe()) }
                        .map { PhoneDeviceImpl.from(it) }
                }
            } catch (e: Exception) {
                emptyList()
            }

            "authorizedPhoneListFlow + getUserIdSafe: ${getUserIdSafe()}".logd()
            "authorizedPhoneListFlow get + $phoneDeviceList".logd()
            trySend(phoneDeviceList)

            awaitClose {
                "authorizedPhoneListFlow unsubscribe callback".logd()
                callbackAdapter.removeOnMobileDeviceListChangedFunc(
                    onMobileDeviceListChangedFunc
                )
            }
        }
    }

    /**
     * This function is used to get the list of known phone devices.
     * It returns a Flow that emits the list of known phone devices.
     */
    override fun knownPhoneListFlow(): Flow<List<PhoneDevice>> {
        return callbackFlow {
            val phoneDeviceList: List<PhoneDevice>
            val uid = getUserIdSafe()

            fun updateList(mobileDeviceList: List<MobileDevice>) {
                val currentUserDevices = mobileDeviceList
                    .filter { it.isValidDevice(uid) }
                    .map { PhoneDeviceImpl.from(it) }
                trySend(currentUserDevices)
            }

            val onMobileDeviceListChangedFunc = { mobileDeviceList: List<MobileDevice> ->
                "known mobileDeviceListChanged + ${mobileDeviceList.print()}".logd()
                updateList(mobileDeviceList)
            }

            if (isActive) {
                callbackAdapter.addOnMobileDeviceListChangedFunc(
                    onMobileDeviceListChangedFunc
                )
            }

            phoneDeviceList = try {
                mobileDeviceControlManager.mobileDeviceList
                    .filter { it.isValidDevice(uid) }
                    .map { PhoneDeviceImpl.from(it) }
            } catch (_: Exception) {
                emptyList()
            }
            "knownPhoneListFlow get + $phoneDeviceList".logd()

            trySend(phoneDeviceList)

            awaitClose {
                "knownPhoneListFlow removeOnMobileDeviceListChangedFunc".logd()
                callbackAdapter.removeOnMobileDeviceListChangedFunc(
                    onMobileDeviceListChangedFunc
                )
            }
        }
    }

    /**
     * This function is used to get the list of phone devices.
     */
    override fun phoneDeviceList(): List<PhoneDevice> {
        return mobileDeviceControlManager.mobileDeviceList.toList()
            .filter { it.enabledProfiles.contains(getUserIdSafe()) }
            .map { PhoneDeviceImpl.from(it) }
    }

    /**
     * This function is used to request device activation for a phone device.
     * It returns true if the activation is successful, false otherwise.
     */
    override suspend fun requestDeviceActivation(
        device: PhoneDevice,
        usageType: MobileDeviceUsageTypeWrapper
    ): Boolean {
        return suspendCancellableCoroutine { con ->
            val userIdSafe = getUserIdSafe()
            var callback: OnMobileDeviceStatusChangedFunc? = null
            val unregister = { cb: OnMobileDeviceStatusChangedFunc? ->
                "requestDeviceActivation unregister".logd()
                cb?.let {
                    callbackAdapter.removeOnMobileDeviceStatusChangedFunc(it)
                }
            }
            var lastStatus = UNKNOWN
            callback = callback@{ mobileDevice: MobileDevice?, status: Int, usageType: Int ->
                "Activation mobileDeviceStatusChanged name=${mobileDevice?.deviceName} + $status + $usageType".logd()
                if (!con.isActive || mobileDevice?.mobileDeviceID != device.deviceId) return@callback

                when (status) {
                    AUTH_NUMERIC_COMPARISON -> {
                        val passcode = getPasscode(device.deviceId)
                        "Activation mobileDeviceStatusChanged passcode=$passcode".logd()
                        confirmDevicePairing(device.deviceId, passcode)
                    }

                    ACTIVATION_SUCCESSFUL, CONNECTION_SUCCESSFUL -> {
                        "Activation mobileDeviceStatusChanged success status=$status".logd()
                        lastStatus = status
                    }

                    IDLE -> {
                        "Activation mobileDeviceStatusChanged lastStatus=$lastStatus".logd()
                        device.setLoading(false, convertToUsageWrapper(usageType))
                        sendActivationEndEvent(context, userIdSafe)
                        val activationSuccessful = (
                                lastStatus == ACTIVATION_SUCCESSFUL ||
                                        lastStatus == CONNECTION_SUCCESSFUL
                                )
                        con.resumeWith(Result.success(activationSuccessful))
                        unregister(callback)
                    }

                    AUTH_FAILED, ACTIVATION_FAILED, CONNECTION_FAILED -> {
                        "Activation mobileDeviceStatusChanged failed status=$status".logd()
                        device.setLoading(false, convertToUsageWrapper(usageType))
                        sendActivationEndEvent(context, userIdSafe)
                        con.resumeWith(Result.success(false))
                        unregister(callback)
                    }

                    else -> {
                        "Activation mobileDeviceStatusChanged other ignored status=$status".logd()
                    }
                }
            }

            if (con.isActive) {
                "requestDeviceActivation start".logd()
                callbackAdapter.addOnMobileDeviceStatusChangedFunc(
                    callback
                )
                device.setLoading(true, usageType)
                sendActivationStartEvent(context, userIdSafe)
                "activation for ${device.name} usageType=$usageType".logd()
                mobileDeviceControlManager.requestDeviceActivation(
                    device.deviceId,
                    convertToList(usageType),
                    userIdSafe
                )
            }

            con.invokeOnCancellation {
                "requestDeviceActivation invokeOnCancellation".logd()
                sendActivationEndEvent(context, userIdSafe)
                unregister(callback)
            }
        }
    }

    /**
     * This function is used to request device deactivation for a phone device.
     * It returns true if the deactivation is successful, false otherwise.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override suspend fun requestDeviceDeactivation(
        device: PhoneDevice,
        usageType: MobileDeviceUsageTypeWrapper,
        uid: Int
    ): Boolean {
        return suspendCancellableCoroutine { con ->
            var callback: OnMobileDeviceStatusChangedFunc? = null
            var mobileDeviceChangeCallback: OnMobileDeviceChangedFunc? = null

            val unregister =
                { statusCb: OnMobileDeviceStatusChangedFunc?, deviceChangeCb: OnMobileDeviceChangedFunc? ->
                    "requestDeviceDeactivation unregister".logd()
                    statusCb?.let {
                        callbackAdapter.removeOnMobileDeviceStatusChangedFunc(it)
                    }
                    deviceChangeCb?.let {
                        if (usageType == MobileDeviceUsageTypeWrapper.NONE) {
                            callbackAdapter.removeOnMobileDeviceChangedFunc(it)
                        }
                    }
                }

            var lastStatus = UNKNOWN
            callback = callback@{ mobileDevice: MobileDevice?, status: Int, usageType: Int ->
                "Deactivation mobileDeviceStatusChanged name=${mobileDevice?.deviceName} + $status + $usageType".logd()

                if (!con.isActive || mobileDevice?.mobileDeviceID != device.deviceId) return@callback

                when (status) {
                    DEACTIVATION_SUCCESSFUL -> lastStatus = status

                    IDLE -> {
                        "Deactivation mobileDeviceStatusChanged lastStatus=$lastStatus".logd()
                        device.setLoading(false, convertToUsageWrapper(usageType))
                        sendDeactivationEndEvent(context, uid)
                        val deactivationSuccessful = (lastStatus == DEACTIVATION_SUCCESSFUL)
                        con.resumeWith(Result.success(deactivationSuccessful))
                        unregister(callback, mobileDeviceChangeCallback)
                    }

                    DEACTIVATION_FAILED -> {
                        device.setLoading(false, convertToUsageWrapper(usageType))
                        sendDeactivationEndEvent(context, uid)
                        con.resumeWith(Result.success(false))
                        unregister(callback, mobileDeviceChangeCallback)
                    }
                }
            }

            mobileDeviceChangeCallback = { mobileDevice: MobileDevice ->
                "deactivation mobileDeviceChanged + ${mobileDevice.print()}".logd()
                if (con.isActive &&
                    mobileDevice.mobileDeviceID == device.deviceId &&
                    mobileDevice.enabledProfiles.contains(uid).not()
                ) {
                    "deactivation success for usageType NONE".logd()
                    device.setLoading(false, usageType)
                    sendDeactivationEndEvent(context, uid)
                    con.resumeWith(Result.success(true))
                    unregister(callback, mobileDeviceChangeCallback)
                }
            }

            if (con.isActive) {
                callbackAdapter.addOnMobileDeviceStatusChangedFunc(callback)
                if (usageType == MobileDeviceUsageTypeWrapper.NONE) {
                    callbackAdapter.addOnMobileDeviceChangedFunc(mobileDeviceChangeCallback)
                }

                device.setLoading(true, usageType)
                sendDeactivationStartEvent(context, uid)

                "deactivation current userprofile id is $uid for ${device.name} usageType=$usageType".logd()
                mobileDeviceControlManager.requestDeviceDeactivation(
                    device.deviceId,
                    convertToList(usageType),
                    uid
                )
            }

            con.invokeOnCancellation {
                "requestDeviceDeactivation invokeOnCancellation".logd()
                sendDeactivationEndEvent(context, uid)
                unregister(callback, mobileDeviceChangeCallback)
            }
        }
    }

    /**
     * This function is used to request device removal for a phone device.
     * It returns true if the removal is successful, false otherwise.
     */
    override suspend fun requestDeviceRemoval(device: PhoneDevice): Boolean {
        return suspendCancellableCoroutine { con ->
            var callback: OnMobileDeviceStatusChangedFunc? = null
            var lastStatus = UNKNOWN
            val unregister =
                { statusCb: OnMobileDeviceStatusChangedFunc? ->
                    "requestDeviceRemoval unregister".logd()
                    statusCb?.let {
                        callbackAdapter.removeOnMobileDeviceStatusChangedFunc(it)
                    }
                }

            callback = { mobileDevice: MobileDevice?, status: Int, _: Int ->
                "requestDeviceRemoval status = $status".logd()
                if (con.isActive && mobileDevice?.mobileDeviceID == device.deviceId) {
                    if (status == REMOVAL_SUCCESSFUL) {
                        con.resumeWith(Result.success(true))
                        unregister(callback)
                    } else if (status == REMOVAL_FAILED || (lastStatus == REMOVAL_FAILED && status == IDLE)) {
                        con.resumeWith(Result.success(false))
                        unregister(callback)
                    }
                    lastStatus = status
                }
            }

            if (con.isActive) {
                callbackAdapter.addOnMobileDeviceStatusChangedFunc(callback)
                mobileDeviceControlManager.requestDeviceRemoval(device.deviceId)
            }

            con.invokeOnCancellation {
                "requestDeviceRemoval invokeOnCancellation".logd()
                unregister(callback)
            }
        }
    }

    /**
     * This function is used to authorize a phone device.
     * It returns a Flow that emits the phone state during the authorization process.
     */
    @Suppress("MaxLineLength", "CyclomaticComplexMethod")
    override fun authorizePhone(phoneDevice: BtDevice): Flow<PhoneState> {
        return callbackFlow {
            "authorizePhone start $phoneDevice".logd()
            val callback = { mobileDevice: MobileDevice?, status: Int, _: Int ->
                "authorizePhone mobileDeviceStatusChanged status=$status current=${phoneDevice.deviceId} device=${mobileDevice?.mobileDeviceID}".logd()
                if (isActive && mobileDevice?.mobileDeviceID == phoneDevice.deviceId) {
                    "authorizePhone device matched status=$status".logd()
                    when (status) {
                        AUTH_NUMERIC_COMPARISON -> trySend(PhoneState.AUTH_IN_PROGRESS)
                        AUTH_SUCCESSFUL -> trySend(PhoneState.AUTH_SUCCESSFUL)
                        AUTH_FAILED -> trySend(PhoneState.AUTH_FAILED)
                        CONNECTION_IN_PROGRESS -> trySend(PhoneState.CONNECTION_IN_PROGRESS)
                        CONNECTION_SUCCESSFUL -> trySend(PhoneState.CONNECTION_SUCCESSFUL)
                        CONNECTION_FAILED -> trySend(PhoneState.CONNECTION_FAILED)
                    }
                }
            }

            if (isActive) {
                phoneDevice.deviceId = mobileDeviceControlManager.addMobileDevice(
                    phoneDevice.macAddress.lowercase(),
                    phoneDevice.name,
                    IntArray(2).apply {
                        this[0] = MobileDeviceType.PHONE
                        this[1] = MobileDeviceType.AUDIO
                    },
                    if (phoneDevice.isIAP2Support()) IAP2 else ""
                )
                "authorizePhone addMobileDevice ${phoneDevice.deviceId}".logd()

                callbackAdapter.addOnMobileDeviceStatusChangedFunc(callback)
                val isCurrentDeviceAuthorized = try {
                    mobileDeviceControlManager.mobileDeviceList.any { it.mobileDeviceID == phoneDevice.deviceId }
                } catch (_: Exception) {
                    false
                }
                if (isCurrentDeviceAuthorized) {
                    trySend(PhoneState.ALREADY_AUTH)
                } else {
                    "authorizePhone requestDeviceActivation $phoneDevice".logd()
                    "authorizePhone isConnectAllDeviceUsageType = $isConnectAllDeviceUsageType".logd()
                    val deviceUSageTypes = if (isConnectAllDeviceUsageType) {
                        IntArray(2).apply {
                            this[0] = MobileDeviceUsageType.TEL
                            this[1] = MobileDeviceUsageType.AUDIO
                        }
                    } else {
                        IntArray(1).apply {
                            this[0] = MobileDeviceUsageType.TEL
                        }
                    }
                    mobileDeviceControlManager.requestDeviceActivation(
                        phoneDevice.deviceId,
                        deviceUSageTypes,
                        getUserIdSafe()
                    )
                }
            }

            awaitClose {
                callbackAdapter.removeOnMobileDeviceStatusChangedFunc(callback)
            }
        }
    }

    /**
     * This function is used to get the passcode for a phone device.
     * It returns the passcode as a String.
     */
    override fun getPasscode(deviceId: Int): String {
        return mobileDeviceControlManager.getPasscode(deviceId)
    }

    /**
     * This function is used to confirm device pairing for a phone device.
     */
    override fun confirmDevicePairing(deviceID: Int, passcode: String) {
        mobileDeviceControlManager.confirmDevicePairing(deviceID, passcode)
    }

    /**
     * This function is used to get the current user profile.
     * It returns a Pair containing the user name and user avatar Bitmap.
     */
    override suspend fun getCurrentUserProfile(): Pair<String?, Bitmap?> =
        suspendCancellableCoroutine { con ->
            val callback = object : IUserProfileServiceCallback {
                override fun onServiceAvailableChanged(isAvailable: Boolean) {
                    super.onServiceAvailableChanged(isAvailable)
                    if (isAvailable) {
                        "userprofile service connected".logd()
                        if (!con.isCompleted) {
                            UserProfileClientInstance.isBond = true
                            con.resumeWith(Result.success(getUserProfile()))
                        }
                    } else {
                        UserProfileClientInstance.isBond = false
                        "userprofile service disconnected".logd()
                    }
                }
            }
            if (con.isActive) {
                "userprofile service bind".logd()
                UserProfileClientInstance.userProfileClient.bind(context, callback)
            }
        }

    /**
     * This function is used to unsubscribe from mobile device updates.
     */
    override fun unsubscribe() {
        mobileDeviceControlManager.unsubscribe(Singletons.mobileDeviceControlCallbackAdapter)
    }

    /**
     * This function is used to subscribe to mobile device updates.
     */
    override fun subscribe() {
        mobileDeviceControlManager.subscribe(Singletons.mobileDeviceControlCallbackAdapter)
    }

    private fun convertToList(usageType: MobileDeviceUsageTypeWrapper): IntArray {
        return when (usageType) {
            MobileDeviceUsageTypeWrapper.AUDIO -> {
                IntArray(1).apply {
                    this[0] = MobileDeviceUsageType.AUDIO
                }
            }

            MobileDeviceUsageTypeWrapper.TEL -> {
                IntArray(1).apply {
                    this[0] = MobileDeviceUsageType.TEL
                }
            }

            MobileDeviceUsageTypeWrapper.ALL -> {
                IntArray(2).apply {
                    this[0] = MobileDeviceUsageType.TEL
                    this[1] = MobileDeviceUsageType.AUDIO
                }
            }

            else -> {
                IntArray(0)
            }
        }
    }

    private fun convertToUsageWrapper(usageType: Int): MobileDeviceUsageTypeWrapper {
        return if (usageType == MobileDeviceUsageType.TEL) {
            MobileDeviceUsageTypeWrapper.TEL
        } else {
            MobileDeviceUsageTypeWrapper.AUDIO
        }
    }

    /**
     * This function is used to notify that a new phone has been assigned.
     * It sends a broadcast to the phone app with the device ID and display ID.
     */
    override fun notifyNewPhoneAssigned(deviceId: Int) {
        "notifyNewDeviceAssigned start deviceId=$deviceId".logd()
        try {
            val intent = Intent(ACTION_NEW_PHONE_ASSIGNED).apply {
                setPackage(PACKAGE_PHONE)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_DISPLAY_ID, currentDisplayId())
            }
            "sendEvent ${intent.extras}".logd()
            context.sendBroadcastAsUser(intent, android.os.Process.myUserHandle())
        } catch (e: Exception) {
            "sendEvent failed $e".logd()
        }
    }

    companion object {
        private const val DELAY_GET_AUTHORIZED_DEVICE = 100L
        private const val DELAY_SUBSCRIBE_CALLBACK = 100L

        private const val ACTION_NEW_PHONE_ASSIGNED = "com.mercedes.devicemanager.NEW_PHONE_ASSIGNED"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val PACKAGE_PHONE = "com.mercedes.phone"
        private const val IAP2 = "iAP2"
        var isConnectAllDeviceUsageType = false
    }
}
