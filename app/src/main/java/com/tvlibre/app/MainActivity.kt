package com.tvlibre.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.net.URL
import java.util.concurrent.TimeUnit

data class MediaEntry(val title: String, val category: String, val type: String, val badge: String, val streamUrl: String)

val BgDeepNavy = Color(0xFF0A0E1A)
val CardDarkBlue = Color(0xFF131B2E)
val AccentCyan = Color(0xFF06B6D4)
val AccentOrange = Color(0xFFFF5722)
val LiveRed = Color(0xFFEF4444)
val AvatarGradients = listOf(
    listOf(Color(0xFFFF512F), Color(0xFFDD2476)),
    listOf(Color(0xFF00C6FF), Color(0xFF0072FF)),
    listOf(Color(0xFFF7971E), Color(0xFFFFD200)),
    listOf(Color(0xFF11998E), Color(0xFF38EF7D)),
    listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))
)

fun parseM3uContent(raw: String, originTag: String): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    val lines = raw.lines()
    var currentTitle = ""
    var currentGroup = originTag
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXTINF:")) {
            val groupMatch = Regex("""group-title="([^"]*)"""").find(trimmed)
            val grp = groupMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            currentGroup = if (grp != null) "$originTag • $grp" else originTag
            currentTitle = trimmed.substringAfterLast(",").trim()
        } else if (trimmed.startsWith("http") && currentTitle.isNotEmpty()) {
            result.add(MediaEntry(currentTitle, currentGroup, "TV", "M3U", trimmed))
            currentTitle = ""
        }
    }
    return result
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDeepNavy, surface = CardDarkBlue, primary = AccentCyan)) {
                TvLibrePlusApp()
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun TvLibrePlusApp() {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf("TV") }
    var selectedSub by remember { mutableStateOf("Todos") }
    var searchQuery by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("Listo") }
    var playerStatus by remember { mutableStateOf("Cargando señal...") }

    val remoteJsonUrl = "https://raw.githubusercontent.com/pabloch-ayal/tv-libre-apk/main/catalogo.json"
    val peruM3uUrl = "https://iptv-org.github.io/iptv/countries/pe.m3u"
    val spanishM3uUrl = "https://iptv-org.github.io/iptv/languages/spa.m3u"

    val defaultList = remember {
        listOf(
            MediaEntry("TV Perú (7.1 HD)", "Perú • Nacional", "TV", "HD", "https://cdn.stream.irtp.gob.pe/tvperu/smil:tvperu.smil/playlist.m3u8"),
            MediaEntry("DW Español", "Internacional • Noticias", "TV", "HD", "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8"),
            MediaEntry("La Noche de los Muertos Vivientes (1968)", "Cine • Terror", "VOD", "PELÍCULA", "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_512kb.mp4")
        )
    }
    var catalog by remember { mutableStateOf(defaultList) }
    var currentMedia by remember { mutableStateOf(defaultList.first()) }

    suspend fun loadAllSources(includeLatam: Boolean = false) {
        statusMsg = "Sincronizando señales..."
        withContext(Dispatchers.IO) {
            val combined = mutableListOf<MediaEntry>()
            try {
                val arr = JSONArray(URL(remoteJsonUrl).readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    combined.add(MediaEntry(o.getString("title"), o.optString("category", "General"), o.optString("type", "TV"), o.optString("badge", "HD"), o.getString("streamUrl")))
                }
            } catch (_: Exception) { }

            try {
                val peRaw = URL(peruM3uUrl).readText()
                combined.addAll(parseM3uContent(peRaw, "Perú M3U"))
            } catch (_: Exception) { }

            if (includeLatam) {
                try {
                    val spaRaw = URL(spanishM3uUrl).readText()
                    combined.addAll(parseM3uContent(spaRaw, "Latinoamérica").take(150))
                } catch (_: Exception) { }
            }

            val distinctList = combined.distinctBy { it.streamUrl }
            if (distinctList.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    catalog = distinctList
                    statusMsg = "${distinctList.size} canales y películas"
                }
            } else {
                withContext(Dispatchers.Main) { statusMsg = "Modo local" }
            }
        }
    }

    LaunchedEffect(Unit) { loadAllSources(includeLatam = false) }

    val exoPlayer = remember {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, java.security.SecureRandom()) }
        val okHttpClient = OkHttpClient.Builder().protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        playerStatus = when (state) {
                            Player.STATE_BUFFERING -> "Conectando transmisión..."
                            Player.STATE_READY -> "Reproduciendo"
                            Player.STATE_ENDED -> "Finalizado"
                            else -> ""
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playerStatus = "Error: ${error.cause?.localizedMessage ?: error.errorCodeName}"
                    }
                })
            }
    }

    LaunchedEffect(currentMedia) {
        playerStatus = "Conectando transmisión..."
        exoPlayer.setMediaItem(MediaItem.fromUri(currentMedia.streamUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    val subs = if (selectedTab == "TV") listOf("Todos", "Perú", "Latinoamérica", "Noticias", "Deportes", "Música", "Cultura") else listOf("Todos", "Terror", "Comedia", "Acción", "Ciencia Ficción", "Animación")
    val filtered = catalog.filter { it.type == selectedTab && (selectedSub == "Todos" || it.category.contains(selectedSub, true)) && (it.title.contains(searchQuery, true) || it.category.contains(searchQuery, true)) }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F172A), BgDeepNavy))).then(if (!isLandscape) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
        if (!isLandscape) {
            Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF1E3A8A), Color(0xFF6D28D9), Color(0xFFDB2777)))).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LiveTv, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("TV LIBRE PLUS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp); Text("Creado por César Ayal", color = Color(0xFF67E8F9), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(statusMsg, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { scope.launch { loadAllSources(includeLatam = true) } }, colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.2f)), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("+ Latam", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { scope.launch { loadAllSources(includeLatam = false) } }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).size(34.dp)) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Box(if (isLandscape) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxWidth().height(225.dp).background(Color.Black)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } }, modifier = Modifier.fillMaxSize())
            if (playerStatus.isNotEmpty() && playerStatus != "Reproduciendo") {
                Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    Text(playerStatus, color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        if (!isLandscape) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF111827)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(currentMedia.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(currentMedia.category, color = AccentCyan, fontSize = 12.sp)
                }
                Surface(shape = RoundedCornerShape(6.dp), color = if (currentMedia.type == "TV") LiveRed else AccentOrange) {
                    Text(if (currentMedia.type == "TV") "● EN VIVO" else "CINE FREE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { selectedTab = "TV"; selectedSub = "Todos" }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == "TV") AccentCyan else CardDarkBlue, contentColor = if (selectedTab == "TV") Color.Black else Color.White), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("TV en Vivo", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { selectedTab = "VOD"; selectedSub = "Todos" }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == "VOD") AccentOrange else CardDarkBlue, contentColor = Color.White), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Movie, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Películas Free", fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subs.forEach { f -> FilterChip(selected = selectedSub == f, onClick = { selectedSub = f }, label = { Text(f) }) }
            }
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Buscar canal, país o película...") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(filtered) { idx, item ->
                    val isPlaying = item == currentMedia
                    val grad = AvatarGradients[idx % AvatarGradients.size]
                    val ini = item.title.split(" ").take(2).joinToString("") { it.take(1).uppercase() }
                    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(if (isPlaying) 1.5.dp else 1.dp, if (isPlaying) AccentCyan else Color(0xFF1E293B)), color = Color.Transparent, modifier = Modifier.fillMaxWidth().clickable { currentMedia = item }) {
                        Row(Modifier.background(Brush.horizontalGradient(if (isPlaying) listOf(Color(0xFF1D4ED8), Color(0xFF7C3AED)) else listOf(CardDarkBlue, Color(0xFF182238)))).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(grad))) { Text(ini, color = Color.White, fontWeight = FontWeight.ExtraBold) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.category, color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            Surface(shape = RoundedCornerShape(8.dp), color = if (isPlaying) AccentCyan else Color.White.copy(alpha = 0.12f)) {
                                Text(if (isPlaying) "VIENDO" else item.badge, color = if (isPlaying) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
