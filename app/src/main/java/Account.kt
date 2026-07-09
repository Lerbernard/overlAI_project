package com.example.test103

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

/**
 * ✅ Account layer: Google Sign-In -> Firebase Auth, with a Firestore-backed
 * premium flag so a paying user stays premium across devices.
 *
 * SETUP REQUIRED (see SNIPPETS.txt): a Firebase project, google-services.json
 * in app/, the google-services Gradle plugin, and your Web Client ID in
 * strings.xml as default_web_client_id.
 */
object Account {

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    fun isSignedIn(): Boolean = auth.currentUser != null
    fun email(): String? = auth.currentUser?.email
    fun displayName(): String? = auth.currentUser?.displayName
    fun uid(): String? = auth.currentUser?.uid

    fun googleClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun launchSignIn(context: Context, launcher: ActivityResultLauncher<Intent>) {
        launcher.launch(googleClient(context).signInIntent)
    }

    /** Call from the sign-in ActivityResult. */
    fun handleSignInResult(context: Context, data: Intent?, done: (Boolean) -> Unit) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val cred = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(cred).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    syncPremiumFromCloud(context) { done(true) }
                } else {
                    Log.e("Account", "Firebase auth failed", task.exception)
                    done(false)
                }
            }
        } catch (e: ApiException) {
            Log.e("Account", "Google sign-in failed: ${e.statusCode}", e)
            done(false)
        }
    }

    fun signOut(context: Context, done: () -> Unit = {}) {
        auth.signOut()
        googleClient(context).signOut().addOnCompleteListener { done() }
    }

    /**
     * ✅ Permanently delete the account: remove the Firestore user doc, delete
     * the Firebase Auth user, sign out of Google, and clear local premium.
     * onResult(true) on success; onResult(false, msg) if Firebase needs a
     * recent login (the caller should ask the user to sign in again).
     */
    fun deleteAccount(context: Context, onResult: (Boolean, String?) -> Unit) {
        val user = auth.currentUser ?: run { onResult(false, "Not signed in"); return }
        val uid = user.uid
        db.collection("users").document(uid).delete()
            .addOnCompleteListener {
                user.delete().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Premium.setPremium(context, false)
                        googleClient(context).signOut()
                        onResult(true, null)
                    } else {
                        val recent = task.exception is
                            com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
                        onResult(false, if (recent)
                            "Please sign in again, then retry deleting your account."
                        else "Couldn't delete account. Try again.")
                    }
                }
            }
    }

    /** Pull the user's premium flag from Firestore into the local Premium cache. */
    fun syncPremiumFromCloud(context: Context, done: () -> Unit = {}) {
        val uid = uid() ?: run { done(); return }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                val premium = doc.getBoolean("premium") == true
                Premium.setPremium(context, premium)
                done()
            }
            .addOnFailureListener { done() }
    }

    /** Write premium state to Firestore (called when a purchase completes). */
    fun setCloudPremium(active: Boolean) {
        val uid = uid() ?: return
        db.collection("users").document(uid)
            .set(mapOf("premium" to active), com.google.firebase.firestore.SetOptions.merge())
    }
}
