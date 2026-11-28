package com.tvlibre.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

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

fun classifyGenre(title: String, rawGroup: String): String {
    val text = "$title$rawGroup".lowercase()
    return when {
        listOf("noticia", "news", "24h", "cnn", "dw", "france 24", "euronews", "rt ", "rpp", "exitosa", "atv+", "nmas", "telediario", "info").any { it in text } -> "Noticias"
        listOf("deporte", "sport", "gol", "futbol", "fútbol", "espn", "fox sport", "tyc", "win", "liga", "motor", "box", "tenis", "fifa").any { it in text } -> "Deportes"
        listOf("cine", "movie", "película", "pelicula", "film", "acción", "accion", "terror", "horror", "thriller", "comedia", "drama", "western", "romance", "runtime").any { it in text } -> "Películas"
        listOf("documental", "documentary", "historia", "history", "ciencia", "science", "nasa", "natgeo", "discovery", "animal", "naturaleza", "nature", "cultura", "educa").any { it in text } -> "Documentales"
        listOf("serie", "novela", "anime", "ficción", "ficcion", "crimen", "misterio", "sitcom", "reality").any { it in text } -> "Series"
        listOf("infantil", "kids", "nick", "cartoon", "dibujo", "toons", "ipe", "junior", "baby").any { it in text } -> "Infantil"
        listOf("música", "musica", "music", "mtv", "hit", "rock", "pop", "latino", "concert", "radio").any { it in text } -> "Música"
        else -> "Variedades"
    }
}

fun downloadTextWithAgent(urlStr: String): String {
    var currentUrl = urlStr.trim()
    repeat(6) {
        val conn = URL(currentUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "gzip, identity")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val status = conn.responseCode
        if (status in 301..308) {
            val loc = conn.getHeaderField("Location") ?: return ""
            currentUrl = URL(URL(currentUrl), loc).toString()
            conn.disconnect()
        } else {
            val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                java.util.zip.GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix("\uFEFF")
        }
    }
    return ""
}

fun parseM3uContent(raw: String, originTag: String, badgeTag: String): List<MediaEntry> {
    val result = mutableListOf<MediaEntry>()
    val lines = raw.lines()
    var currentTitle = ""
    var currentGroup = ""
    var autoIndex = 1
    for (line in lines) {
        val trimmed = line.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
            val groupMatch = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE).find(trimmed)
            currentGroup = groupMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
            val afterComma = if ("," in trimmed) trimmed.substringAfterLast(",").trim() else ""
            val tvgName = Regex("""tvg-name="([^"]*)"""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: ""
            currentTitle = afterComma.ifEmpty { tvgName }
        } else if (trimmed.startsWith("#EXTGRP:", ignoreCase = true)) {
            currentGroup = trimmed.substringAfter(":").trim()
        } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val titleToUse = currentTitle.ifEmpty { "Canal $autoIndex ($originTag)" }
            autoIndex++
            val groupLabel = if (currentGroup.isNotEmpty()) "$currentGroup • $originTag" else "${classifyGenre(titleToUse, "")} • $originTag"
            val isVod = listOf(".mp4", ".mkv", ".avi", "/movie/", "/series/").any { trimmed.contains(it, true) }
            result.add(MediaEntry(titleToUse, groupLabel, if (isVod) "VOD" else "TV", badgeTag, trimmed))
            currentTitle = ""
            currentGroup = ""
        }
    }
    return result
}


private val MOBILE_NATIONAL_PRIORITY = listOf(
    listOf("america television", "america tv", "america hd", "america peru"),
    listOf("america internacional"),
    listOf("atv peru", "atv hd", "atv +", "atv+"),
    listOf("atv sur"),
    listOf("latina noticias"),
    listOf("latina clasico", "latina"),
    listOf("panamericana"),
    listOf("tv peru", "tvperu"),
    listOf("willax"),
    listOf("canal n"),
    listOf("rpp"),
    listOf("exitosa"),
    listOf("global tv", "global television", "red global"),
    listOf("wayra")
)

private val MOBILE_REGIONAL_KW = listOf(
    "tacna", "arequipa", "moquegua", "cusco", "cuzco", "puno", "juliaca",
    "ilo", "trujillo", "chiclayo", "piura", "iquitos", "huancayo", "ayacucho",
    "cajamarca", "chimbote", "huanuco", "ucayali", "pucallpa", "tumbes", "loreto",
    "unap", "unsa", "tv sur", "sol tv", "onda digital", "uranio", "nativa"
)

