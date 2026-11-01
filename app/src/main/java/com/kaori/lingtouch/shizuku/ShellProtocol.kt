package com.kaori.lingtouch.shizuku

import android.os.IBinder

/** Hand-written Binder contract shared by the app process and the Shizuku UserService. */
object ShellProtocol {
    const val DESCRIPTOR = "com.kaori.lingtouch.shizuku.ILingShellService"
    const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_UID = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 2
    const val SHIZUKU_DESTROY_TRANSACTION = 16777115
}
