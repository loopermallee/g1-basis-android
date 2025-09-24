package io.texne.g1.basis.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val DEFAULT_BOND_TIMEOUT_MS = 20_000L
private const val DEFAULT_BOND_TAG = "BondingHelper"

@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
fun ensureBond(
    context: Context,
    device: BluetoothDevice,
    timeoutMs: Long = DEFAULT_BOND_TIMEOUT_MS,
    logTag: String = DEFAULT_BOND_TAG
): Boolean {
    val initialState = device.bondState
    BleLogger.info(logTag, "Ensuring bond for ${device.address}: initialState=$initialState")
    if (initialState == BluetoothDevice.BOND_BONDED) {
        BleLogger.info(logTag, "Bond already established for ${device.address}")
        return true
    }

    val latch = CountDownLatch(1)
    val completed = AtomicBoolean(false)
    val bonded = AtomicBoolean(false)

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            if (target.address != device.address) {
                return
            }

            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val previous = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.ERROR
            )
            BleLogger.info(logTag, "Bond state change for ${device.address}: state=$state previous=$previous")
            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    bonded.set(true)
                    if (completed.compareAndSet(false, true)) {
                        latch.countDown()
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    if (completed.compareAndSet(false, true)) {
                        latch.countDown()
                    }
                }
            }
        }
    }

    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, filter)
    }

    return try {
        val shouldInitiateBond = initialState != BluetoothDevice.BOND_BONDING
        if (!shouldInitiateBond) {
            BleLogger.info(logTag, "Bonding already in progress for ${device.address}")
        }
        if (shouldInitiateBond && !startBond(device, logTag)) {
            BleLogger.warn(logTag, "Bond initiation skipped for ${device.address}")
            return false
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            BleLogger.info(logTag, "Bond completed quickly for ${device.address}")
            bonded.set(true)
            return true
        }

        val awaited = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!awaited) {
            BleLogger.warn(logTag, "Bond timeout for ${device.address}")
        }
        val success = bonded.get() || device.bondState == BluetoothDevice.BOND_BONDED
        BleLogger.info(logTag, "Bond result for ${device.address}: success=$success finalState=${device.bondState}")
        success
    } finally {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

@SuppressLint("MissingPermission")
private fun startBond(device: BluetoothDevice, logTag: String): Boolean {
    val insecureStarted = try {
        val method = device.javaClass.getMethod("createBondInsecure")
        val result = method.invoke(device)
        val started = (result as? Boolean) == true
        if (started) {
            BleLogger.info(logTag, "createBondInsecure() initiated for ${device.address}")
        } else {
            BleLogger.info(logTag, "createBondInsecure() returned false for ${device.address}")
        }
        started
    } catch (error: NoSuchMethodException) {
        BleLogger.info(logTag, "createBondInsecure unavailable for ${device.address}")
        false
    } catch (error: Throwable) {
        BleLogger.warn(logTag, "createBondInsecure error for ${device.address}", error)
        false
    }
    if (insecureStarted) {
        return true
    }

    return try {
        val started = device.createBond()
        if (!started) {
            BleLogger.warn(logTag, "createBond() returned false for ${device.address}")
        } else {
            BleLogger.info(logTag, "createBond() initiated for ${device.address}")
        }
        started
    } catch (error: Throwable) {
        BleLogger.warn(logTag, "createBond() error for ${device.address}", error)
        false
    }
}