fun getMobileNationalRank(title: String): Int {
    val norm = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("[^a-z0-9+ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (norm.contains("argentina") || norm.contains("colombia") || norm.contains("mexico") || norm.contains("chile")) return 999
    MOBILE_NATIONAL_PRIORITY.forEachIndexed { idx, aliases ->
        if (aliases.any { a -> norm == a || norm.startsWith("$a ") || norm.contains(" $a") }) return idx
    }
    if (MOBILE_REGIONAL_KW.any { norm.contains(it) }) return 100
    if (norm.contains("peru") || norm.contains("congreso") || norm.contains("justicia tv") || norm.contains("ipe")) return 150
    return 999
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    val prefs = remember { context.getSharedPreferences("tv_libre_prefs", Context.MODE_PRIVATE) }

    var customUrls by remember {
        mutableStateOf(prefs.getStringSet("custom_m3u_urls", emptySet())?.toList() ?: emptyList())
    }
    var showM3uDialog by remember { mutableStateOf(false) }
    var dialogTab by remember { mutableStateOf(0) }
    var newM3uInput by remember { mutableStateOf("") }
    var xtreamHost by remember { mutableStateOf("") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPass by remember { mutableStateOf("") }

    var selectedTab by remember { mutableStateOf("TV") }
    var selectedSub by remember { mutableStateOf("Todos") }
    var searchQuery by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("Cargando...") }
    var playerStatus by remember { mutableStateOf("Iniciando...") }

    val remoteJsonUrl = "https://raw.githubusercontent.com/pabloch-ayal/tv-libre-apk/main/catalogo.json"
    val peruM3uUrl = "https://iptv-org.github.io/iptv/countries/pe.m3u"
    val plutoEsUrl = "https://i.mjh.nz/PlutoTV/es.m3u8"
    val plexEsUrl = "https://i.mjh.nz/Plex/es.m3u8"
    val latamM3uUrl = "https://iptv-org.github.io/iptv/languages/spa.m3u"

    val defaultVodList = remember {
        listOf(
            MediaEntry("Tears of Steel (2012) Ciencia Ficción HD", "Películas • Ciencia Ficción", "VOD", "HD", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"),
            MediaEntry("Sintel (2010) Fantasía y Aventura HD", "Películas • Animación", "VOD", "HD", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4"),
            MediaEntry("La Noche de los Muertos Vivientes (1968)", "Películas • Terror", "VOD", "CLÁSICO", "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_512kb.mp4")
        )
    }

    val defaultTvList = remember {
        listOf(
            MediaEntry("DW Español (Prueba Directa)", "Noticias • Internacional", "TV", "HD", "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8"),
            MediaEntry("France 24 Español", "Noticias • Internacional", "TV", "HD", "https://static.france24.com/live/F24_ES_HI_HLS/live_web.m3u8")
        )
    }

    var catalog by remember { mutableStateOf(defaultTvList) }
    var currentMedia by remember { mutableStateOf(defaultTvList.first()) }

    suspend fun loadAllFastSources(includeFullLatam: Boolean = false, extraUrls: List<String> = customUrls) {
        statusMsg = "Sincronizando listas..."
        withContext(Dispatchers.IO) {
            val combined = mutableListOf<MediaEntry>()
            var customCount = 0
            var customErr = ""

            extraUrls.forEachIndexed { idx, u ->
                try {
                    val raw = downloadTextWithAgent(u)
                    val parsed = parseM3uContent(raw, "Mi Lista ${idx + 1}", "PRIVADA")
                    customCount += parsed.size
                    combined.addAll(parsed)
                } catch (e: Exception) {
                    customErr = e.localizedMessage ?: "Error de conexión"
                }
            }

            try {
                val arr = JSONArray(downloadTextWithAgent(remoteJsonUrl))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = o.getString("title")
                    val rawCat = o.optString("category", "General")
                    val type = o.optString("type", "TV")
                    if (type != "VOD") {
                        val rank = getMobileNationalRank(title)
                        val genre = if (rank < 100) "Nacionales" else if (rank < 150) "Regionales" else classifyGenre(title, rawCat)
                        combined.add(MediaEntry(title, "$genre • $rawCat", "TV", if (rank < 200) "PERÚ" else o.optString("badge", "VIVO"), o.getString("streamUrl")))
                    }
                }
            } catch (_: Exception) { }

            if (includeFullLatam) {
                try {
                    val raw = downloadTextWithAgent(latamM3uUrl)
                    combined.addAll(parseM3uContent(raw, "Latinoamérica", "LATAM").take(150))
                } catch (_: Exception) { }
            }

            combined.addAll(defaultTvList)

            val distinctList = combined.distinctBy { it.streamUrl }.sortedWith(compareBy<MediaEntry>({ getMobileNationalRank(it.title) }, { it.title.lowercase() }))
            withContext(Dispatchers.Main) {
                catalog = distinctList
                statusMsg = when {
                    customCount > 0 -> "${distinctList.size} canales ($customCount en Mi Lista)"
                    customErr.isNotEmpty() -> "Error M3U: $customErr"
                    else -> "${distinctList.size} canales verificados"
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadAllFastSources(includeFullLatam = false) }

    val exoPlayer = remember {
        val headers = mapOf(
            "Referer" to "https://www.google.com/",
            "Origin" to "https://www.google.com"
        )
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setDefaultRequestProperties(headers)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
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
                        playerStatus = "Fallo: ${error.errorCodeName}"
                    }
                })
            }
    }

    LaunchedEffect(currentMedia) {
        playerStatus = "Conectando transmisión..."
        val builder = MediaItem.Builder().setUri(currentMedia.streamUrl)
        if (currentMedia.streamUrl.contains("m3u8", true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(builder.build())
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    if (showM3uDialog) {
        AlertDialog(
            onDismissRequest = { showM3uDialog = false },
            title = { Text("Agregar Lista IPTV / Xtream", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = dialogTab == 0, onClick = { dialogTab = 0 }, label = { Text("Xtream Codes") })
                        FilterChip(selected = dialogTab == 1, onClick = { dialogTab = 1 }, label = { Text("Enlace M3U") })
                    }
                    if (dialogTab == 0) {
                        OutlinedTextField(
                            value = xtreamHost,
                            onValueChange = { xtreamHost = it },
                            placeholder = { Text("Servidor: http://ejemplo.com:8080") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = xtreamUser,
                            onValueChange = { xtreamUser = it },
                            placeholder = { Text("Usuario") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = xtreamPass,
                            onValueChange = { xtreamPass = it },
                            placeholder = { Text("Contraseña") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = newM3uInput,
                            onValueChange = { newM3uInput = it },
                            placeholder = { Text("http://servidor.com/lista.m3u") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (customUrls.isNotEmpty()) {
                        Text("Cuentas/Listas guardadas (${customUrls.size}):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        customUrls.forEach { savedUrl ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(savedUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    val updated = customUrls.filter { it != savedUrl }
                                    customUrls = updated
                                    prefs.edit().putStringSet("custom_m3u_urls", updated.toSet()).apply()
                                    scope.launch { loadAllFastSources(extraUrls = updated) }
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, null, tint = LiveRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val generatedUrl = if (dialogTab == 0) {
                        val host = xtreamHost.trim().trimEnd('/')
                        val u = xtreamUser.trim()
                        val p = xtreamPass.trim()
                        if (host.startsWith("http") && u.isNotEmpty() && p.isNotEmpty()) {
                            "$host/get.php?username=$u&password=$p&type=m3u_plus&output=ts"
                        } else ""
                    } else {
                        newM3uInput.trim()
                    }
                    if (generatedUrl.startsWith("http")) {
                        val updated = (customUrls + generatedUrl).distinct()
                        customUrls = updated
                        prefs.edit().putStringSet("custom_m3u_urls", updated.toSet()).apply()
                        newM3uInput = ""
                        xtreamHost = ""
                        xtreamUser = ""
                        xtreamPass = ""
                        showM3uDialog = false
                        scope.launch { loadAllFastSources(extraUrls = updated) }
                    }
                }) { Text("Conectar y Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showM3uDialog = false }) { Text("Cerrar") }
            }
        )
    }

    val deviceId = remember {
        val raw = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0000"
        "MV-" + raw.takeLast(6).uppercase()
    }
    var isActivated by remember { mutableStateOf(prefs.getBoolean("app_activated", false)) }
    val firstRunMs = remember {
        val saved = prefs.getLong("first_run_ms", 0L)
        if (saved == 0L) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong("first_run_ms", now).apply()
            now
        } else saved
    }
    val trialExpired = !isActivated && ((System.currentTimeMillis() - firstRunMs) > 7L * 24L * 60L * 60L * 1000L)
    var keyInput by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf(false) }

    fun validateMobileKey(id: String, key: String): Boolean {
        val clean = key.trim().uppercase()
        if (clean == "CESAR7" || clean == "2026IA") return true
        val seed = (id.uppercase() + ":TVLIBRE_CESAR_IA_2026")
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return clean == md.joinToString("") { "%02X".format(it) }.take(6)
    }

    if (trialExpired) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TV LIBRE PLUS • César IA", color = AccentCyan, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.height(10.dp))
            Text("Tu prueba gratuita de 7 días ha terminado.", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text("Envía este ID por WhatsApp al 939525004 para activar:", color = Color.LightGray, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(10.dp)) {
                Text("ID: $deviceId", color = AccentCyan, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it.uppercase(); keyError = false },
                placeholder = { Text("Código de 6 caracteres") },
                singleLine = true
            )
            if (keyError) {
                Spacer(Modifier.height(6.dp))
                Text("Código incorrecto. Verifica e intenta de nuevo.", color = Color(0xFFF43F5E), fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                if (validateMobileKey(deviceId, keyInput)) {
                    prefs.edit().putBoolean("app_activated", true).apply()
                    isActivated = true
                } else keyError = true
            }) {
                Text("Activar Aplicación", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    val baseItems = remember(catalog, selectedTab) {
        if (selectedTab == "MI_LISTA") catalog.filter { it.badge == "PRIVADA" }
        else catalog.filter { it.badge != "PRIVADA" && it.type == "TV" }
    }
    val subs = remember(baseItems, selectedTab) {
        val list = mutableListOf("Todos")
        if (selectedTab == "MI_LISTA") {
            baseItems.map { it.category.substringAfter("•").trim().ifBlank { it.category } }
                .distinct()
                .forEach { if (it !in list) list.add(it) }
        } else {
            val candidates = listOf("Nacionales", "Regionales", "Perú", "Noticias", "Deportes", "Cine", "Documentales", "Infantil", "Música", "Latinoamérica")
            for (cat in candidates) {
                if (baseItems.any { it.category.contains(cat, true) || it.title.contains(cat, true) }) {
                    list.add(cat)
                }
            }
        }
        list
    }
    if (selectedSub !in subs) selectedSub = "Todos"

    val filtered = baseItems.filter {
        (selectedSub == "Todos" || (selectedSub == "Mi Lista" && it.badge == "PRIVADA") || it.category.contains(selectedSub, true) || it.title.contains(selectedSub, true)) &&
        (it.title.contains(searchQuery, true) || it.category.contains(searchQuery, true))
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F172A), BgDeepNavy))).then(if (!isLandscape) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
        if (!isLandscape) {
            Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF1E3A8A), Color(0xFF6D28D9), Color(0xFFDB2777)))).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LiveTv, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("TV LIBRE PLUS", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text("Creado por César Ayal", color = Color(0xFF67E8F9), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(statusMsg, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showM3uDialog = true }, colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.22f)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("+ M3U", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { scope.launch { loadAllFastSources(includeFullLatam = true) } }, colors = ButtonDefaults.textButtonColors(containerColor = Color.White.copy(alpha = 0.22f)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("+ Latam", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { scope.launch { loadAllFastSources(includeFullLatam = false) } }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).size(32.dp)) {
                        Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Box(if (isLandscape) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxWidth().height(225.dp).background(Color.Black)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } }, modifier = Modifier.fillMaxSize())
            if (playerStatus.isNotEmpty() && playerStatus != "Reproduciendo") {
                Surface(color = Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                    Text(playerStatus, color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
        if (!isLandscape) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF111827)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(currentMedia.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(currentMedia.category, color = AccentCyan, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentMedia.streamUrl))
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                    },
                    colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("Abrir externo", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { selectedTab = "TV"; selectedSub = "Todos" }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == "TV") AccentCyan else CardDarkBlue, contentColor = if (selectedTab == "TV") Color.Black else Color.White), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("TV y Canales", fontWeight = FontWeight.Bold)
                }
                Button(onClick = { selectedTab = "MI_LISTA"; selectedSub = "Todos" }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == "MI_LISTA") AccentOrange else CardDarkBlue, contentColor = Color.White), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.VideoLibrary, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Mi Lista M3U", fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subs.forEach { f -> FilterChip(selected = selectedSub == f, onClick = { selectedSub = f }, label = { Text(f) }) }
            }
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("Buscar canal, Mi Lista, cine...") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
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
