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
                                        if (wallets.size != 7) {
                                            throw IllegalStateException("Your account was created, but all seven wallets were not returned. Please contact Nedu Wallet support before using the account.")
                                        }
                                        screen = "home"
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
                                    screen = "home"
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
                "home" -> WalletHome(wallets, hideBalance, { hideBalance = !hideBalance }, onNavigate = { screen = it }, onWalletClick = { selectedWallet = it; screen = "wallet-detail" })
                "wallet-detail" -> selectedWallet?.let { WalletDetailScreen(it, hideBalance) { screen = "home" } } ?: run { screen = "home" }
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
            placeholder = { Text("Password", color = Color.LightGray) },
            shape = RoundedCornerShape(18.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Muted)
            } },
            colors = fieldColors())
        Spacer(Modifier.height(12.dp))
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
    Scaffold(bottomBar = { BottomBar("home", onNavigate) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(24.dp))
                Text("Nedu Wallet", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(NeduRed, DeepRed)), RoundedCornerShape(28.dp)).padding(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your Wallets", color = Color.White.copy(.8f), fontSize = 15.sp)
                            IconButton(onClick = toggleHidden) { Icon(if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.White) }
                        }
                        Text("Multi-currency account", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("Balances are loaded from your account", color = Color.White.copy(.7f), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("↑", "Send", Modifier.weight(1f))
                    ActionTile("↓", "Receive", Modifier.weight(1f))
                    ActionTile("⇄", "Swap", Modifier.weight(1f))
                    ActionTile("◎", "Transfer", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Send, receive, swap and transfer will be enabled after their secure server-side workflows are verified.",
                    color = Muted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                Text("Your Wallets", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
            }
            if (wallets.isEmpty()) {
                item {
                    Text("No wallets were returned for this account.", color = Muted, modifier = Modifier.padding(vertical = 20.dp))
                }
            } else {
                wallets.forEach { wallet ->
                    item {
                        WalletRow(
                            flag = currencyMark(wallet.code),
                            name = wallet.name,
                            code = wallet.code,
                            amount = "${wallet.balance} ${wallet.code}",
                            hidden = hidden,
                            onClick = { onWalletClick(wallet) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WalletRow(flag: String, name: String, code: String, amount: String, hidden: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = 25.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(code, color = Muted, fontSize = 14.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (hidden) "••••" else amount, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Available balance", color = Muted, fontSize = 12.sp)
            }
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
        if (!wallet.address.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Wallet address: " + wallet.address, color = Ink)
        }
        Spacer(Modifier.height(18.dp))
        Text("Transaction history and transfers will appear here when secure wallet services are connected.", color = Muted)
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
            Spacer(Modifier.height(22.dp))
            Text("Account & Support", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(10.dp))
            listOf("Security & 2FA", "Backup & Recovery", "Notifications", "Help & Support", "Terms & Privacy").forEach { label ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Soft)) {
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
fun BottomBar(selected: String, onNavigate: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFFFAFAFC)) {
        NavigationBarItem(selected == "home", onClick = { onNavigate("home") }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
        NavigationBarItem(selected == "card", onClick = { onNavigate("card") }, icon = { Icon(Icons.Default.CreditCard, null) }, label = { Text("Card") })
        NavigationBarItem(selected == "profile", onClick = { onNavigate("profile") }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
    }
}

@Composable
fun ActionTile(symbol: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier.height(86.dp), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(symbol, color = DeepRed, fontSize = 27.sp)
            Text(label, color = DeepRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
