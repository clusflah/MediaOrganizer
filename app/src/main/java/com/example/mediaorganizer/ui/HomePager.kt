package com.example.mediaorganizer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mediaorganizer.MainViewModel
import kotlinx.coroutines.launch

private val TABS = listOf("影视" to AppIcons.Movie, "音乐" to AppIcons.MusicNote)

/**
 * 首页：影视整理和音乐整理并列，点标签或左右滑动切换。
 * 标题栏和标签是两页共用的；影视页的「⋮」菜单只在影视页显示。
 * 多选或正在扫描、整理时不能滑动切换，免得误触把进行中的列表滑走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePager(vm: MainViewModel, openNaming: () -> Unit, openScrape: () -> Unit) {
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()
    var askUndo by remember { mutableStateOf(false) }
    val selecting = vm.selected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        if (!selecting) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("影视整理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (pager.currentPage == 0) "整理影片 · 刮削海报和 NFO" else "整理音乐 · 封面、NFO 和歌词",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (pager.currentPage == 0) MainMenu(vm, openNaming, openScrape, onUndo = { askUndo = true })
                },
            )
            PrimaryTabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                modifier = Modifier.padding(horizontal = PageGutter - 4.dp),
            ) {
                TABS.forEachIndexed { i, (label, icon) ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(label, fontWeight = if (pager.currentPage == i) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalPager(
            pager, Modifier.weight(1f),
            userScrollEnabled = !selecting && !vm.busy,
        ) { page ->
            when (page) {
                0 -> MainScreen(vm, openNaming, openScrape, askUndo = askUndo, onAskUndo = { askUndo = it })
                else -> MusicScreen(vm)
            }
        }
    }
}
