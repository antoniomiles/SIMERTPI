package ec.gob.simertpi.simertpi_citizen_app

import android.os.Build
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.UUID

/** Separate non-exportable signing credential. Logout removes tokens, not this key. */
class DeviceCredentialChannel(private val activity: FragmentActivity, messenger: BinaryMessenger) {
 private val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
 private var pending: MethodChannel.Result?=null
 private var prompt: BiometricPrompt?=null
 init {
  // Migrate before Dart can save its first CP24.5.3 session. Existing CP24.5.2
  // installations with login history conservatively consume the offer; manual
  // setup stays available. This also covers a previously cancelled native prompt.
  val migratedInvitation=File(activity.noBackupFilesDir,"simertpi.installation.invited")
  if(!migratedInvitation.exists() && activity.noBackupFilesDir.listFiles()?.any {
    it.name.startsWith("simertpi.bio.") && (it.name.endsWith(".session") || it.name.endsWith(".declined") || it.name.endsWith(".vault"))
   }==true)write(migratedInvitation,"presented")
  MethodChannel(messenger,"simertpi/device_credential").setMethodCallHandler { call,result ->
   val args=call.arguments as? Map<*,*> ?: emptyMap<Any,Any>()
   val diagnose=call.method=="prepare" || call.method=="commit" ||
     (call.method=="sign" && (args["payload"] as? String)?.startsWith("SIMERTPI-DEVICE-V1\nREGISTER\n")==true)
   var diagnosticPoint=if(call.method=="prepare")"PREPARE_VALIDATION" else if(call.method=="commit")"BINDING_STORAGE" else "SIGN_INIT_FAILED"
   try {
    val namespace=args["namespace"] as? String ?: throw IllegalArgumentException()
    val prefix="simertpi.sign."+MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString(""){"%02x".format(it)}
    val installation=File(activity.noBackupFilesDir,"simertpi.installation.invited")
    when(call.method) {
     "invitationPresented" -> {
      result.success(installation.exists())
     }
     "markInvitationPresented" -> { write(installation,"presented");result.success(null) }
     "bindings" -> {
      val bindings=activity.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(prefix+".") && it.name.endsWith(".binding") }
        ?.mapNotNull { file ->
         val binding=JSONObject(file.readText());val alias=file.name.removeSuffix(".binding")
         val usable=try { signer(alias);true } catch(_:Exception) { false }
         binding.keys().asSequence().associateWith { binding.getString(it) }.toMutableMap<String,Any>().apply { put("usable",usable) }
        } ?: emptyList()
      result.success(bindings)
     }
     else -> {
      val owner=args["owner"] as? String ?: throw IllegalArgumentException()
      val alias=prefix+"."+UUID.fromString(owner)
      val file=File(activity.noBackupFilesDir,"$alias.binding")
      when(call.method) {
       "prepare" -> {
        bio("BIO_KEYSTORE_PREPARE_START",diagnose)
        require(pending==null && Build.VERSION.SDK_INT>=24)
        diagnosticPoint="BIOMETRIC_COMPATIBILITY"
        require(BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS)
        // Never replace an authorized key without explicit revocation.
        diagnosticPoint="BINDING_EXISTS"
        require(!file.exists())
        diagnosticPoint="KEY_DELETE"
        if(keys.containsAlias(alias))keys.deleteEntry(alias)
        diagnosticPoint="KEY_SPEC"
        val builder=KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
          .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
          .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
          .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        if(Build.VERSION.SDK_INT>=30)builder.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
        else { @Suppress("DEPRECATION") builder.setUserAuthenticationValidityDurationSeconds(-1) }
        diagnosticPoint="KEY_GENERATION"
        val pair=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {initialize(builder.build())}.generateKeyPair()
        diagnosticPoint="DEVICE_STORAGE"
        val deviceFile=File(activity.noBackupFilesDir,"simertpi.installation.device")
        if(!deviceFile.exists())write(deviceFile,UUID.randomUUID().toString())
        val prepared=mapOf("deviceId" to deviceFile.readText(),"publicKey" to Base64.encodeToString(pair.public.encoded,Base64.NO_WRAP))
        bio("BIO_KEYSTORE_PREPARE_OK",diagnose)
        result.success(prepared)
       }
       "commit" -> {
        require(keys.containsAlias(alias) && pending==null)
        val binding=JSONObject().put("owner",owner).put("username",args["username"] as String)
          .put("credentialId",UUID.fromString(args["credentialId"] as String).toString())
          .put("deviceId",UUID.fromString(args["deviceId"] as String).toString())
        write(file,binding.toString());bio("BIO_LOCAL_BINDING_OK",diagnose);result.success(null)
       }
       "remove" -> {
        require(pending==null)
        if(keys.containsAlias(alias))keys.deleteEntry(alias)
        if(file.exists())check(file.delete())
        result.success(null)
       }
       "sign" -> sign(alias,args["payload"] as String,result,diagnose) { diagnosticPoint=it }
       else -> result.notImplemented()
      }
     }
    }
   } catch(error:Exception) {
    val stage=if(call.method=="prepare")"BIO_KEYSTORE_PREPARE_FAILED" else if(call.method=="sign")"BIO_ANDROID_PROMPT_FAILED" else "BIO_ENABLE_COMPLETED"
    bio(stage,diagnose,diagnosticPoint,error)
    result.error("UNAVAILABLE","Device credential unavailable",if(diagnose)diagnosticDetails(diagnosticPoint,error)else null)
   }
  }
 }
 private fun write(file:File,value:String) {
  val atomic=AtomicFile(file);val stream=atomic.startWrite()
  try { stream.write(value.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
  catch(error:Exception){atomic.failWrite(stream);throw error}
 }
 private fun signer(alias:String)=Signature.getInstance("SHA256withRSA").apply { initSign(keys.getKey(alias,null) as PrivateKey) }
 private fun sign(alias:String,payload:String,result:MethodChannel.Result,diagnose:Boolean,diagnosticPoint:(String)->Unit) {
  require(pending==null && payload.length<=2048 && payload.startsWith("SIMERTPI-DEVICE-V1\n"))
  val signature=signer(alias);pending=result
  diagnosticPoint("PROMPT_START_FAILED")
  fun finish(value:String?=null,error:String?=null,cause:Exception?=null,point:String?=null,androidCode:Int?=null) {
   if(pending!==result)return
   pending=null;prompt=null
   if(error!=null) {
    bio("BIO_ANDROID_PROMPT_FAILED",diagnose,point?:error,cause,androidCode)
    result.error(error,"Biometric verification did not complete",if(diagnose)diagnosticDetails(point?:error,cause,androidCode)else null)
   } else {bio("BIO_ANDROID_PROMPT_OK",diagnose);result.success(value)}
  }
  prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback(){
   override fun onAuthenticationSucceeded(authentication:BiometricPrompt.AuthenticationResult) {
    try {
     check(authentication.authenticationType==BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC)
     val authorized=authentication.cryptoObject?.signature ?: throw IllegalStateException()
     authorized.update(payload.toByteArray(Charsets.UTF_8));finish(Base64.encodeToString(authorized.sign(),Base64.NO_WRAP))
    }catch(error:Exception){finish(error="INVALIDATED",cause=error,point="SIGNATURE_FAILED")}
   }
   override fun onAuthenticationFailed(){val current=prompt;finish(error="NOT_RECOGNIZED");current?.cancelAuthentication()}
   override fun onAuthenticationError(code:Int,message:CharSequence){finish(androidCode=code,error=when(code){
    BiometricPrompt.ERROR_LOCKOUT -> "LOCKOUT"
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "LOCKOUT_PERMANENT"
    BiometricPrompt.ERROR_USER_CANCELED,BiometricPrompt.ERROR_CANCELED,BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "CANCELLED"
    else -> "UNAVAILABLE"
   })}
  })
  val info=BiometricPrompt.PromptInfo.Builder().setTitle("Ingresar con huella")
    .setSubtitle("Verifica tu identidad en este dispositivo")
    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    .setNegativeButtonText("Utilizar contraseña").build()
  try {bio("BIO_ANDROID_PROMPT_START",diagnose);prompt!!.authenticate(info,BiometricPrompt.CryptoObject(signature))}
  catch(error:Exception){finish(error="UNAVAILABLE",cause=error,point="PROMPT_START_FAILED")}
 }
 private fun diagnosticDetails(point:String,error:Exception?,androidCode:Int?=null):Map<String,Any> {
  val allowed=setOf("IllegalArgumentException","IllegalStateException","InvalidAlgorithmParameterException","KeyStoreException",
    "KeyPermanentlyInvalidatedException","UserNotAuthenticatedException","UnrecoverableKeyException","InvalidKeyException",
    "NoSuchAlgorithmException","ProviderException","SignatureException","IOException","JSONException","SecurityException")
  val type=error?.javaClass?.simpleName
  return mutableMapOf<String,Any>("nativeCode" to point,"exceptionClass" to if(type in allowed)type!! else "OTHER").apply {
   if(androidCode!=null && androidCode in 1..1000)put("androidCode",androidCode)
  }
 }
 private fun bio(stage:String,enabled:Boolean,point:String?=null,error:Exception?=null,androidCode:Int?=null) {
  if(!enabled || activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)return
  try {
   val details=if(point!=null)diagnosticDetails(point,error,androidCode)else emptyMap()
   Log.d("SIMERTPI_BIO", "event=$stage source=NATIVE"+(if(point!=null)" result=FAILED errorType=NATIVE"else "")+details.entries.joinToString("") { " ${it.key}=${it.value}" })
  }catch(_:Exception){ /* Logging never changes a crypto operation. */ }
 }
}
