package com.smartwalker.service.auth

import android.app.Activity
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthService @Inject constructor() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    fun isSignedIn(): Boolean = auth.currentUser != null

    fun getCurrentUserId(): String = auth.currentUser?.uid ?: ""

    suspend fun getIdToken(): String {
        return try {
            auth.currentUser?.getIdToken(false)?.await()?.token ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "getIdToken 실패", e)
            ""
        }
    }

    suspend fun signOut() {
        auth.signOut()
    }

    fun startPhoneVerification(
        phoneNumber: String,
        activity: Activity,
        callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
    ) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
        Log.d(TAG, "전화번호 인증 시작: $phoneNumber")
    }

    suspend fun signInWithPhoneCredential(credential: PhoneAuthCredential): Result<String> {
        return try {
            val result = auth.signInWithCredential(credential).await()
            val uid = result.user?.uid ?: throw Exception("사용자 정보를 가져올 수 없습니다")
            Log.d(TAG, "Firebase 로그인 성공: uid=$uid")
            Result.success(uid)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(Exception("인증번호가 올바르지 않습니다"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "FirebaseAuthService"

        /** 한국 전화번호를 E.164 국제 형식(+82)으로 변환합니다. */
        fun formatKoreanPhoneNumber(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.startsWith("010") || digits.startsWith("011") ||
                digits.startsWith("016") || digits.startsWith("017") ||
                digits.startsWith("018") || digits.startsWith("019") ->
                    "+82${digits.substring(1)}"
                digits.startsWith("82") -> "+$digits"
                else -> "+82$digits"
            }
        }
    }
}
