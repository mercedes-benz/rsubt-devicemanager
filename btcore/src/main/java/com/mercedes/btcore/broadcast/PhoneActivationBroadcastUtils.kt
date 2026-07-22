// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Mercedes-Benz Group AG

package com.mercedes.btcore.broadcast

import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.mercedes.btcore.utils.logd

object PhoneActivationBroadcastUtils {
    fun sendActivationStartEvent(context: Context, userIdSafe: Int) {
        sendEvent(context, userIdSafe, PhoneActivationStatus.ACTIVATION_START)
    }

    fun sendActivationEndEvent(context: Context, userIdSafe: Int) {
        sendEvent(context, userIdSafe, PhoneActivationStatus.ACTIVATION_END)
    }

    fun sendDeactivationStartEvent(context: Context, userIdSafe: Int) {
        sendEvent(context, userIdSafe, PhoneActivationStatus.DEACTIVATION_START)
    }

    fun sendDeactivationEndEvent(context: Context, userIdSafe: Int) {
        sendEvent(context, userIdSafe, PhoneActivationStatus.DEACTIVATION_END)
    }

    private fun sendEvent(context: Context, userIdSafe: Int, status: PhoneActivationStatus) {
        try {
            val intent =
                Intent(PhoneActivationStatusReceiver.ACTION_PHONE_ACTIVATION_STATUS_CHANGE).apply {
                    setPackage(context.packageName)
                    putExtra(PhoneActivationStatusReceiver.KEY_USER_ID, userIdSafe)
                    putExtra(PhoneActivationStatusReceiver.KEY_STATUS, status.name)
                }
            val userManager = context.getSystemService(UserManager::class.java) as UserManager
            userManager.userProfiles.forEach {
                context.sendBroadcastAsUser(intent, it)
            }
        } catch (e: Exception) {
            "sendEvent failed $e".logd()
        }
    }
}
