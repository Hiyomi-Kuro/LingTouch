package com.kaori.lingtouch.shizuku

import android.os.IBinder
import android.os.Parcel

/** Client proxy for the shell-capable UserService binder. */
class LingShellClient(val binder: IBinder) {

    fun isAlive(): Boolean = runCatching { binder.isBinderAlive && binder.pingBinder() }.getOrDefault(false)

    fun exec(command: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShellProtocol.DESCRIPTOR)
            data.writeString(command)
            binder.transact(ShellProtocol.TRANSACTION_EXEC, data, reply, 0)
            reply.readException()
            return reply.readString().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun uid(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShellProtocol.DESCRIPTOR)
            binder.transact(ShellProtocol.TRANSACTION_UID, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun destroy() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShellProtocol.DESCRIPTOR)
            binder.transact(ShellProtocol.TRANSACTION_DESTROY, data, reply, 0)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
