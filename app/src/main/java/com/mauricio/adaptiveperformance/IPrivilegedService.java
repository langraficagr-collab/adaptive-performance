package com.mauricio.adaptiveperformance;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

public interface IPrivilegedService extends IInterface {
    String exec(String command) throws RemoteException;
    int remoteUid() throws RemoteException;
    void destroy() throws RemoteException;

    abstract class Stub extends Binder implements IPrivilegedService {
        private static final String DESCRIPTOR = "com.mauricio.adaptiveperformance.IPrivilegedService";
        static final int TRANSACTION_exec = IBinder.FIRST_CALL_TRANSACTION;
        static final int TRANSACTION_remoteUid = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 2;

        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static IPrivilegedService asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IPrivilegedService) return (IPrivilegedService) iin;
            return new Proxy(obj);
        }

        @Override public IBinder asBinder() { return this; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) {
                data.enforceInterface(DESCRIPTOR);
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_exec:
                    String command = data.readString();
                    String result = exec(command);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                case TRANSACTION_remoteUid:
                    int uid = remoteUid();
                    reply.writeNoException();
                    reply.writeInt(uid);
                    return true;
                case TRANSACTION_destroy:
                    destroy();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements IPrivilegedService {
            private final IBinder remote;
            Proxy(IBinder remote) { this.remote = remote; }
            @Override public IBinder asBinder() { return remote; }

            @Override public String exec(String command) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(command);
                    remote.transact(TRANSACTION_exec, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally { reply.recycle(); data.recycle(); }
            }

            @Override public int remoteUid() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_remoteUid, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally { reply.recycle(); data.recycle(); }
            }

            @Override public void destroy() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_destroy, data, reply, 0);
                    reply.readException();
                } finally { reply.recycle(); data.recycle(); }
            }
        }
    }
}
