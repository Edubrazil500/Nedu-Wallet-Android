package com.nedu.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nedu.wallet.data.AccountProfile
import com.nedu.wallet.data.SupabaseClientProvider
import com.nedu.wallet.data.WalletBalance
import com.nedu.wallet.data.WalletRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import android.util.Base64

private val NeduRed = Color(0xFFC91D27)
private val DeepRed = Color(0xFF98151F)
private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF85858D)
private val Soft = Color(0xFFF2F1F5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NeduApp() }
    }
}

@Composable
fun NeduApp() {
    var screen by remember { mutableStateOf("splash") }
    LaunchedEffect(screen) {
        if (screen == "splash") {
            delay(1_500)
            screen = "welcome"
        }
    }
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var hideBalance by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authNotice by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var wallets by remember { mutableStateOf<List<WalletBalance>>(emptyList()) }
    var selectedWallet by remember { mutableStateOf<WalletBalance?>(null) }
    var profile by remember { mutableStateOf<AccountProfile?>(null) }
    var identityType by remember { mutableStateOf("bvn") }
    var identityNumber by remember { mutableStateOf("") }
    var identitySelfie by remember { mutableStateOf<String?>(null) }
    var identityMessage by remember { mutableStateOf<String?>(null) }
    var identityError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val walletRepository = remember {
        if (SupabaseClientProvider.isConfigured) WalletRepository() else null
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    authError = null
                    authNotice = null
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not read the selected photo.")
                    val repository = walletRepository
                        ?: throw IllegalStateException("Supabase is not configured in this build.")
                    repository.uploadMyAvatar(bytes, mimeType)
                    profile = repository.loadMyProfile()
                    authNotice = "Your profile photo has been updated."
                } catch (error: Exception) {
                    authError = error.message ?: "Unable to upload your profile photo."
                }
            }
        }
    }

    val identityCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            try {
                val output = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
                val bytes = output.toByteArray()
                require(bytes.size <= 6 * 1024 * 1024) { "Captured selfie is too large. Please try again." }
                identitySelfie = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                identityError = null
                identityMessage = "Camera selfie captured. It will be checked by Prembly when you submit."
            } catch (error: Exception) {
                identitySelfie = null
                identityError = error.message ?: "Unable to capture that selfie."
            }
        } else {
            identityError = "Camera capture was cancelled. Please take a selfie to continue."
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = NeduRed, onPrimary = Color.White, background = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            when (screen) {
                "splash" -> SplashScreen { }
                "welcome" -> WelcomeScreen(
                    onCreate = { authError = null; authNotice = null; screen = "signup" },
                    onLogin = { authError = null; authNotice = null; screen = "login" }
                )
                "signup" -> SignupScreen(
                    fullName = fullName,
                    onFullName = { fullName = it },
                    phone = phone,
                    onPhone = { phone = it },
                    email = email,
                    onEmail = { email = it },
                    password = password,
                    onPassword = { password = it },
                    errorMessage = authError,
                    loading = isLoading,
                    onBack = { screen = "welcome" },
                    onLogin = { authError = null; authNotice = null; screen = "login" },
                    onSignUp = {
                        val repository = walletRepository
                        if (repository == null) {
                            authError = "This build is not connected to Supabase. Configure the existing project URL and publishable key."
                        } else {
                            scope.launch {
                                isLoading = true
                                authError = null
                                authNotice = null
                                try {
                                    email = email.trim()
                                    val hasSession = repository.signUp(fullName, phone, email, password)
                                    if (hasSession) {
                                        profile = repository.loadMyProfile()
                                            ?: throw IllegalStateException("Your account was created, but its profile is not available yet. Please contact Nedu Wallet support.")
                                        wallets = repository.loadMyWallets()
                                        if (wallets.count { it.isCrypto } != 2) {
                                            throw IllegalStateException("Your account was created, but its two crypto wallet records are not ready. Please contact Nedu Wallet support.")
                                        }
                                        screen = if (profile?.fiatKycComplete == true) "home" else "identity-verification"
                                    } else {
                                        password = ""
                                        authNotice = "Your account has been created. Please check your email and confirm your address before signing in."
                                        screen = "login"
                                    }
                                } catch (error: Exception) {
                                    authError = error.message ?: "Unable to create your account. Please check your details and try again."
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                )
                "login" -> LoginScreen(
                    email = email,
                    onEmail = { email = it },
                    password = password,
                    onPassword = { password = it },
                    errorMessage = authError,
                    noticeMessage = authNotice,
                    loading = isLoading,
                    onLogin = {
                        val repository = walletRepository
                        if (repository == null) {
                            authError = "This build is not connected to Supabase yet. Configure the existing project's URL and publishable key in local.properties."
                        } else {
                            scope.launch {
                                isLoading = true
                                authError = null
                                authNotice = null
                                try {
                                    repository.signIn(email, password)
                                    profile = repository.loadMyProfile()
                                    wallets = repository.loadMyWallets()
                                    screen = if (wallets.any { !it.isCrypto }) "home" else "identity-verification"
                                } catch (error: Exception) {
                                    authError = error.message ?: "Unable to sign in. Check your connection and credentials."
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    },
                    onCreate = { authError = null; authNotice = null; screen = "signup" }
                )
                "home" -> WalletHome(wallets.filterNot { it.isCrypto }, hideBalance, { hideBalance = !hideBalance }, onNavigate = { screen = it }, onWalletClick = { selectedWallet = it; screen = "wallet-detail" })
                "bills" -> BillsScreen(onNavigate = { screen = it }, onBack = { screen = "home" })
                "crypto" -> CryptoWalletScreen(onNavigate = { screen = it }, onBack = { screen = "home" })
                "wallet-detail" -> selectedWallet?.let { WalletDetailScreen(it, hideBalance) { screen = "home" } } ?: run { screen = "home" }
                "send", "receive", "swap", "transfer" -> ActionFlowScreen(screen, wallets) { screen = "home" }
                "support-security-2fa", "support-backup-recovery", "support-notifications", "support-help-support", "support-terms-privacy" ->
                    SupportDetailScreen(screen) { screen = "profile" }
                "identity-verification" -> IdentityVerificationScreen(
                    idType = identityType,
                    onIdType = { identityType = it; identityNumber = ""; identityMessage = null; identityError = null },
                    idNumber = identityNumber,
                    onIdNumber = { identityNumber = it.filter(Char::isDigit).take(11) },
                    hasSelfie = identitySelfie != null,
                    message = identityMessage,
                    errorMessage = identityError,
                    loading = isLoading,
                    onChooseSelfie = { identityCamera.launch(null) },
                    onBack = { identityError = null; identityMessage = null; screen = "profile" },
                    onSubmit = {
                        val repository = walletRepository
                        if (repository == null) {
                            identityError = "Identity verification is not connected in this build."
                        } else if (identitySelfie == null) {
                            identityError = "Choose a clear selfie before continuing."
                        } else {
                            scope.launch {
                                isLoading = true
                                identityError = null
                                identityMessage = null
                                try {
                                    val resultMessage = repository.verifyIdentity(identityType, identityNumber, identitySelfie!!)
                                    identityMessage = resultMessage
                                    identityNumber = ""
                                    identitySelfie = null
                                    wallets = repository.loadMyWallets()
                                    profile = repository.loadMyProfile()
                                    if (profile?.fiatKycComplete == true) {
                                        authNotice = resultMessage
                                        screen = "home"
                                    }
                                } catch (error: Exception) {
                                    identityError = error.message ?: "Identity verification failed. Please try again."
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                )
                "card" -> CardScreen(onNavigate = { screen = it })
                "edit-profile" -> EditProfileScreen(
                    profile = profile,
                    errorMessage = authError,
                    loading = isLoading,
                    onBack = { authError = null; screen = "profile" },
                    onSave = { name, phoneValue ->
                        val repository = walletRepository
                        if (repository == null) {
                            authError = "Profile service is not configured in this build."
                        } else {
                            scope.launch {
                                isLoading = true
                                authError = null
                                try {
                                    repository.updateMyProfile(name, phoneValue)
                                    profile = repository.loadMyProfile()
                                    screen = "profile"
                                } catch (error: Exception) {
                                    authError = error.message ?: "Unable to save your profile."
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    }
                )
                else -> ProfileScreen(
                    profile = profile,
                    onNavigate = { screen = it },
                    onEditProfile = { authError = null; screen = "edit-profile" },
                    onPickPhoto = { photoPicker.launch("image/*") },
                    photoMessage = authNotice,
                    errorMessage = authError,
                    onSignOut = {
                        val repository = walletRepository
                        if (repository != null) {
                            scope.launch {
                                try {
                                    repository.signOut()
                                    wallets = emptyList()
                                    profile = null
                                    screen = "welcome"
                                } catch (error: Exception) {
                                    authError = error.message ?: "Unable to sign out."
                                }
                            }
                        } else {
                            screen = "welcome"
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SplashScreen(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(NeduRed).clickable { onContinue() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.background(Color(0xFFD92B39), RoundedCornerShape(30.dp)).padding(horizontal = 44.dp, vertical = 22.dp)) {
                Text("Nedu", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text("Nedu Wallet", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Multi-currency  ·  One account", color = Color.White.copy(.72f), fontSize = 15.sp)
        }
    }
}

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onLogin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Nedu", color = NeduRed, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(48.dp))
        Text("Welcome to Nedu Wallet", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Text("Access your Nedu Wallet account and view your multi-currency balances. More features will be enabled as their secure integrations are verified.",
            color = Muted, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))
        PrimaryButton("Create Account", onCreate)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Log In – I already have an account", color = DeepRed, fontSize = 16.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("NGN  USD  EUR  GBP  CAD  USDT  USDC. One app for all your money.",
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun LoginScreen(email: String, onEmail: (String) -> Unit, password: String, onPassword: (String) -> Unit,
                errorMessage: String?, noticeMessage: String?, loading: Boolean, onLogin: () -> Unit, onCreate: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(top = 40.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Log In", Modifier.fillMaxWidth(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(54.dp))
        Text("Welcome Back", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Log in to your Nedu Wallet account.", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(28.dp))
        Text("Email address", Modifier.fillMaxWidth(), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        SoftField(value = email, onValueChange = onEmail, hint = "Enter your email address", keyboard = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        Text("Password", Modifier.fillMaxWidth(), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            placeholder = { Text("Enter your password", color = Muted) },
            shape = RoundedCornerShape(18.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted)
            } },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color(0xFFEDECF1),
                focusedContainerColor = Color(0xFFFFF1F2),
                unfocusedBorderColor = Color(0xFF77727F),
                focusedBorderColor = NeduRed,
                unfocusedTextColor = Color(0xFF111111),
                focusedTextColor = Color(0xFF111111),
                cursorColor = NeduRed,
                unfocusedPlaceholderColor = Muted,
                focusedPlaceholderColor = Muted
            ))
        Spacer(Modifier.height(6.dp))
        Text("Tap the eye icon to show or hide your password.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("Password must have capital, small, number and character.", color = Muted, fontSize = 13.sp)
        if (errorMessage != null) {
            Text(errorMessage, color = NeduRed, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        if (noticeMessage != null) {
            Text(noticeMessage, color = Color(0xFF247A42), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (loading) "Signing In..." else "Log In", onLogin, enabled = !loading)
        TextButton(onClick = { /* Password recovery will be connected with the verified Auth redirect flow. */ }) {
            Text("Forgot Password?", color = DeepRed, fontSize = 16.sp)
        }
        TextButton(onClick = onCreate) { Text("New here? Create Account", color = DeepRed, fontWeight = FontWeight.Bold) }
        Text("By continuing you agree to Nedu Wallet terms.", color = Muted, fontSize = 13.sp)
    }
}


@Composable
fun SignupScreen(
    fullName: String,
    onFullName: (String) -> Unit,
    phone: String,
    onPhone: (String) -> Unit,
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    errorMessage: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onSignUp: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Create Account", Modifier.fillMaxWidth(), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "Create your Nedu Wallet account. Your profile and currency wallets will be set up automatically.",
            color = Muted,
            fontSize = 15.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(24.dp))
        SoftField(value = fullName, onValueChange = onFullName, hint = "Full name")
        Spacer(Modifier.height(12.dp))
        SoftField(value = phone, onValueChange = onPhone, hint = "Phone number (optional)", keyboard = KeyboardType.Phone)
        Spacer(Modifier.height(12.dp))
        Text("Email address", Modifier.fillMaxWidth(), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        SoftField(value = email, onValueChange = onEmail, hint = "Enter your email address", keyboard = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        Text("Password", Modifier.fillMaxWidth(), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            placeholder = { Text("Enter a password", color = Muted) },
            shape = RoundedCornerShape(18.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fieldColors()
        )
        Spacer(Modifier.height(10.dp))
        Text("Use a strong password that meets your Supabase Auth password requirements.", color = Muted, fontSize = 12.sp)
        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = NeduRed, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (loading) "Creating Account..." else "Create Account", onSignUp, enabled = !loading)
        TextButton(onClick = onBack, enabled = !loading) {
            Text("Back", color = DeepRed)
        }
        TextButton(onClick = onLogin, enabled = !loading) {
            Text("Already have an account? Log in", color = DeepRed)
        }
    }
}

@Composable
fun WalletHome(wallets: List<WalletBalance>, hidden: Boolean, toggleHidden: () -> Unit, onNavigate: (String) -> Unit, onWalletClick: (WalletBalance) -> Unit) {
    val ngnBalance = wallets.firstOrNull { it.code.equals("NGN", true) }?.balance ?: "0"
    Scaffold(bottomBar = { BottomBar("home", onNavigate) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(22.dp))
                Text("Good to see you", color = Muted, fontSize = 14.sp)
                Text("Nedu Wallet", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(18.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = DeepRed)) {
                    Column(Modifier.background(Brush.linearGradient(listOf(NeduRed, DeepRed, Color(0xFF64101A)))).padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Available NGN balance", color = Color.White.copy(.82f), modifier = Modifier.weight(1f), fontSize = 14.sp)
                            IconButton(onClick = toggleHidden) { Icon(if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.White) }
                        }
                        Text(if (hidden) "₦ ••••••••" else "₦ $ngnBalance", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("Your other currency balances are listed separately below.", color = Color.White.copy(.76f), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ActionTile("↑", "Send", Modifier.weight(1f)) { onNavigate("send") }
                    ActionTile("↓", "Receive", Modifier.weight(1f)) { onNavigate("receive") }
                    ActionTile("⇄", "Swap", Modifier.weight(1f)) { onNavigate("swap") }
                    ActionTile("▣", "Bills", Modifier.weight(1f)) { onNavigate("bills") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onNavigate("crypto") }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AccountBalanceWallet, null); Spacer(Modifier.width(8.dp)); Text("Open Non-Custodial Crypto Wallets")
                }
                Spacer(Modifier.height(23.dp))
                Text("My Wallets", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Tap any wallet to view its balance and account details.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(9.dp))
            }
            val fiatWallets = wallets.filterNot { it.isCrypto }
            if (fiatWallets.isEmpty()) {
                item { Text("No fiat wallets were returned for this account.", color = Muted, modifier = Modifier.padding(vertical = 20.dp)) }
            } else {
                fiatWallets.forEach { wallet ->
                    item {
                        WalletRow(
                            flag = currencyMark(wallet.code), name = wallet.name, code = wallet.code,
                            amount = "${wallet.balance} ${wallet.code}", hidden = hidden,
                            onClick = { onWalletClick(wallet) }, crypto = wallet.isCrypto
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(14.dp)) }
        }
    }
}

@Composable
fun WalletRow(flag: String, name: String, code: String, amount: String, hidden: Boolean, onClick: () -> Unit, crypto: Boolean = false) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (crypto) Color(0xFFEAF5EF) else Color(0xFFFFEEEE)), contentAlignment = Alignment.Center) {
                Text(flag, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (crypto) Color(0xFF16764B) else DeepRed)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink)
                Text(if (crypto) "$code · Digital asset" else "$code · Cash wallet", color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (hidden) "••••••" else amount, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
                Text("Available", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
fun WalletDetailScreen(wallet: WalletBalance, hidden: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, null, tint = DeepRed)
            Text("Back to wallets", color = DeepRed)
        }
        Spacer(Modifier.height(20.dp))
        Text(wallet.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(wallet.code, color = Muted)
        Spacer(Modifier.height(22.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = DeepRed)) {
            Column(Modifier.background(Brush.horizontalGradient(listOf(NeduRed, DeepRed))).padding(24.dp)) {
                Text("Available balance", color = Color.White.copy(.8f))
                Spacer(Modifier.height(8.dp))
                Text(if (hidden) "••••••" else wallet.balance + " " + wallet.code, color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Wallet information", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Currency: " + wallet.code, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("Type: " + if (wallet.isCrypto) "Cryptocurrency" else "Fiat currency", color = Ink)
        if (wallet.isCrypto) {
            Spacer(Modifier.height(18.dp))
            Text("Receive address", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(8.dp))
            if (!wallet.address.isNullOrBlank()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(wallet.address, color = Ink, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Confirm the supported network with Nedu before sending funds.", color = Muted, fontSize = 11.sp)
                    }
                }
            } else {
                Text("A deposit address has not been provisioned for this wallet yet. Do not send USDT or USDC to an address from another network or service.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Transaction activity will appear here as verified wallet services become available.", color = Muted)
    }
}
@Composable
fun CardScreen(onNavigate: (String) -> Unit) {
    Scaffold(bottomBar = { BottomBar("card", onNavigate) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(22.dp)) {
            Spacer(Modifier.height(18.dp))
            Text("Nedu Card", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = DeepRed)) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(NeduRed, DeepRed, Color(0xFF6F1019)))).padding(22.dp)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Nedu", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            Text("CARD", color = Color.White.copy(.85f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("••••  ••••  ••••  ••••", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(12.dp))
                            Text("CARD NOT ISSUED", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Your card will appear here after a verified issuer integration.", color = Color.White.copy(.8f), fontSize = 11.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Card services", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text("A real card number, expiry date, CVV, Apple Pay or card controls will only be shown after a licensed card issuer provisions a card for your account.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(profile: AccountProfile?, onNavigate: (String) -> Unit, onEditProfile: () -> Unit, onPickPhoto: () -> Unit, photoMessage: String?, errorMessage: String?, onSignOut: () -> Unit) {
    Scaffold(bottomBar = { BottomBar("profile", onNavigate) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Profile", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = DeepRed)) {
                Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(NeduRed, DeepRed))).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val initials = profile?.name.orEmpty().trim().split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("")
                    Box(Modifier.size(92.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        if (!profile?.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = profile?.avatarUrl,
                                contentDescription = "Customer profile photo",
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            Text(initials.ifBlank { "NW" }, color = DeepRed, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(profile?.name?.takeIf { it.isNotBlank() } ?: "Nedu Wallet Customer", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Nedu Wallet account", color = Color.White.copy(.82f), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onPickPhoto, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Change profile photo")
                    }
                    if (!photoMessage.isNullOrBlank()) {
                        Text(photoMessage, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    if (!errorMessage.isNullOrBlank()) {
                        Text(errorMessage, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Personal Information", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
                Column(Modifier.padding(18.dp)) {
                    ProfileValue("Full name", profile?.name ?: "Not available")
                    ProfileValue("Email", profile?.email ?: "Not available")
                    ProfileValue("Phone", profile?.phone?.takeIf { it.isNotBlank() } ?: "Not added")
                    ProfileValue("Country", profile?.country?.takeIf { it.isNotBlank() } ?: "Not set", last = true)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onEditProfile, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepRed)) {
                Icon(Icons.Default.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text("Edit Personal Information", fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = { onNavigate("identity-verification") }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepRed)) {
                Icon(Icons.Default.VerifiedUser, null)
                Spacer(Modifier.width(8.dp))
                Text("BVN & NIN Identity Verification", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            Spacer(Modifier.height(22.dp))
            Text("Account & Support", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(10.dp))
            listOf("Security & 2FA", "Backup & Recovery", "Notifications", "Help & Support", "Terms & Privacy").forEach { label ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                    onNavigate("support-" + label.lowercase().replace(" & ", "-").replace(" ", "-"))
                }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
                    Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium, color = Ink)
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) {
                Text("Log Out", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Nedu Wallet · Secure account", modifier = Modifier.fillMaxWidth(), color = Muted, textAlign = TextAlign.Center, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String, last: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Muted, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        }
        if (!last) HorizontalDivider(color = Color(0xFFE2E0E7))
    }
}

@Composable
fun EditProfileScreen(
    profile: AccountProfile?,
    errorMessage: String?,
    loading: Boolean,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(profile?.name) { mutableStateOf(profile?.name.orEmpty()) }
    var phone by remember(profile?.phone) { mutableStateOf(profile?.phone.orEmpty()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 22.dp).padding(top = 28.dp, bottom = 24.dp)
    ) {
        TextButton(onClick = onBack, enabled = !loading) { Text("Back", color = DeepRed) }
        Text("Personal Information", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("Update the name and phone number shown on your Nedu Wallet profile.", color = Muted)
        Spacer(Modifier.height(24.dp))
        Text("Full name", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SoftField(value = name, onValueChange = { name = it }, hint = "Full name")
        Spacer(Modifier.height(16.dp))
        Text("Email address", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Soft)) {
            Text(profile?.email.orEmpty(), Modifier.padding(16.dp), color = Ink, fontSize = 16.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text("Email changes must be confirmed through Supabase account security.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text("Phone number", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SoftField(value = phone, onValueChange = { phone = it }, hint = "Phone number", keyboard = KeyboardType.Phone)
        if (errorMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(errorMessage, color = NeduRed, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (loading) "Saving..." else "Save Changes", { onSave(name, phone) }, enabled = !loading)
    }
}

@Composable
fun ActionFlowScreen(action: String, wallets: List<WalletBalance>, onBack: () -> Unit) {
    val title = when (action) { "send" -> "Send money"; "receive" -> "Receive money"; "swap" -> "Swap currencies"; else -> "Transfer between wallets" }
    val description = when (action) {
        "send" -> "Choose a wallet and recipient. Outgoing transfers remain protected until a verified payout or blockchain signing service is connected."
        "receive" -> "Select a wallet to see its provisioned receiving details. Only use an address and network explicitly assigned to your account."
        "swap" -> "Choose between USDT and USDC. A live executable quote and secure custody integration are required before conversion can be enabled."
        else -> "Move funds between your own Nedu wallets only after the server-side transfer flow is enabled and tested."
    }
    var selectedCode by remember(action) { mutableStateOf(if (action == "swap") "USDT" else wallets.firstOrNull()?.code ?: "NGN") }
    var secondCode by remember(action) { mutableStateOf("USDC") }
    var amount by remember(action) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(22.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = DeepRed); Text("Back to wallets", color = DeepRed) }
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(description, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(22.dp))
        when (action) {
            "receive" -> {
                Text("Select receiving wallet", fontWeight = FontWeight.SemiBold, color = Ink)
                wallets.forEach { w ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { selectedCode = w.code }, colors = CardDefaults.cardColors(containerColor = if (selectedCode == w.code) Color(0xFFFFF0F1) else Soft), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(15.dp)) {
                            Text("${w.name} (${w.code})", fontWeight = FontWeight.Bold, color = Ink)
                            if (!w.address.isNullOrBlank()) Text(w.address, color = Ink, fontSize = 12.sp)
                            else Text(if (w.isCrypto) "Address not yet provisioned" else "Account receiving details are not available in this wallet record", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Text("Never send crypto until the exact network is confirmed. A missing address means deposits are not ready for that asset.", color = NeduRed, fontSize = 12.sp)
            }
            "swap" -> {
                Text("From", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("USDT", "USDC").forEach { code ->
                        FilterChip(selected = selectedCode == code, onClick = { selectedCode = code; secondCode = if (code == "USDT") "USDC" else "USDT" }, label = { Text(code) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("To", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("USDT", "USDC").filter { it != selectedCode }.forEach { code ->
                        FilterChip(selected = secondCode == code, onClick = { secondCode = code }, label = { Text(code) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Text("Live rate unavailable — no conversion will be estimated or executed until the approved provider supplies a fresh quote.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Swap temporarily unavailable", {}, enabled = false)
            }
            else -> {
                Text("From wallet", fontWeight = FontWeight.SemiBold)
                wallets.forEach { w ->
                    if (w.code == selectedCode) Text("${w.name} · ${w.balance} ${w.code}", color = Ink)
                }
                Spacer(Modifier.height(12.dp))
                Text("Amount", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(12.dp))
                Text(if (action == "send") "Recipient details and payout execution are not yet connected." else "Internal transfer execution is not yet connected. No funds will be moved from this screen.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (action == "send") "Sending unavailable" else "Transfer unavailable", {}, enabled = false)
            }
        }
    }
}

@Composable
fun SupportDetailScreen(topic: String, onBack: () -> Unit) {
    val title = topic.removePrefix("support-").split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    val detail = when (topic) {
        "support-security-2fa" -> "Review account security and sign-in protection. Two-factor authentication controls will appear here when a verified MFA flow is enabled for this account."
        "support-backup-recovery" -> "Keep your email and phone number up to date. Account recovery must be completed through verified Supabase authentication; never share your password or one-time codes."
        "support-notifications" -> "Transaction and security notifications will be shown here when notification preferences and delivery are connected."
        "support-help-support" -> "For help with your account, contact Nedu Wallet support through the official support channel. Never send your password, private key or recovery phrase."
        else -> "Review the applicable Nedu Wallet terms and privacy information before using financial services. This section will display the approved current documents."
    }
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = DeepRed); Text("Back to profile", color = DeepRed) }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(20.dp)) {
            Text(detail, Modifier.padding(18.dp), color = Ink, fontSize = 15.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
fun IdentityVerificationScreen(
    idType: String,
    onIdType: (String) -> Unit,
    idNumber: String,
    onIdNumber: (String) -> Unit,
    hasSelfie: Boolean,
    message: String?,
    errorMessage: String?,
    loading: Boolean,
    onChooseSelfie: () -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, null, tint = DeepRed)
            Text("Back to profile", color = DeepRed)
        }
        Text("Identity Verification", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            "Complete both BVN and NIN checks with a live selfie match to unlock eligible fiat wallet services.",
            color = Muted, fontSize = 14.sp, lineHeight = 21.sp
        )
        Card(colors = CardDefaults.cardColors(containerColor = Soft), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your information is sent over an encrypted connection to Nedu Wallet's verification service and Prembly. It is not displayed publicly.", color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                Text("Never share your BVN, NIN or selfie in chat or with anyone claiming to be support.", color = DeepRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Choose verification type", color = Ink, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = idType == "bvn", onClick = { onIdType("bvn") }, label = { Text("BVN") })
            FilterChip(selected = idType == "nin", onClick = { onIdType("nin") }, label = { Text("NIN") })
        }
        Text(if (idType == "bvn") "11-digit Bank Verification Number" else "11-digit National Identification Number", color = Ink, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = idNumber,
            onValueChange = onIdNumber,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Enter 11 digits") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp),
            colors = fieldColors()
        )
        OutlinedButton(onClick = onChooseSelfie, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.CameraAlt, null)
            Spacer(Modifier.width(8.dp))
            Text(if (hasSelfie) "Selfie captured · Take another" else "Take selfie with camera")
        }
        if (message != null) Text(message, color = Color(0xFF247A42), fontSize = 13.sp)
        if (errorMessage != null) Text(errorMessage, color = NeduRed, fontSize = 13.sp)
        PrimaryButton(
            if (loading) "Verifying..." else "Verify ${idType.uppercase()}",
            onClick = onSubmit,
            enabled = !loading && idNumber.length == 11 && hasSelfie
        )
        Text("Complete the second check separately. Fiat wallet access remains restricted until both BVN and NIN are verified.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun BillsScreen(onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val categories = listOf(
        Triple("Mobile Airtime", "MTN, Airtel, Glo, 9mobile", "📱"),
        Triple("Mobile Data", "Data bundles and subscriptions", "🌐"),
        Triple("Electricity", "Prepaid and postpaid electricity", "⚡"),
        Triple("Cable TV", "DStv, GOtv and StarTimes", "📺"),
        Triple("Internet", "Home and business internet", "🛜"),
        Triple("Other Local Bills", "Other supported Nigerian billers", "🧾")
    )
    Scaffold(bottomBar = { BottomBar("bills", onNavigate) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(22.dp)) {
            TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = DeepRed); Text("Back to Home", color = DeepRed) }
            Text("Bills & Utilities", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("Supported Nigerian airtime, data, electricity, cable TV and internet bills.", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            categories.forEach { (title, subtitle, emoji) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(emoji, fontSize = 25.sp); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text(subtitle, color = Muted, fontSize = 12.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = Muted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), shape = RoundedCornerShape(16.dp)) {
                Text("Payments are not enabled yet. Provider catalogue lookup, customer validation, atomic wallet debit, provider confirmation and automatic refunds must be completed before money can be taken. This screen cannot debit your wallet.", Modifier.padding(16.dp), color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
fun CryptoWalletScreen(onNavigate: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(bottomBar = { BottomBar("crypto", onNavigate) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(22.dp)) {
            TextButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = DeepRed); Text("Back to Home", color = DeepRed) }
            Text("Crypto Wallets", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("Separate self-custody section. Fiat balances are not shown here.", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            listOf(
                Triple("TRON · TRC20", "USDT supported; native USDC is not offered", "USDT"),
                Triple("BNB Smart Chain · BEP20", "Binance-Peg tokens, clearly distinguished from issuer-native tokens", "USDT / USDC"),
                Triple("Solana · SPL", "Native USDT and USDC", "USDT / USDC")
            ).forEach { (network, detail, assets) ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
                    Column(Modifier.padding(17.dp)) {
                        Text(network, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(detail, color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(7.dp)); Text(assets, color = DeepRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Wallet creation and import are not activated yet", color = Ink, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Do not enter a recovery phrase or send funds in this build. Secure generation, 12/24-word backup verification, wallet import/recovery, Android Keystore encryption and on-device signing require the audited wallet SDK dependency and a successful device test.", color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Network gas fees", fontWeight = FontWeight.Bold, color = Ink)
            Text("TRON uses TRX, BNB Smart Chain uses BNB, and Solana uses SOL for network fees.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
fun BottomBar(selected: String, onNavigate: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFFFAFAFC)) {
        NavigationBarItem(selected == "home", onClick = { onNavigate("home") }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
        NavigationBarItem(selected == "bills", onClick = { onNavigate("bills") }, icon = { Icon(Icons.Default.ReceiptLong, null) }, label = { Text("Bills") })
        NavigationBarItem(selected == "crypto", onClick = { onNavigate("crypto") }, icon = { Icon(Icons.Default.CurrencyBitcoin, null) }, label = { Text("Crypto") })
        NavigationBarItem(selected == "card", onClick = { onNavigate("card") }, icon = { Icon(Icons.Default.CreditCard, null) }, label = { Text("Card") })
        NavigationBarItem(selected == "profile", onClick = { onNavigate("profile") }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
    }
}

@Composable
fun ActionTile(symbol: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.height(86.dp).clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(symbol, color = DeepRed, fontSize = 25.sp)
            Text(label, color = DeepRed, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = NeduRed)) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SoftField(value: String, onValueChange: (String) -> Unit, hint: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(color = Ink, fontSize = 16.sp),
        placeholder = { Text(hint, color = Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(18.dp),
        colors = fieldColors()
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Color(0xFFF7F6F8),
    focusedContainerColor = Color(0xFFFFF8F8),
    unfocusedBorderColor = Color(0xFFB8B6C2),
    focusedBorderColor = NeduRed,
    unfocusedTextColor = Ink,
    focusedTextColor = Ink,
    cursorColor = NeduRed,
    unfocusedPlaceholderColor = Muted,
    focusedPlaceholderColor = Muted,
    unfocusedLabelColor = Muted,
    focusedLabelColor = NeduRed
)


private fun currencyMark(code: String): String = when (code.uppercase()) {
    "NGN" -> "₦"
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "CAD" -> "C$"
    "USDT" -> "₮"
    "USDC" -> "$"
    else -> "¤"
}
